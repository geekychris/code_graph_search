package com.codegraph.mcp.tools;

import com.codegraph.core.stats.Counts;
import com.codegraph.core.stats.RepoStats;
import com.codegraph.core.stats.StatsRollup;
import com.codegraph.core.stats.StatsService;
import com.codegraph.mcp.McpTool;

import java.util.*;
import java.util.function.Predicate;

/** Project stats for agents: one repo's size and mix, or rollups across the visible repos. */
public final class StatsTools {

    private StatsTools() {}

    public static class ProjectStats implements McpTool {
        private final StatsService stats;
        private final Predicate<String> visible;

        public ProjectStats(StatsService stats, Predicate<String> visible) {
            this.stats = stats;
            this.visible = visible;
        }

        @Override public String getName() { return "project_stats"; }

        @Override
        public String getDescription() {
            return "Size and make-up of code: files and lines (code, comment, blank) by language and by kind "
                    + "(source, test, config, build, ci, docs, data, generated, asset, other), counted at each repo's indexed commit. "
                    + "With repo: that repo's totals, languages, kinds, top directories and largest files. "
                    + "Without: a rollup over every repo you can see, grouped by group_by (repo, family, language, kind, "
                    + "visibility, tag, none) and optionally broken down by another dimension, with filters. "
                    + "Examples: test vs source lines per family; Java code per repo; docs files across everything.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("repo", prop("string", "One repository id: its own stats. Omit for a rollup."));
            props.put("group_by", prop("string", "Rollup dimension: repo, family, language, kind, visibility, tag or none (default family)"));
            props.put("breakdown", prop("string", "Second dimension inside each group (default none)"));
            props.put("metric", prop("string", "Order by: code (default), lines, files, comment, blank or bytes"));
            props.put("repos", prop("string", "Filter: comma-separated repo ids"));
            props.put("families", prop("string", "Filter: comma-separated families"));
            props.put("languages", prop("string", "Filter: comma-separated languages, e.g. Java,Go"));
            props.put("kinds", prop("string", "Filter: comma-separated kinds, e.g. source,test"));
            props.put("tags", prop("string", "Filter: comma-separated tags"));
            props.put("limit", prop("integer", "Groups to return (default 25)"));
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "object");
            s.put("properties", props);
            s.put("required", List.of());
            return s;
        }

        @Override
        public Object execute(Map<String, Object> args) {
            String repo = str(args, "repo");
            if (repo != null) {
                if (!visible.test(repo)) return "Unknown repository '" + repo + "'. Check the id with list_repos.";
                return stats.repoStats(repo).map(ProjectStats::describe)
                        .orElse("No stats yet for '" + repo + "': they're counted when the repo is next indexed.");
            }
            StatsRollup.Query q = new StatsRollup.Query(str(args, "group_by"), str(args, "breakdown"), str(args, "metric"),
                    csv(args, "repos"), csv(args, "families"), csv(args, "languages"), csv(args, "kinds"),
                    Set.of(), csv(args, "tags"), 8);
            StatsRollup.Result r;
            try {
                r = stats.rollup(q, visible);
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
            int limit = 25;
            Object l = args.get("limit");
            if (l instanceof Number n) limit = n.intValue();
            else if (l != null) try { limit = Integer.parseInt(l.toString()); } catch (NumberFormatException ignored) {}
            StringBuilder sb = new StringBuilder();
            sb.append("# Stats by ").append(r.query().groupBy());
            if (!"none".equals(r.query().breakdown())) sb.append(", broken down by ").append(r.query().breakdown());
            sb.append("\n").append(r.repos()).append(" repos · ").append(line(r.totals())).append("\n\n");
            sb.append("| ").append(r.query().groupBy()).append(" | repos | files | code | comment | blank | lines |")
                    .append("none".equals(r.query().breakdown()) ? "" : " " + r.query().breakdown() + " (" + r.query().metric() + ") |")
                    .append("\n|---|---:|---:|---:|---:|---:|---:|").append("none".equals(r.query().breakdown()) ? "" : "---|").append("\n");
            r.groups().stream().limit(Math.max(1, limit)).forEach(g -> {
                Counts c = g.counts();
                sb.append("| ").append(g.key()).append(" | ").append(g.repos()).append(" | ").append(c.files).append(" | ")
                        .append(c.code).append(" | ").append(c.comment).append(" | ").append(c.blank).append(" | ").append(c.lines).append(" |");
                if (!"none".equals(r.query().breakdown())) {
                    sb.append(" ");
                    g.breakdown().forEach(p -> sb.append(p.key()).append(" ").append(p.counts().metric(r.query().metric())).append("; "));
                    sb.append("|");
                }
                sb.append("\n");
            });
            if (r.groups().size() > limit) sb.append("\n(").append(r.groups().size() - limit).append(" more groups)\n");
            return sb.toString();
        }

        static String describe(RepoStats s) {
            StringBuilder sb = new StringBuilder("# ").append(s.repoId()).append(" at ")
                    .append(s.commit() == null ? "?" : s.commit().substring(0, Math.min(8, s.commit().length()))).append("\n");
            sb.append(line(s.totals())).append("; ").append(s.excluded()).append(" vendored or build-output files not counted\n\n");
            sb.append("## By kind\n");
            s.byKind().entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().lines, a.getValue().lines))
                    .forEach(e -> sb.append("- ").append(e.getKey()).append(": ").append(line(e.getValue())).append("\n"));
            sb.append("\n## By language\n");
            s.byLanguage().entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().code, a.getValue().code))
                    .limit(20).forEach(e -> sb.append("- ").append(e.getKey()).append(": ").append(line(e.getValue())).append("\n"));
            if (s.byDir() != null && !s.byDir().isEmpty()) {
                sb.append("\n## Top-level directories\n");
                s.byDir().entrySet().stream().limit(12)
                        .forEach(e -> sb.append("- ").append(e.getKey()).append(": ").append(line(e.getValue())).append("\n"));
            }
            if (s.largest() != null && !s.largest().isEmpty()) {
                sb.append("\n## Largest files\n");
                s.largest().stream().limit(10).forEach(f -> sb.append("- ").append(f.path()).append(" (").append(f.language())
                        .append(", ").append(f.kind()).append("): ").append(f.lines()).append(" lines\n"));
            }
            return sb.toString();
        }

        private static String line(Counts c) {
            return c.files + " files, " + c.code + " code / " + c.comment + " comment / " + c.blank + " blank lines";
        }

        private static Map<String, Object> prop(String type, String description) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", type);
            p.put("description", description);
            return p;
        }

        private static String str(Map<String, Object> args, String key) {
            Object v = args.get(key);
            return v == null || v.toString().isBlank() ? null : v.toString();
        }

        private static Set<String> csv(Map<String, Object> args, String key) {
            String v = str(args, key);
            if (v == null) return Set.of();
            Set<String> out = new LinkedHashSet<>();
            for (String p : v.split(",")) if (!p.isBlank()) out.add(p.trim());
            return out;
        }
    }
}
