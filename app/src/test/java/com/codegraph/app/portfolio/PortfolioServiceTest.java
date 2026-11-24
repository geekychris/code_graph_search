package com.codegraph.app.portfolio;

import com.codegraph.core.config.AppConfig;
import com.codegraph.core.model.CodeElement;
import com.codegraph.core.model.Repository;
import com.codegraph.core.model.SearchQuery;
import com.codegraph.core.model.Visibility;
import com.codegraph.indexer.IndexerService;
import com.codegraph.parser.ParserRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Syncs a "GitHub" listing that points at local git repositories, so the whole
 * list → clone → index → push → incremental re-index loop runs without a network.
 */
class PortfolioServiceTest {

    @TempDir Path tmp;

    private IndexerService indexer;
    private PortfolioService portfolio;
    private Path origin;
    private final List<RepoLister.RemoteRepo> listing = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        AppConfig.IndexerConfig ic = new AppConfig.IndexerConfig();
        ic.setDataDir(tmp.resolve("data").toString());
        ic.setIndexingThreads(2);
        indexer = new IndexerService(ic);

        origin = tmp.resolve("origin/widgets");
        Files.createDirectories(origin.resolve("src"));
        git(origin, "init", "-q", "-b", "main");
        git(origin, "config", "user.email", "test@example.com");
        git(origin, "config", "user.name", "Test");
        write("src/Alpha.java", "package w; public class Alpha { public void run() {} }");
        write("src/Gamma.java", "package w; public class Gamma { public void keep() {} }");
        commit("first");

        AppConfig.PortfolioConfig pc = new AppConfig.PortfolioConfig();
        pc.setEnabled(true);
        pc.setOwners(List.of("someone"));
        pc.setConcurrency(1);
        GitMirror mirror = new GitMirror(tmp.resolve("mirrors"), null, 60);
        portfolio = new PortfolioService(pc, indexer, ParserRegistry.withDefaults(), owner -> listing, mirror);

        listing.add(remote("widgets", false, false, Instant.parse("2026-11-01T00:00:00Z")));
        listing.add(remote("forked-thing", true, true, Instant.parse("2026-11-01T00:00:00Z")));
    }

    @AfterEach
    void tearDown() {
        portfolio.stop();
        indexer.close();
    }

    @Test
    void syncIndexesNewReposAndSkipsForks() throws Exception {
        PortfolioService.SyncReport report = portfolio.syncAll();

        assertThat(report.listed()).isEqualTo(2);
        assertThat(report.tracked()).isEqualTo(1);
        assertThat(report.skipped()).isEqualTo(1);
        awaitIdle();

        Repository repo = indexer.getRepository("widgets").orElseThrow();
        assertThat(repo.getVisibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(repo.getSource()).isEqualTo("github");
        assertThat(repo.getIndexedCommit()).isEqualTo(head());
        assertThat(repo.getStatus()).isEqualTo(Repository.IndexingStatus.READY);
        assertThat(names("widgets")).contains("Alpha", "Gamma");
        assertThat(indexer.getRepository("forked-thing")).isEmpty();
    }

    @Test
    void pushReindexesOnlyChangedFiles() throws Exception {
        portfolio.syncAll();
        awaitIdle();
        String gammaId = idOf("widgets", "Gamma");
        String firstCommit = head();

        // Delete Alpha, add Beta, leave Gamma alone
        Files.delete(origin.resolve("src/Alpha.java"));
        write("src/Beta.java", "package w; public class Beta { public void go() {} }");
        commit("second");

        // Nothing re-indexes until GitHub reports a newer push
        PortfolioService.SyncReport unchanged = portfolio.syncAll();
        assertThat(unchanged.queued()).isZero();

        listing.set(0, remote("widgets", false, false, Instant.parse("2026-11-02T00:00:00Z")));
        PortfolioService.SyncReport pushed = portfolio.syncAll();
        assertThat(pushed.queued()).isEqualTo(1);
        awaitIdle();

        Repository repo = indexer.getRepository("widgets").orElseThrow();
        assertThat(repo.getIndexedCommit()).isEqualTo(head()).isNotEqualTo(firstCommit);
        assertThat(names("widgets")).contains("Beta", "Gamma").doesNotContain("Alpha");
        // Gamma was not touched, so it keeps its id
        assertThat(idOf("widgets", "Gamma")).isEqualTo(gammaId);
        assertThat(indexer.listFilePaths("widgets")).doesNotContain("src/Alpha.java");
    }

    @Test
    void visibilityFollowsGitHub() throws Exception {
        portfolio.syncAll();
        awaitIdle();
        listing.set(0, new RepoLister.RemoteRepo("widgets", "someone/widgets", true, false, false,
                origin.toUri().toString(), "https://example.invalid/widgets", "main",
                Instant.parse("2026-11-01T00:00:00Z"), null, 1));
        portfolio.syncAll();
        assertThat(indexer.getRepository("widgets").orElseThrow().getVisibility()).isEqualTo(Visibility.PRIVATE);
    }

    @Test
    void webhookSignatureIsRequired() {
        // No secret configured: every signature is rejected
        assertThat(portfolio.verifySignature("{}".getBytes(), "sha256=00")).isFalse();
        assertThat(portfolio.verifySignature("{}".getBytes(), null)).isFalse();
    }

    @Test
    void parsesNameStatus() {
        GitMirror.ChangeSet cs = GitMirror.parseNameStatus("M\0src/A.java\0D\0src/B.java\0A\0src/c d.java\0");
        assertThat(cs.changed()).containsExactly("src/A.java", "src/c d.java");
        assertThat(cs.deleted()).containsExactly("src/B.java");
    }

    // ---- helpers ----

    private RepoLister.RemoteRepo remote(String name, boolean isPrivate, boolean fork, Instant pushedAt) {
        return new RepoLister.RemoteRepo(name, "someone/" + name, isPrivate, fork, false,
                origin.toUri().toString(), "https://example.invalid/" + name, "main", pushedAt, null, 1);
    }

    private void awaitIdle() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        Thread.sleep(50);
        while (!portfolio.activeJobs().isEmpty()) {
            if (System.nanoTime() > deadline) throw new AssertionError("index jobs did not finish");
            Thread.sleep(50);
        }
    }

    private List<String> names(String repoId) {
        SearchQuery q = new SearchQuery();
        q.setRepoIds(List.of(repoId));
        q.setLimit(500);
        return indexer.search(q).stream().map(CodeElement::getName).toList();
    }

    private String idOf(String repoId, String name) {
        SearchQuery q = new SearchQuery();
        q.setRepoIds(List.of(repoId));
        q.setLimit(500);
        return indexer.search(q).stream().filter(e -> name.equals(e.getName()))
                .map(CodeElement::getId).findFirst().orElseThrow();
    }

    private void write(String rel, String content) throws IOException {
        Path p = origin.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private void commit(String message) throws Exception {
        git(origin, "add", "-A");
        git(origin, "commit", "-q", "-m", message);
    }

    private String head() throws Exception {
        return git(origin, "rev-parse", "HEAD").trim();
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IllegalStateException("git " + String.join(" ", args) + ": " + out);
        return out;
    }
}
