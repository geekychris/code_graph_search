package com.codegraph.deps;

import com.codegraph.deps.graph.DependencyGraph;
import com.codegraph.deps.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyGraphTest {

    private static final DependencyGraph G = DependencyGraph.build(Map.of(
            "core", repo(module("pom.xml", "com.h:core", "4.0.0")),
            "util", repo(module("pom.xml", "com.h:util", "4.0.0", dep("com.h:core", "4.0.0", "compile"))),
            "index", repo(module("pom.xml", "com.h:index", "4.0.0",
                    dep("com.h:util", "4.0.0", "compile"), dep("com.h:core", "3.1.0", "compile"),
                    dep("org.apache.lucene:lucene-core", "9.12.0", "compile"))),
            "app", repo(module("pom.xml", "com.h:app", "1.0",
                    dep("com.h:index", "4.0.0", "compile"), dep("com.h:test-kit", "4.0.0", "test"))),
            "test-kit", repo(module("pom.xml", "com.h:test-kit", "4.0.0", dep("com.h:app", "1.0", "compile"))),
            "secret", repo(module("pom.xml", "com.h:secret", "1.0", dep("com.h:core", "4.0.0", "compile"))),
            "platform", repo(goModule("go:github.com/x/platform", new Dependency(
                    new Coordinate(Ecosystem.GO, "github.com/x/runtime"), "v0.0.0", "v0.0.0", "compile", false, "../runtime"))),
            "runtime", repo(goModule("go:github.com/x/runtime"))));

    @Test
    void edgesConnectConsumersToProducers() {
        assertThat(G.dependenciesOf("index", r -> true)).extracting(DependencyGraph.RepoEdge::toRepo)
                .containsExactlyInAnyOrder("util", "core");
        assertThat(G.dependentsOf("core", r -> true)).extracting(DependencyGraph.RepoEdge::fromRepo)
                .containsExactlyInAnyOrder("util", "index", "secret");
        assertThat(G.externalDependencies("index")).containsKey("maven:org.apache.lucene:lucene-core");
    }

    @Test
    void siblingCheckoutsAreEdges() {
        DependencyGraph.RepoEdge e = G.dependenciesOf("platform", r -> true).get(0);
        assertThat(e.toRepo()).isEqualTo("runtime");
        assertThat(e.sibling()).isTrue();
    }

    @Test
    void closureFollowsDepthAndDirection() {
        assertThat(G.closure("app", -1, false, false, r -> true))
                .containsOnlyKeys("index", "util", "core");
        assertThat(G.closure("app", 1, false, false, r -> true)).containsOnlyKeys("index");
        assertThat(G.closure("core", -1, true, false, r -> true)).containsKeys("util", "index", "app", "secret");
    }

    @Test
    void driftFindsOlderVersions() {
        List<DependencyGraph.Drift> drift = G.drift(r -> true);
        assertThat(drift).singleElement().satisfies(d -> {
            assertThat(d.fromRepo()).isEqualTo("index");
            assertThat(d.toRepo()).isEqualTo("core");
            assertThat(d.usedVersion()).isEqualTo("3.1.0");
            assertThat(d.currentVersion()).isEqualTo("4.0.0");
            assertThat(d.behind()).isTrue();
        });
    }

    @Test
    void layersIgnoreTestEdgesAndReportCycles() {
        DependencyGraph.Layers l = G.layers(r -> true, r -> true);
        assertThat(l.layers().get(0)).contains("core", "runtime");
        assertThat(l.layers().get(1)).contains("util", "secret", "platform");
        assertThat(l.layers().get(2)).contains("index");
        assertThat(l.layers().get(3)).contains("app");
        // app → test-kit is a test edge, so test-kit → app is no cycle
        assertThat(l.cycles()).isEmpty();
    }

    @Test
    void hiddenReposDisappear() {
        assertThat(G.dependentsOf("core", r -> !r.equals("secret"))).extracting(DependencyGraph.RepoEdge::fromRepo)
                .doesNotContain("secret");
        assertThat(G.layers(r -> !r.equals("secret"), r -> true).layers()).noneMatch(l -> l.contains("secret"));
        assertThat(G.usersOf("maven:com.h:core", r -> !r.equals("secret")))
                .extracting(DependencyGraph.RepoEdge::fromRepo).doesNotContain("secret");
    }

    @Test
    void versionComparison() {
        assertThat(G.drift(r -> true)).isNotEmpty();
        assertThat(compare("3.0.10", "3.0.9")).isPositive();
        assertThat(compare("v1.2.0", "1.10.0")).isNegative();
        assertThat(compare("2.0", "2.0.0")).isZero();
    }

    // ---- helpers ----

    private static int compare(String a, String b) {
        return DependencyGraph.compareVersions(a, b);
    }

    private static RepoManifests repo(ModuleManifest... modules) {
        return new RepoManifests(List.of(modules), List.of());
    }

    private static ModuleManifest module(String path, String ga, String version, Dependency... deps) {
        String[] p = ga.split(":");
        return new ModuleManifest(path, "maven", Coordinate.maven(p[0], p[1]), version, null, List.of(deps), List.of(), Map.of());
    }

    private static ModuleManifest goModule(String key, Dependency... deps) {
        return new ModuleManifest("go.mod", "go", Coordinate.parse(key), null, null, List.of(deps), List.of(), Map.of());
    }

    private static Dependency dep(String ga, String version, String scope) {
        String[] p = ga.split(":");
        return new Dependency(Coordinate.maven(p[0], p[1]), version, version, scope, false, null);
    }
}
