package com.codegraph.core.stats;

import java.util.*;
import java.util.function.Function;

/**
 * Rolls repos' stats up by a dimension, optionally broken down by another:
 * "lines of Java per family", "test files per repo", "code per language
 * across everything". Every rollup sums the same language × kind cells, so the
 * numbers always agree with each other.
 */
public final class StatsRollup {

    private StatsRollup() {}

    /** What can be grouped or broken down by. */
    public static final List<String> DIMENSIONS = List.of("repo", "family", "language", "kind", "visibility", "tag", "none");

    /** Facts about a repo that aren't in its stats: where it belongs. */
    public record RepoMeta(String family, String visibility, List<String> tags) {
        public static final RepoMeta NONE = new RepoMeta(null, null, List.of());
    }

    /**
     * A rollup request. Filters are ANDed; an empty set means no filter.
     * {@code metric} orders groups and breakdown entries (code by default).
     */
    public record Query(String groupBy, String breakdown, String metric,
                        Set<String> repos, Set<String> families, Set<String> languages,
                        Set<String> kinds, Set<String> visibility, Set<String> tags, int breakdownLimit) {
        public static Query of(String groupBy) {
            return new Query(groupBy, "none", "code", Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), 12);
        }

        public Query normalized() {
            return new Query(dim(groupBy, "family"), dim(breakdown, "none"), metric == null ? "code" : metric,
                    nz(repos), nz(families), nz(languages), nz(kinds), nz(visibility), nz(tags),
                    breakdownLimit <= 0 ? 12 : breakdownLimit);
        }

        private static String dim(String d, String dflt) {
            return d == null || d.isBlank() ? dflt : d;
        }

        private static Set<String> nz(Set<String> s) {
            return s == null ? Set.of() : s;
        }
    }

    /** One entry of a breakdown. */
    public record Part(String key, Counts counts) {}

    /** One group: its counts, how many repos are in it, and its breakdown. */
    public record Group(String key, Counts counts, int repos, List<Part> breakdown) {}

    /**
     * The result. {@code totals} counts each file once, even when a dimension
     * (tag) puts a repo in several groups; {@code repos} is how many repos matched.
     */
    public record Result(Query query, Counts totals, int repos, List<Group> groups) {}

    /** Rolls stats up. meta gives each repo's family, visibility and tags. */
    public static Result rollup(Collection<RepoStats> stats, Function<String, RepoMeta> meta, Query q) {
        Query query = q.normalized();
        if (!DIMENSIONS.contains(query.groupBy()) || !DIMENSIONS.contains(query.breakdown())) {
            throw new IllegalArgumentException("group by and breakdown must be one of " + DIMENSIONS);
        }
        Counts totals = new Counts();
        Map<String, Counts> groups = new HashMap<>();
        Map<String, Set<String>> groupRepos = new HashMap<>();
        Map<String, Map<String, Counts>> parts = new HashMap<>();
        Set<String> matched = new HashSet<>();

        for (RepoStats s : stats) {
            RepoMeta m = Optional.ofNullable(meta.apply(s.repoId())).orElse(RepoMeta.NONE);
            if (!pass(query.repos(), s.repoId()) || !pass(query.families(), m.family())
                    || !pass(query.visibility(), m.visibility())) continue;
            if (!query.tags().isEmpty() && (m.tags() == null || m.tags().stream().noneMatch(query.tags()::contains))) continue;
            boolean any = false;
            for (var byLang : s.cells().entrySet()) {
                String lang = byLang.getKey();
                if (!pass(query.languages(), lang)) continue;
                for (var byKind : byLang.getValue().entrySet()) {
                    String kind = byKind.getKey();
                    if (!pass(query.kinds(), kind)) continue;
                    Counts c = byKind.getValue();
                    any = true;
                    totals.add(c);
                    for (String g : keys(query.groupBy(), s.repoId(), m, lang, kind)) {
                        groups.computeIfAbsent(g, k -> new Counts()).add(c);
                        groupRepos.computeIfAbsent(g, k -> new HashSet<>()).add(s.repoId());
                        if (!"none".equals(query.breakdown())) {
                            Map<String, Counts> p = parts.computeIfAbsent(g, k -> new HashMap<>());
                            for (String b : keys(query.breakdown(), s.repoId(), m, lang, kind)) {
                                p.computeIfAbsent(b, k -> new Counts()).add(c);
                            }
                        }
                    }
                }
            }
            if (any) matched.add(s.repoId());
        }

        String metric = query.metric();
        Comparator<Map.Entry<String, Counts>> byMetric = Comparator
                .comparingLong((Map.Entry<String, Counts> e) -> e.getValue().metric(metric)).reversed()
                .thenComparing(Map.Entry::getKey);
        List<Group> out = groups.entrySet().stream().sorted(byMetric)
                .map(e -> new Group(e.getKey(), e.getValue(), groupRepos.get(e.getKey()).size(),
                        limit(parts.getOrDefault(e.getKey(), Map.of()), byMetric, query.breakdownLimit())))
                .toList();
        return new Result(query, totals, matched.size(), out);
    }

    /** The breakdown's largest entries, the rest summed as "(other)". */
    private static List<Part> limit(Map<String, Counts> parts, Comparator<Map.Entry<String, Counts>> order, int n) {
        List<Map.Entry<String, Counts>> sorted = parts.entrySet().stream().sorted(order).toList();
        List<Part> out = new ArrayList<>();
        Counts rest = null;
        for (int i = 0; i < sorted.size(); i++) {
            if (i < n) {
                out.add(new Part(sorted.get(i).getKey(), sorted.get(i).getValue()));
            } else {
                if (rest == null) rest = new Counts();
                rest.add(sorted.get(i).getValue());
            }
        }
        if (rest != null) out.add(new Part("(other)", rest));
        return out;
    }

    private static boolean pass(Set<String> filter, String value) {
        return filter.isEmpty() || (value != null && filter.contains(value));
    }

    private static List<String> keys(String dim, String repo, RepoMeta m, String lang, String kind) {
        return switch (dim) {
            case "repo" -> List.of(repo);
            case "family" -> List.of(m.family() == null ? "(no family)" : m.family());
            case "language" -> List.of(lang);
            case "kind" -> List.of(kind);
            case "visibility" -> List.of(m.visibility() == null ? "(unknown)" : m.visibility());
            case "tag" -> m.tags() == null || m.tags().isEmpty() ? List.of("(no tags)") : m.tags();
            default -> List.of("all");
        };
    }
}
