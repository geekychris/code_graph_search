package com.codegraph.tests;

import com.codegraph.deps.graph.DependencyGraph;
import com.codegraph.deps.model.*;
import com.codegraph.mcp.tools.DependencyTools;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** The dependency MCP tools, and that they never mention repos the caller can't see. */
class DependencyToolsTest {

    private static final DependencyGraph G = DependencyGraph.build(Map.of(
            "core", repo("core", "2.0"),
            "util", repo("util", "2.0", dep("core", "2.0")),
            "secret-app", repo("secret-app", "1.0", dep("util", "2.0"), dep("core", "1.5")),
            "public-app", repo("public-app", "1.0", dep("util", "2.0"))));

    private static final Predicate<String> PUBLIC = r -> !r.equals("secret-app");

    private static String run(Object tool, Map<String, Object> args) throws Exception {
        return (String) ((com.codegraph.mcp.McpTool) tool).execute(args);
    }

    private static DependencyTools.Context ctx(Predicate<String> visible) {
        return new DependencyTools.Context(() -> G, visible);
    }

    @Test
    void dependentsHideInvisibleRepos() throws Exception {
        String all = run(new DependencyTools.RepoDependents(ctx(r -> true)), Map.of("repo", "util"));
        assertThat(all).contains("secret-app", "public-app");
        String pub = run(new DependencyTools.RepoDependents(ctx(PUBLIC)), Map.of("repo", "util"));
        assertThat(pub).contains("public-app").doesNotContain("secret-app");
    }

    @Test
    void invisibleRepoLooksUnknown() throws Exception {
        String out = run(new DependencyTools.RepoDependencies(ctx(PUBLIC)), Map.of("repo", "secret-app"));
        assertThat(out).startsWith("No dependency information");
    }

    @Test
    void transitiveDependenciesAndLayers() throws Exception {
        String deps = run(new DependencyTools.RepoDependencies(ctx(r -> true)), Map.of("repo", "public-app", "depth", -1));
        assertThat(deps).contains("util").contains("core (2 hops)");
        String layers = run(new DependencyTools.DependencyLayers(ctx(r -> true)), Map.of());
        assertThat(layers).contains("Layer 0:** core").contains("Layer 1:** util").contains("No cycles");
    }

    @Test
    void driftAndArtifactUsersRespectVisibility() throws Exception {
        assertThat(run(new DependencyTools.DriftReport(ctx(r -> true)), Map.of())).contains("secret-app", "1.5");
        assertThat(run(new DependencyTools.DriftReport(ctx(PUBLIC)), Map.of())).startsWith("No version drift");
        String users = run(new DependencyTools.FindArtifactUsers(ctx(PUBLIC)), Map.of("artifact", "maven:com.h:core"));
        assertThat(users).contains("util").doesNotContain("secret-app");
    }

    @Test
    void statsSummarise() throws Exception {
        String stats = run(new DependencyTools.DependencyStats(ctx(r -> true)), Map.of());
        assertThat(stats).contains("util: 2 dependent repos").contains("Cycles\nNone");
    }

    private static RepoManifests repo(String name, String version, Dependency... deps) {
        return new RepoManifests(List.of(new ModuleManifest("pom.xml", "maven", Coordinate.maven("com.h", name),
                version, null, List.of(deps), List.of(), Map.of())), List.of());
    }

    private static Dependency dep(String name, String version) {
        return new Dependency(Coordinate.maven("com.h", name), version, version, "compile", false, null);
    }
}
