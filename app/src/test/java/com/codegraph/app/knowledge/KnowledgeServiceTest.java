package com.codegraph.app.knowledge;

import com.codegraph.app.deps.DependencyService;
import com.codegraph.core.config.AppConfig;
import com.codegraph.core.config.RepoConfig;
import com.codegraph.core.knowledge.*;
import com.codegraph.core.model.Repository;
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
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.*;

/**
 * A public library, a private app that uses it, and a repo-atlas checkout that
 * describes both.
 */
class KnowledgeServiceTest {

    @TempDir Path tmp;
    private IndexerService indexer;
    private KnowledgeServiceImpl knowledge;
    private final Predicate<String> all = r -> true;
    private final Predicate<String> publicOnly = r -> r.equals("lib") || r.equals("repo-atlas");

    @BeforeEach
    void setUp() throws IOException {
        AppConfig.IndexerConfig ic = new AppConfig.IndexerConfig();
        ic.setDataDir(tmp.resolve("data").toString());
        ic.setIndexingThreads(2);
        indexer = new IndexerService(ic);
        DependencyService deps = new DependencyService(indexer);
        deps.load();
        indexer.addIndexListener(deps::onRepoIndexed);
        knowledge = new KnowledgeServiceImpl(indexer, deps, "repo-atlas", null);
        knowledge.load();
        indexer.addIndexListener(knowledge::onRepoIndexed);

        write("lib/pom.xml", pom("com.x", "kv-lib", "1.0", ""));
        write("lib/src/main/java/com/x/kv/store/Store.java", """
            package com.x.kv.store;
            import com.x.kv.codec.Codec;
            public class Store {
                public static void put(String k) { Codec.encode(k); }
            }""");
        write("lib/src/main/java/com/x/kv/codec/Codec.java", """
            package com.x.kv.codec;
            public class Codec { public static String encode(String s) { return s; } }""");
        write("app/pom.xml", pom("com.y", "my-app", "1.0",
                "<dependency><groupId>com.x</groupId><artifactId>kv-lib</artifactId><version>1.0</version></dependency>"));
        write("app/src/main/java/com/y/App.java", """
            package com.y;
            import com.x.kv.store.Store;
            public class App { public void save() { Store.put("a"); } }""");

        write("atlas/AGENTS.md", "# atlas");
        write("atlas/repos/_TEMPLATE.md", "---\nname: x\n---\n");
        write("atlas/repos/lib.md", """
            ---
            name: lib
            family: storage
            category: library
            status: active
            maturity: 3
            purpose: A tiny key-value store.
            tech: [Java 21, Maven]
            tags: [key-value, codec]
            related: [app]
            ---

            ## Purpose
            Stores things.

            ## Design
            A store and a codec.
            """);
        write("atlas/repos/app.md", """
            ---
            name: app
            family: storage
            purpose: Secret app.
            tags: [key-value]
            ---
            """);
        write("atlas/synthesis/overview.md", "# Portfolio\nAll of it.");
    }

    @AfterEach
    void tearDown() {
        indexer.close();
    }

    @Test
    void importsAtlasCardsWhenTheAtlasRepoIsIndexed() {
        index("lib", Visibility.PUBLIC);
        index("app", Visibility.PRIVATE);
        index("repo-atlas", "atlas", Visibility.PRIVATE);

        RepoCard lib = knowledge.card("lib").orElseThrow();
        assertThat(lib.family()).isEqualTo("storage");
        assertThat(lib.maturity()).isEqualTo(3);
        assertThat(lib.sections()).containsKeys("Purpose", "Design");
        assertThat(knowledge.cards(all)).extracting(RepoCard::repoId).containsExactly("app", "lib");
        // the public view sees lib's card, without the private related repo
        assertThat(knowledge.cards(publicOnly)).singleElement()
                .satisfies(c -> assertThat(c.related()).isEmpty());
        assertThat(knowledge.synthesis(all)).containsKey("overview");
        assertThat(knowledge.synthesis(r -> r.equals("lib"))).isEmpty();
    }

    @Test
    void tagConceptsSpanReposAndRespectVisibility() {
        index("lib", Visibility.PUBLIC);
        index("app", Visibility.PRIVATE);
        index("repo-atlas", "atlas", Visibility.PRIVATE);

        ConceptIndexEntry kv = knowledge.concept("key-value", all).orElseThrow();
        assertThat(kv.repos()).extracting(ConceptIndexEntry.Usage::repoId).containsExactlyInAnyOrder("lib", "app");
        assertThat(knowledge.concept("key-value", publicOnly).orElseThrow().repos())
                .extracting(ConceptIndexEntry.Usage::repoId).containsExactly("lib");
        assertThat(knowledge.searchConcepts("key", null, all, 10)).extracting(ConceptIndexEntry::slug).contains("key-value");
        assertThat(knowledge.concept("family-storage", all)).isPresent();
    }

    @Test
    void architectureGroupsPackagesAndCountsReferences() {
        index("lib", Visibility.PUBLIC);
        index("app", Visibility.PRIVATE);

        // src/main/java and the single-child chain com/x/kv are walked through
        Architecture top = knowledge.architecture("lib", "", all);
        assertThat(top.path()).isEqualTo("com/x/kv");
        assertThat(top.groups()).extracting(Architecture.Group::id).containsExactlyInAnyOrder("com/x/kv/store", "com/x/kv/codec");
        assertThat(top.edges()).anySatisfy(e -> {
            assertThat(e.from()).isEqualTo("com/x/kv/store");
            assertThat(e.to()).isEqualTo("com/x/kv/codec");
        });
        // Store is called from the app repo; Codec from Store
        assertThat(top.keyTypes()).extracting(Architecture.KeyType::name).contains("Store", "Codec");
        assertThat(top.keyTypes()).filteredOn(t -> t.name().equals("Store"))
                .singleElement().satisfies(t -> assertThat(t.crossRepoInbound()).isPositive());

        Architecture app = knowledge.architecture("app", "", all);
        assertThat(app.external()).singleElement().satisfies(x -> assertThat(x.repoId()).isEqualTo("lib"));
        assertThat(knowledge.architecture("app", "", publicOnly.and(r -> !r.equals("lib"))).external()).isEmpty();

        Architecture inside = knowledge.architecture("lib", "com/x/kv/store", all);
        // Store.java, plus the rest of the repo it calls into (the codec)
        assertThat(inside.groups()).extracting(Architecture.Group::label).containsExactly("Store.java", "rest of repo");
        assertThat(inside.edges()).singleElement().satisfies(e -> assertThat(e.to()).isEqualTo(".."));
        assertThat(inside.breadcrumbs()).containsExactly("com", "com/x", "com/x/kv", "com/x/kv/store");
    }

    @Test
    void sessionsClaimAndSubmitConceptsWithResolvedAnchors() {
        index("lib", Visibility.PUBLIC);
        index("app", Visibility.PRIVATE);

        assertThat(knowledge.pendingWork(all, 10)).extracting(KnowledgeService.WorkItem::repoId).contains("lib", "app");
        var bundle = knowledge.claim("lib", "test", all).orElseThrow();
        assertThat(bundle).containsKeys("repo", "architecture", "instructions");
        assertThat(knowledge.pendingWork(all, 10)).extracting(KnowledgeService.WorkItem::repoId).doesNotContain("lib");

        var result = knowledge.submit("lib", new KnowledgeService.Submission(
                "A key-value store. Used by app.",
                "```mermaid\nflowchart LR\n  Store --> Codec\n```",
                List.of(new Concept(null, "Key Value Store", "infrastructure", "Stores strings", null, "Call Store.put",
                                List.of(new Anchor(null, "com.x.kv.store.Store", null, null, null),
                                        new Anchor(null, null, "src/main/java/com/x/kv/codec/Codec.java", null, null),
                                        new Anchor(null, "com.x.nope.Missing", null, null, null)), null),
                        new Concept(null, "Codec", "weird-kind", "Encodes", null, null, List.of(), null))),
                "test");
        assertThat(result.concepts()).isEqualTo(2);
        assertThat(result.anchorsResolved()).isEqualTo(2);
        assertThat(result.anchorsUnresolved()).isEqualTo(1);
        // lib is public and the text names the private repo "app"? too short to check (< 5 chars): no warning
        RepoKnowledge k = knowledge.knowledge("lib").orElseThrow();
        assertThat(k.diagram()).startsWith("flowchart LR");
        assertThat(k.concepts()).extracting(Concept::kind).containsExactly("infrastructure", "capability");
        assertThat(k.concepts().get(0).anchors().get(0).elementId()).isNotNull();

        ConceptIndexEntry c = knowledge.concept("key-value-store", all).orElseThrow();
        assertThat(c.source()).isEqualTo("session");
        assertThat(c.repos()).singleElement().satisfies(u -> assertThat(u.howToUse()).isEqualTo("Call Store.put"));
        assertThat(knowledge.pendingWork(all, 10)).extracting(KnowledgeService.WorkItem::repoId).doesNotContain("lib");
    }

    @Test
    void rejectsDiagramsThatAreNotMermaid() {
        index("lib", Visibility.PUBLIC);
        assertThatThrownBy(() -> knowledge.submit("lib",
                new KnowledgeService.Submission(null, "<svg/>", List.of()), "test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> knowledge.submit("lib",
                new KnowledgeService.Submission(null, null, List.of()), "test"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void warnsWhenAPublicWriteUpNamesAPrivateRepo() throws IOException {
        write("secretsauce/README.md", "# secret");
        write("secretsauce/src/A.java", "package a; public class A {}");
        index("lib", Visibility.PUBLIC);
        index("secretsauce", Visibility.PRIVATE);
        var result = knowledge.submit("lib", new KnowledgeService.Submission(
                "Also powers secretsauce.", null, List.of()), "test");
        assertThat(result.warnings()).anyMatch(w -> w.contains("secretsauce"));

        // the private name inside a longer repo name, a package or an anchor is not a mention
        var ok = knowledge.submit("lib", new KnowledgeService.Submission(
                "Built on secretsauce-core and com.secretsauce.util; see secretsauce.", null,
                List.of(new Concept(null, "Thing", "api", "Uses secretsauce-lib.", null, null,
                        List.of(new Anchor(null, "com.x.secretsauce.Thing", null, null, null)), null))), "test");
        assertThat(ok.warnings()).anyMatch(w -> w.contains("secretsauce"));   // "see secretsauce." at the end
        var clean = knowledge.submit("lib", new KnowledgeService.Submission(
                "Built on secretsauce-core and com.secretsauce.util.", null,
                List.of(new Concept(null, "Thing", "api", "Uses secretsauce-lib.", null, null,
                        List.of(new Anchor(null, "com.x.secretsauce.Thing", null, null, null)), null))), "test");
        assertThat(clean.warnings()).noneMatch(w -> w.contains("secretsauce"));
    }

    // ---- helpers ----

    private void index(String id, Visibility v) {
        index(id, id, v);
    }

    private void index(String id, String dir, Visibility v) {
        RepoConfig rc = new RepoConfig();
        rc.setId(id);
        rc.setPath(tmp.resolve(dir).toString());
        rc.setVisibility(v);
        // as Main does: register the repo with its visibility, then index
        Repository pre = new Repository();
        pre.setId(id);
        pre.setName(id);
        pre.setRootPath(rc.getPath());
        pre.setVisibility(v);
        indexer.saveRepository(pre);
        indexer.indexRepository(rc, ParserRegistry.withDefaults());
        Repository r = indexer.getRepository(id).orElseThrow();
        assertThat(r.getVisibility()).isEqualTo(v);
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
