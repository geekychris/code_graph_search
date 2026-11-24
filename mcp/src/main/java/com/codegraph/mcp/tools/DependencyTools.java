package com.codegraph.mcp.tools;

import com.codegraph.deps.graph.DependencyGraph;
import com.codegraph.mcp.McpTool;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Tools over the portfolio dependency graph: which repos depend on which, through
 * which artifacts and versions, in what layer order, and where versions have drifted.
 * Results only include repos the caller may see.
 */
public class DependencyTools {

    private DependencyTools() {}

    /** Shared state for the tools: the current graph and the caller's repo visibility. */
    public record Context(Supplier<DependencyGraph> graph, Predicate<String> visible) {}

    private static Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", props);
        s.put("required", required);
        return s;
    }

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : v.toString();
    }

    private static int num(Map<String, Object> args, String key, int dflt) {
        Object v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        try { return v == null ? dflt : Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return dflt; }
    }

    private static boolean bool(Map<String, Object> args, String key, boolean dflt) {
        Object v = args.get(key);
        return v == null ? dflt : Boolean.parseBoolean(v.toString());
    }

    private static String unknownRepo(String repo) {
        return "No dependency information for repository '" + repo + "'. Check the id with list_repos.";
    }

    private static String edgeLine(DependencyGraph.RepoEdge e, boolean showTarget) {
        String other = showTarget ? e.toRepo() : e.fromRepo();
        return "- **" + other + "** via `" + e.coordinate() + "`"
                + (e.usedVersion() != null ? " " + e.usedVersion() : "")
                + (e.sibling() ? " (sibling checkout)" : "")
                + (!"compile".equals(e.scope()) ? " [" + e.scope() + "]" : "")
                + " — declared in " + e.manifestPath();
    }

    // -------------------------------------------------------------------------
    // repo_dependencies
    // -------------------------------------------------------------------------

    public static class RepoDependencies implements McpTool {
        private final Context ctx;
        public RepoDependencies(Context ctx) { this.ctx = ctx; }

        @Override public String getName() { return "repo_dependencies"; }

        @Override
        public String getDescription() {
            return "What a repository depends on across the whole portfolio: the other repos whose artifacts it "
                    + "uses (with artifact, version, scope and the manifest that declares it), optionally transitively, "
                    + "plus its external (third-party) dependencies. Covers Maven, Gradle, Go (including "
                    + "replace => ../sibling), npm, Cargo and Python. Use it to understand what infrastructure a repo "
                    + "builds on before changing or reusing it.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("repo", prop("string", "Repository id"));
            p.put("depth", prop("integer", "Hops to follow (1 = direct only, -1 = all). Default 1"));
            p.put("include_external", prop("boolean", "Also list third-party dependencies. Default false"));
            p.put("include_test", prop("boolean", "Include test/dev-scope edges. Default true"));
            return schema(p, List.of("repo"));
        }

        @Override
        public Object execute(Map<String, Object> args) {
            String repo = str(args, "repo");
            DependencyGraph g = ctx.graph().get();
            if (repo == null || !ctx.visible().test(repo) || !g.repos().contains(repo)) return unknownRepo(repo);
            int depth = num(args, "depth", 1);
            boolean includeTest = bool(args, "include_test", true);
            StringBuilder sb = new StringBuilder("## Dependencies of ").append(repo).append("\n\n");
            List<String> produced = g.producedBy(repo);
            if (!produced.isEmpty()) sb.append("Produces: ").append(produced.stream().map(c -> "`" + c + "`").collect(Collectors.joining(", "))).append("\n\n");

            List<DependencyGraph.RepoEdge> direct = g.dependenciesOf(repo, ctx.visible()).stream()
                    .filter(e -> includeTest || !e.isTest()).toList();
            sb.append("### Direct (").append(direct.size()).append(")\n");
            if (direct.isEmpty()) sb.append("None: this repo uses no artifacts from other repos here.\n");
            direct.forEach(e -> sb.append(edgeLine(e, true)).append("\n"));

            if (depth != 1) {
                Map<String, Integer> closure = g.closure(repo, depth, false, includeTest, ctx.visible());
                List<String> transitive = closure.entrySet().stream().filter(e -> e.getValue() > 1)
                        .map(e -> e.getKey() + " (" + e.getValue() + " hops)").toList();
                sb.append("\n### Transitive (").append(transitive.size()).append(")\n");
                transitive.forEach(t -> sb.append("- ").append(t).append("\n"));
            }
            if (bool(args, "include_external", false)) {
                var ext = g.externalDependencies(repo);
                sb.append("\n### External (").append(ext.size()).append(")\n");
                ext.forEach((c, v) -> sb.append("- `").append(c).append("` ").append(String.join(", ", v)).append("\n"));
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // repo_dependents
    // -------------------------------------------------------------------------

    public static class RepoDependents implements McpTool {
        private final Context ctx;
        public RepoDependents(Context ctx) { this.ctx = ctx; }

        @Override public String getName() { return "repo_dependents"; }

        @Override
        public String getDescription() {
            return "Which repositories depend on a given repository (directly, or transitively with depth), and "
                    + "through which artifact and version. Use it for impact analysis before changing a library, "
                    + "and to judge how battle-tested a piece of shared infrastructure is.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("repo", prop("string", "Repository id"));
            p.put("depth", prop("integer", "Hops to follow (1 = direct only, -1 = all). Default 1"));
            return schema(p, List.of("repo"));
        }

        @Override
        public Object execute(Map<String, Object> args) {
            String repo = str(args, "repo");
            DependencyGraph g = ctx.graph().get();
            if (repo == null || !ctx.visible().test(repo) || !g.repos().contains(repo)) return unknownRepo(repo);
            int depth = num(args, "depth", 1);
            List<DependencyGraph.RepoEdge> direct = g.dependentsOf(repo, ctx.visible());
            StringBuilder sb = new StringBuilder("## Repositories that depend on ").append(repo).append("\n\n");
            sb.append("### Direct (").append(direct.size()).append(")\n");
            if (direct.isEmpty()) sb.append("None.\n");
            direct.forEach(e -> sb.append(edgeLine(e, false)).append("\n"));
            if (depth != 1) {
                Map<String, Integer> closure = g.closure(repo, depth, true, true, ctx.visible());
                sb.append("\n### All dependents (").append(closure.size()).append(")\n");
                closure.forEach((r, d) -> sb.append("- ").append(r).append(" (").append(d).append(" hops)\n"));
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // dependency_layers
    // -------------------------------------------------------------------------

    public static class DependencyLayers implements McpTool {
        private final Context ctx;
        public DependencyLayers(Context ctx) { this.ctx = ctx; }

        @Override public String getName() { return "dependency_layers"; }

        @Override
        public String getDescription() {
            return "Build/release order of repositories: layer 0 depends on no other repo, each later layer only "
                    + "on earlier ones (test-scope edges ignored). Also reports dependency cycles. Filter with a "
                    + "name prefix, e.g. 'hitorro' for the HiTorro family. Use it to plan coordinated upgrades "
                    + "(release libraries in layer order).";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("prefix", prop("string", "Only repos whose id starts with this (optional)"));
            return schema(p, List.of());
        }

        @Override
        public Object execute(Map<String, Object> args) {
            String prefix = Optional.ofNullable(str(args, "prefix")).orElse("");
            DependencyGraph.Layers l = ctx.graph().get().layers(ctx.visible(), r -> r.startsWith(prefix));
            if (l.layers().isEmpty()) return "No dependencies between repositories" + (prefix.isEmpty() ? "" : " starting with '" + prefix + "'") + ".";
            StringBuilder sb = new StringBuilder("## Dependency layers").append(prefix.isEmpty() ? "" : " (" + prefix + "*)").append("\n\n");
            for (int i = 0; i < l.layers().size(); i++) {
                sb.append("- **Layer ").append(i).append(":** ").append(String.join(", ", l.layers().get(i))).append("\n");
            }
            sb.append("\n").append(l.cycles().isEmpty() ? "No cycles." : "Cycles: " + l.cycles()).append("\n");
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // drift_report
    // -------------------------------------------------------------------------

    public static class DriftReport implements McpTool {
        private final Context ctx;
        public DriftReport(Context ctx) { this.ctx = ctx; }

        @Override public String getName() { return "drift_report"; }

        @Override
        public String getDescription() {
            return "Repositories using a different (usually older) version of an internal artifact than the "
                    + "producing repo currently declares, e.g. an app still on hitorro-core 3.1.0 after 4.0.0. "
                    + "Optionally limited to one producing or consuming repo.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("repo", prop("string", "Only drift involving this repo, as producer or consumer (optional)"));
            return schema(p, List.of());
        }

        @Override
        public Object execute(Map<String, Object> args) {
            String repo = str(args, "repo");
            List<DependencyGraph.Drift> drift = ctx.graph().get().drift(ctx.visible()).stream()
                    .filter(d -> repo == null || d.fromRepo().equals(repo) || d.toRepo().equals(repo)).toList();
            if (drift.isEmpty()) return "No version drift" + (repo == null ? "" : " involving " + repo) + ".";
            StringBuilder sb = new StringBuilder("## Version drift (").append(drift.size()).append(")\n\n");
            for (DependencyGraph.Drift d : drift) {
                sb.append("- **").append(d.fromRepo()).append("** uses `").append(d.coordinate()).append("` ")
                        .append(d.usedVersion()).append(d.behind() ? " — behind " : " — differs from ")
                        .append(d.toRepo()).append("'s ").append(d.currentVersion()).append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // find_artifact_users
    // -------------------------------------------------------------------------

    public static class FindArtifactUsers implements McpTool {
        private final Context ctx;
        public FindArtifactUsers(Context ctx) { this.ctx = ctx; }

        @Override public String getName() { return "find_artifact_users"; }

        @Override
        public String getDescription() {
            return "Who produces and who uses an artifact, internal or third-party, with versions. Give a full key "
                    + "(maven:group:artifact, go:module/path, npm:name, cargo:name, pypi:name) or a substring such as "
                    + "'rocksdb' or 'jackson-databind' to search. Use it to find existing users of a library before "
                    + "adopting it, or every repo affected by a vulnerable version.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("artifact", prop("string", "Artifact key or substring"));
            p.put("limit", prop("integer", "Max artifacts to report when searching by substring. Default 10"));
            return schema(p, List.of("artifact"));
        }

        @Override
        public Object execute(Map<String, Object> args) {
            String q = Optional.ofNullable(str(args, "artifact")).orElse("").trim();
            if (q.isEmpty()) return "Give an artifact key or substring.";
            DependencyGraph g = ctx.graph().get();
            int limit = num(args, "limit", 10);
            // Collect matching keys from what visible repos declare or produce
            SortedSet<String> keys = new TreeSet<>();
            for (String r : g.repos()) {
                if (!ctx.visible().test(r)) continue;
                g.manifests(r).modules().forEach(m -> {
                    if (m.produces() != null && m.produces().key().contains(q)) keys.add(m.produces().key());
                    m.dependencies().forEach(d -> { if (d.coordinate().key().contains(q)) keys.add(d.coordinate().key()); });
                });
            }
            if (keys.contains(q)) keys.retainAll(Set.of(q));
            if (keys.isEmpty()) return "No visible repository declares or produces an artifact matching '" + q + "'.";
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (String key : keys) {
                if (shown++ >= limit) {
                    sb.append("\n… ").append(keys.size() - limit).append(" more matching artifacts; narrow the query.\n");
                    break;
                }
                sb.append("## `").append(key).append("`\n");
                var producers = g.producersOf(key, ctx.visible());
                if (!producers.isEmpty()) {
                    sb.append("Produced by: ").append(producers.stream()
                            .map(p -> p.repo() + (p.version() != null ? " " + p.version() : "")).collect(Collectors.joining(", "))).append("\n");
                }
                var users = g.usersOf(key, ctx.visible());
                Map<String, SortedSet<String>> byRepo = new TreeMap<>();
                users.forEach(u -> byRepo.computeIfAbsent(u.fromRepo(), k -> new TreeSet<>())
                        .add(Optional.ofNullable(u.usedVersion()).orElse("?") + ("compile".equals(u.scope()) ? "" : " [" + u.scope() + "]")));
                sb.append("Used by ").append(byRepo.size()).append(" repo(s):\n");
                byRepo.forEach((r, v) -> sb.append("- ").append(r).append(": ").append(String.join(", ", v)).append("\n"));
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // portfolio_dependency_stats
    // -------------------------------------------------------------------------

    public static class DependencyStats implements McpTool {
        private final Context ctx;
        public DependencyStats(Context ctx) { this.ctx = ctx; }

        @Override public String getName() { return "portfolio_dependency_stats"; }

        @Override
        public String getDescription() {
            return "Portfolio-wide dependency overview: the most depended-on internal repos (critical shared "
                    + "infrastructure), internal libraries nobody uses, cycles, and the most widely used "
                    + "third-party artifacts with how many versions are in use.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("limit", prop("integer", "Rows per section. Default 15"));
            return schema(p, List.of());
        }

        @Override
        public Object execute(Map<String, Object> args) {
            int limit = num(args, "limit", 15);
            DependencyGraph g = ctx.graph().get();
            Predicate<String> vis = ctx.visible();
            Map<String, Long> fanIn = g.edges(vis).stream().filter(e -> !e.isTest())
                    .map(e -> e.fromRepo() + "|" + e.toRepo()).distinct()
                    .collect(Collectors.groupingBy(k -> k.substring(k.indexOf('|') + 1), Collectors.counting()));
            StringBuilder sb = new StringBuilder("## Portfolio dependencies\n\n");
            long repos = g.repos().stream().filter(vis).count();
            sb.append(repos).append(" repos with build manifests, ")
                    .append(g.edges(vis).size()).append(" internal artifact edges.\n\n");
            sb.append("### Most depended-on repos\n");
            fanIn.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(limit)
                    .forEach(e -> sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append(" dependent repos\n"));
            List<String> unused = g.unusedProducers(vis);
            sb.append("\n### Producing repos nobody here depends on (").append(unused.size()).append(")\n");
            sb.append(unused.stream().limit(limit * 3L).collect(Collectors.joining(", "))).append("\n");
            var cycles = g.layers(vis, r -> true).cycles();
            sb.append("\n### Cycles\n").append(cycles.isEmpty() ? "None" : cycles.toString()).append("\n");
            sb.append("\n### Most used third-party artifacts\n");
            g.topExternal(limit, vis).forEach(x -> sb.append("- `").append(x.coordinate()).append("`: ")
                    .append(x.repoCount()).append(" repos, ").append(x.versions().size()).append(" versions\n"));
            return sb.toString();
        }
    }
}
