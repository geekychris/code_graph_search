package com.codegraph.app.deps;

import com.codegraph.core.config.AppConfig;
import com.codegraph.core.config.RepoConfig;
import com.codegraph.core.model.*;
import com.codegraph.indexer.IndexerService;
import com.codegraph.parser.ParserRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A library repo and an app repo that uses it. The app is indexed first, so its
 * references to the library only become resolvable when the library is indexed.
 */
class DependencyServiceTest {

    @TempDir Path tmp;
    private IndexerService indexer;
    private DependencyService deps;

    @BeforeEach
    void setUp() throws IOException {
        AppConfig.IndexerConfig ic = new AppConfig.IndexerConfig();
        ic.setDataDir(tmp.resolve("data").toString());
        ic.setIndexingThreads(2);
        indexer = new IndexerService(ic);
        deps = new DependencyService(indexer);
        deps.load();
        indexer.addIndexListener(deps::onRepoIndexed);

        write("lib/pom.xml", pom("com.x", "kv-lib", "4.0.0", ""));
        write("lib/src/main/java/com/x/kv/Store.java", """
            package com.x.kv;
            public class Store extends Base {
                public static void put(String k) {}
            }""");
        write("lib/src/main/java/com/x/kv/Base.java", "package com.x.kv; public class Base {}");

        write("app/pom.xml", pom("com.y", "my-app", "1.0",
                "<dependency><groupId>com.x</groupId><artifactId>kv-lib</artifactId><version>3.9.0</version></dependency>"));
        write("app/src/main/java/com/y/App.java", """
            package com.y;
            import com.x.kv.Store;
            import com.x.kv.Base;
            public class App extends Base {
                public void save() { Store.put("a"); }
            }""");
    }

    @AfterEach
    void tearDown() {
        indexer.close();
    }

    @Test
    void buildsTheRepoGraphAndDrift() {
        index("app");
        index("lib");
        var g = deps.get();
        assertThat(g.dependenciesOf("app", r -> true)).singleElement()
                .satisfies(e -> {
                    assertThat(e.toRepo()).isEqualTo("lib");
                    assertThat(e.coordinate()).isEqualTo("maven:com.x:kv-lib");
                });
        assertThat(g.drift(r -> true)).singleElement()
                .satisfies(d -> assertThat(d.usedVersion()).isEqualTo("3.9.0"));
        assertThat(indexer.listRepoFacts(DependencyService.FACT_KIND)).containsKeys("app", "lib");
    }

    @Test
    void resolvesCallsExtendsAndImportsAcrossRepos() {
        index("app");
        index("lib");

        CodeElement put = find("lib", "put", ElementType.METHOD);
        CodeElement base = find("lib", "Base", ElementType.CLASS);
        CodeElement save = find("app", "save", ElementType.METHOD);
        CodeElement app = find("app", "App", ElementType.CLASS);

        assertThat(indexer.getCallers(put.getId())).extracting(CodeElement::getId).contains(save.getId());
        assertThat(indexer.getEdgesTo(base.getId(), EdgeType.EXTENDS)).extracting(CodeEdge::getFromId)
                .contains(app.getId());
        assertThat(indexer.getEdgesTo(put.getId(), EdgeType.CALLS)).allSatisfy(e ->
                assertThat(e.getMetadata()).containsEntry(CodeEdge.META_RESOLVED, "cross-repo"));
        // the import of com.x.kv.Store points at the Store class in the library
        CodeElement store = find("lib", "Store", ElementType.CLASS);
        assertThat(indexer.getEdgesTo(store.getId(), EdgeType.IMPORTS)).isNotEmpty();
    }

    @Test
    void doesNotLinkReposWithoutADependency() throws IOException {
        write("other/pom.xml", pom("com.z", "other", "1.0", ""));
        write("other/src/main/java/com/z/Other.java", """
            package com.z;
            public class Other { public void go() { com.x.kv.Store.put("b"); } }""");
        index("lib");
        index("other");
        CodeElement put = find("lib", "put", ElementType.METHOD);
        assertThat(indexer.getCallers(put.getId())).noneMatch(e -> e.getRepoId().equals("other"));
    }

    // ---- helpers ----

    private void index(String id) {
        RepoConfig rc = new RepoConfig();
        rc.setId(id);
        rc.setPath(tmp.resolve(id).toString());
        indexer.indexRepository(rc, ParserRegistry.withDefaults());
    }

    private CodeElement find(String repo, String name, ElementType type) {
        SearchQuery q = new SearchQuery();
        q.setRepoIds(List.of(repo));
        q.setLimit(500);
        return indexer.search(q).stream().filter(e -> name.equals(e.getName()) && e.getElementType() == type)
                .findFirst().orElseThrow(() -> new AssertionError("no " + type + " " + name + " in " + repo));
    }

    private void write(String rel, String content) throws IOException {
        Path p = tmp.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static String pom(String g, String a, String v, String deps) {
        return "<project><groupId>" + g + "</groupId><artifactId>" + a + "</artifactId><version>" + v
                + "</version><dependencies>" + deps + "</dependencies></project>";
    }
}
