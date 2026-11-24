package com.codegraph.tests;

import com.codegraph.core.config.AppConfig;
import com.codegraph.core.config.RepoConfig;
import com.codegraph.core.model.*;
import com.codegraph.core.security.Principal;
import com.codegraph.core.security.RequestContext;
import com.codegraph.core.service.ScopedGraphService;
import com.codegraph.indexer.IndexerService;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A public repo and a private repo with an edge between them. Callers without private
 * access must not see the private repo, its elements, or edges and paths into it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VisibilityTest {

    private static final Principal ALL = new Principal("all", true, false);

    private Path tmp;
    private IndexerService raw;
    private ScopedGraphService scoped;
    private CodeElement publicRun;
    private CodeElement privateHelp;

    @BeforeAll
    void setUp() throws Exception {
        tmp = Files.createTempDirectory("codegraph-visibility-");
        AppConfig.IndexerConfig ic = new AppConfig.IndexerConfig();
        ic.setDataDir(tmp.resolve("data").toString());
        ic.setIndexingThreads(2);
        raw = new IndexerService(ic);
        scoped = new ScopedGraphService(raw);

        index("open-lib", Visibility.PUBLIC, "Shared",
                "package o; public class Shared { public void run() {} }");
        index("secret-app", Visibility.PRIVATE, "Hidden",
                "package s; public class Hidden { public void help() {} }");

        publicRun = byName("open-lib", "run");
        privateHelp = byName("secret-app", "help");
        // A cross-repo call from public code into private code
        raw.saveEdge(new CodeEdge(publicRun.getId(), privateHelp.getId(), EdgeType.CALLS));
    }

    @AfterAll
    void tearDown() {
        RequestContext.clear();
        raw.close();
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    @Test
    void anonymousSeesOnlyPublicRepos() {
        List<Repository> repos = as(Principal.ANONYMOUS, scoped::listRepositories);
        assertThat(repos).extracting(Repository::getId).containsExactly("open-lib");
        assertThat(as(Principal.ANONYMOUS, () -> scoped.getRepository("secret-app"))).isEmpty();
        assertThat(as(ALL, scoped::listRepositories)).extracting(Repository::getId)
                .contains("open-lib", "secret-app");
    }

    @Test
    void searchAndCountAreFilteredInsideTheIndex() {
        SearchQuery q = new SearchQuery();
        q.setLimit(500);
        List<CodeElement> anon = as(Principal.ANONYMOUS, () -> scoped.search(q));
        assertThat(anon).isNotEmpty().allMatch(e -> e.getRepoId().equals("open-lib"));

        SearchQuery onlySecret = new SearchQuery();
        onlySecret.setRepoIds(List.of("secret-app"));
        assertThat(as(Principal.ANONYMOUS, () -> scoped.search(onlySecret))).isEmpty();
        assertThat(as(Principal.ANONYMOUS, () -> scoped.count(onlySecret))).isZero();

        SearchQuery all = new SearchQuery();
        all.setLimit(500);
        assertThat(as(ALL, () -> scoped.search(all))).anyMatch(e -> e.getRepoId().equals("secret-app"));
    }

    @Test
    void privateElementsLookAbsent() {
        assertThat(as(Principal.ANONYMOUS, () -> scoped.getElementById(privateHelp.getId()))).isEmpty();
        assertThat(as(Principal.ANONYMOUS, () -> scoped.getCallers(privateHelp.getId()))).isEmpty();
        assertThat(as(Principal.ANONYMOUS, () -> scoped.listFilePaths("secret-app"))).isEmpty();
        assertThat(as(ALL, () -> scoped.getElementById(privateHelp.getId()))).isPresent();
    }

    @Test
    void edgesIntoPrivateCodeAreHidden() {
        assertThat(as(Principal.ANONYMOUS, () -> scoped.getCallees(publicRun.getId())))
                .noneMatch(e -> e.getRepoId().equals("secret-app"));
        assertThat(as(Principal.ANONYMOUS, () -> scoped.getEdgesFrom(publicRun.getId(), EdgeType.CALLS)))
                .noneMatch(e -> e.getToId().equals(privateHelp.getId()));
        assertThat(as(ALL, () -> scoped.getEdgesFrom(publicRun.getId(), EdgeType.CALLS)))
                .anyMatch(e -> e.getToId().equals(privateHelp.getId()));
    }

    @Test
    void pathsDoNotCrossIntoPrivateCode() {
        PathQuery q = new PathQuery(publicRun.getId(), privateHelp.getId());
        PathResult anon = as(Principal.ANONYMOUS, () -> scoped.findShortestPath(q));
        assertThat(anon.getPaths()).isEmpty();

        PathResult all = as(ALL, () -> scoped.findShortestPath(new PathQuery(publicRun.getId(), privateHelp.getId())));
        assertThat(all.getPaths()).isNotEmpty();
    }

    @Test
    void subgraphDropsPrivateNodes() {
        GraphServiceSubGraph sub = new GraphServiceSubGraph(
                as(Principal.ANONYMOUS, () -> scoped.getSubGraph(publicRun.getId(), 2, null)));
        assertThat(sub.repoIds()).doesNotContain("secret-app");
    }

    @Test
    void unknownVisibilityFailsClosed() throws Exception {
        index("mystery", null, "Mystery", "package m; public class Mystery {}");
        assertThat(as(Principal.ANONYMOUS, scoped::listRepositories))
                .extracting(Repository::getId).doesNotContain("mystery");
    }

    // ---- helpers ----

    private record GraphServiceSubGraph(com.codegraph.core.service.GraphService.SubGraph g) {
        List<String> repoIds() { return g.nodes().stream().map(CodeElement::getRepoId).distinct().toList(); }
    }

    private static <T> T as(Principal p, Supplier<T> body) {
        return RequestContext.callAs(p, body);
    }

    private void index(String id, Visibility visibility, String cls, String source) throws Exception {
        Path dir = tmp.resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(cls + ".java"), source);
        RepoConfig rc = new RepoConfig();
        rc.setId(id);
        rc.setPath(dir.toString());
        raw.indexRepository(rc);
        Repository repo = raw.getRepository(id).orElseThrow();
        repo.setVisibility(visibility);
        scoped.saveRepository(repo);
    }

    private CodeElement byName(String repoId, String name) {
        SearchQuery q = new SearchQuery();
        q.setRepoIds(List.of(repoId));
        q.setLimit(100);
        return raw.search(q).stream().filter(e -> name.equals(e.getName())).findFirst().orElseThrow();
    }
}
