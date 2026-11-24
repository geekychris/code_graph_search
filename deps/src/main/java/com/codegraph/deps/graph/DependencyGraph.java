package com.codegraph.deps.graph;

import com.codegraph.deps.model.Coordinate;
import com.codegraph.deps.model.Dependency;
import com.codegraph.deps.model.ModuleManifest;
import com.codegraph.deps.model.RepoManifests;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Repository-to-repository dependencies across a whole portfolio.
 *
 * <p>Built from every repo's manifests: a dependency on an artifact that another repo
 * produces becomes an edge between the two repos, and so does a sibling-checkout
 * reference such as Go's {@code replace X => ../other-repo}. Everything else is an
 * external dependency. Immutable; rebuild it when manifests change (it takes
 * milliseconds for hundreds of repos).
 *
 * <p>Queries take a {@code visible} predicate on repo ids so callers can hide repos the
 * current user may not see; hidden repos are left out entirely, including as the
 * producer of an artifact.
 */
public final class DependencyGraph {

    /** A repo module that publishes an artifact. */
    public record Producer(String repo, String manifestPath, String version) {}

    /** One repo using an artifact (or sibling checkout) of another. */
    public record RepoEdge(String fromRepo, String toRepo, String coordinate, String usedVersion,
                           String scope, boolean sibling, String manifestPath) {
        public boolean isTest() { return "test".equals(scope) || "dev".equals(scope); }
    }

    /** A consumer using an older (or different) version than the producer's current one. */
    public record Drift(String fromRepo, String toRepo, String coordinate, String usedVersion,
                        String currentVersion, boolean behind) {}

    /** Layer order: layer 0 depends on no other repo here. Cycles are reported separately. */
    public record Layers(List<List<String>> layers, List<List<String>> cycles) {}

    /** How widely an external artifact is used. */
    public record ExternalUsage(String coordinate, int repoCount, SortedSet<String> versions, SortedSet<String> repos) {}

    private final Map<String, RepoManifests> manifests;
    private final Map<String, List<Producer>> producers;
    private final List<RepoEdge> edges;
    private final Map<String, List<RepoEdge>> out = new HashMap<>();
    private final Map<String, List<RepoEdge>> in = new HashMap<>();

    private DependencyGraph(Map<String, RepoManifests> manifests, Map<String, List<Producer>> producers,
                            List<RepoEdge> edges) {
        this.manifests = manifests;
        this.producers = producers;
        this.edges = edges;
        for (RepoEdge e : edges) {
            out.computeIfAbsent(e.fromRepo(), k -> new ArrayList<>()).add(e);
            in.computeIfAbsent(e.toRepo(), k -> new ArrayList<>()).add(e);
        }
    }

    public static DependencyGraph build(Map<String, RepoManifests> byRepo) {
        Map<String, RepoManifests> manifests = new TreeMap<>(byRepo);
        Map<String, List<Producer>> producers = new HashMap<>();
        for (var e : manifests.entrySet()) {
            for (ModuleManifest m : e.getValue().modules()) {
                if (m.produces() != null) {
                    producers.computeIfAbsent(m.produces().key(), k -> new ArrayList<>())
                            .add(new Producer(e.getKey(), m.manifestPath(), m.version()));
                }
            }
        }
        Map<String, String> repoByLowerName = new HashMap<>();
        for (String r : manifests.keySet()) repoByLowerName.put(r.toLowerCase(Locale.ROOT), r);

        // One edge per (from, to, coordinate): the first declaration wins, non-test preferred
        Map<String, RepoEdge> unique = new LinkedHashMap<>();
        for (var e : manifests.entrySet()) {
            String from = e.getKey();
            for (ModuleManifest m : e.getValue().modules()) {
                for (Dependency d : m.dependencies()) {
                    String to = null;
                    boolean sibling = false;
                    if (d.localPath() != null && d.localPath().startsWith("../")) {
                        String first = d.localPath().substring(3).split("/")[0];
                        to = repoByLowerName.get(first.toLowerCase(Locale.ROOT));
                        sibling = to != null;
                    }
                    if (to == null) {
                        List<Producer> ps = producers.get(d.coordinate().key());
                        if (ps != null) {
                            to = ps.stream().map(Producer::repo).filter(r -> !r.equals(from)).findFirst().orElse(null);
                        }
                    }
                    if (to == null || to.equals(from)) continue;
                    RepoEdge edge = new RepoEdge(from, to, d.coordinate().key(), d.resolvedVersion(),
                            d.scope(), sibling, m.manifestPath());
                    String key = from + "|" + to + "|" + edge.coordinate();
                    RepoEdge prev = unique.get(key);
                    if (prev == null || (prev.isTest() && !edge.isTest())) unique.put(key, edge);
                }
            }
        }
        return new DependencyGraph(manifests, producers, List.copyOf(unique.values()));
    }

    // -------------------------------------------------------------------------
    // Basic lookups
    // -------------------------------------------------------------------------

    public Set<String> repos() { return manifests.keySet(); }

    public RepoManifests manifests(String repo) { return manifests.getOrDefault(repo, RepoManifests.empty()); }

    public List<RepoEdge> edges(Predicate<String> visible) {
        return edges.stream().filter(e -> visible.test(e.fromRepo()) && visible.test(e.toRepo())).toList();
    }

    /** Producers of an artifact key such as {@code maven:com.hitorro:hitorro-core}. */
    public List<Producer> producersOf(String coordinateKey, Predicate<String> visible) {
        return producers.getOrDefault(coordinateKey, List.of()).stream().filter(p -> visible.test(p.repo())).toList();
    }

    /** Artifact keys produced by a repo. */
    public List<String> producedBy(String repo) {
        return manifests(repo).modules().stream().map(ModuleManifest::produces).filter(Objects::nonNull)
                .map(Coordinate::key).distinct().sorted().toList();
    }

    /** Direct internal dependencies of a repo. */
    public List<RepoEdge> dependenciesOf(String repo, Predicate<String> visible) {
        return out.getOrDefault(repo, List.of()).stream().filter(e -> visible.test(e.toRepo())).toList();
    }

    /** Direct internal dependents of a repo. */
    public List<RepoEdge> dependentsOf(String repo, Predicate<String> visible) {
        return in.getOrDefault(repo, List.of()).stream().filter(e -> visible.test(e.fromRepo())).toList();
    }

    /**
     * Repos reachable from {@code repo} within {@code depth} hops (-1 = unlimited), walking
     * dependencies ({@code dependents=false}) or dependents. Maps repo → distance.
     */
    public Map<String, Integer> closure(String repo, int depth, boolean dependents, boolean includeTest,
                                        Predicate<String> visible) {
        Map<String, Integer> dist = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>(List.of(repo));
        dist.put(repo, 0);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            int d = dist.get(cur);
            if (depth >= 0 && d >= depth) continue;
            List<RepoEdge> next = dependents ? dependentsOf(cur, visible) : dependenciesOf(cur, visible);
            for (RepoEdge e : next) {
                if (!includeTest && e.isTest()) continue;
                String n = dependents ? e.fromRepo() : e.toRepo();
                if (!dist.containsKey(n)) {
                    dist.put(n, d + 1);
                    queue.add(n);
                }
            }
        }
        dist.remove(repo);
        return dist;
    }

    /** External (non-portfolio) dependencies of a repo: coordinate → versions used. */
    public SortedMap<String, SortedSet<String>> externalDependencies(String repo) {
        SortedMap<String, SortedSet<String>> ext = new TreeMap<>();
        for (ModuleManifest m : manifests(repo).modules()) {
            for (Dependency d : m.dependencies()) {
                if (producers.containsKey(d.coordinate().key())) continue;
                if (d.localPath() != null) continue;
                ext.computeIfAbsent(d.coordinate().key(), k -> new TreeSet<>())
                        .add(Optional.ofNullable(d.resolvedVersion()).orElse(Optional.ofNullable(d.declaredVersion()).orElse("?")));
            }
        }
        return ext;
    }

    // -------------------------------------------------------------------------
    // Analysis
    // -------------------------------------------------------------------------

    /** Consumers whose resolved version differs from the producer's current version. */
    public List<Drift> drift(Predicate<String> visible) {
        List<Drift> result = new ArrayList<>();
        for (RepoEdge e : edges(visible)) {
            if (e.sibling() || e.usedVersion() == null) continue;
            String current = producers.getOrDefault(e.coordinate(), List.of()).stream()
                    .filter(p -> p.repo().equals(e.toRepo())).map(Producer::version)
                    .filter(Objects::nonNull).findFirst().orElse(null);
            if (current == null || current.equals(e.usedVersion())) continue;
            result.add(new Drift(e.fromRepo(), e.toRepo(), e.coordinate(), e.usedVersion(), current,
                    compareVersions(e.usedVersion(), current) < 0));
        }
        result.sort(Comparator.comparing(Drift::toRepo).thenComparing(Drift::fromRepo));
        return result;
    }

    /**
     * Topological layers of the non-test repo graph: layer 0 has no internal
     * dependencies, layer n depends only on layers below n. Repos in a cycle share a
     * layer and are listed under {@code cycles}.
     */
    public Layers layers(Predicate<String> visible, Predicate<String> include) {
        Set<String> nodes = new TreeSet<>();
        Map<String, Set<String>> adj = new HashMap<>();
        for (RepoEdge e : edges(visible)) {
            if (e.isTest() || !include.test(e.fromRepo()) || !include.test(e.toRepo())) continue;
            nodes.add(e.fromRepo());
            nodes.add(e.toRepo());
            adj.computeIfAbsent(e.fromRepo(), k -> new TreeSet<>()).add(e.toRepo());
        }
        List<List<String>> sccs = tarjan(nodes, adj);
        Map<String, Integer> comp = new HashMap<>();
        for (int i = 0; i < sccs.size(); i++) for (String n : sccs.get(i)) comp.put(n, i);

        // Tarjan emits components in reverse topological order: dependencies first
        int[] level = new int[sccs.size()];
        for (int i = 0; i < sccs.size(); i++) {
            int lvl = 0;
            for (String n : sccs.get(i)) {
                for (String dep : adj.getOrDefault(n, Set.of())) {
                    int c = comp.get(dep);
                    if (c != i) lvl = Math.max(lvl, level[c] + 1);
                }
            }
            level[i] = lvl;
        }
        TreeMap<Integer, List<String>> byLevel = new TreeMap<>();
        List<List<String>> cycles = new ArrayList<>();
        for (int i = 0; i < sccs.size(); i++) {
            byLevel.computeIfAbsent(level[i], k -> new ArrayList<>()).addAll(sccs.get(i));
            if (sccs.get(i).size() > 1) cycles.add(sccs.get(i).stream().sorted().toList());
        }
        List<List<String>> layers = byLevel.values().stream().map(l -> l.stream().sorted().toList()).toList();
        return new Layers(layers, cycles);
    }

    /** Repos producing artifacts that no other (visible) repo depends on. */
    public List<String> unusedProducers(Predicate<String> visible) {
        Set<String> used = edges(visible).stream().map(RepoEdge::toRepo).collect(Collectors.toSet());
        return producers.values().stream().flatMap(List::stream).map(Producer::repo).distinct()
                .filter(visible).filter(r -> !used.contains(r)).sorted().toList();
    }

    /** External artifacts used by the most repos. */
    public List<ExternalUsage> topExternal(int limit, Predicate<String> visible) {
        Map<String, SortedSet<String>> repos = new HashMap<>();
        Map<String, SortedSet<String>> versions = new HashMap<>();
        for (String r : manifests.keySet()) {
            if (!visible.test(r)) continue;
            externalDependencies(r).forEach((coord, vs) -> {
                repos.computeIfAbsent(coord, k -> new TreeSet<>()).add(r);
                versions.computeIfAbsent(coord, k -> new TreeSet<>()).addAll(vs);
            });
        }
        return repos.entrySet().stream()
                .map(e -> new ExternalUsage(e.getKey(), e.getValue().size(), versions.get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparingInt(ExternalUsage::repoCount).reversed().thenComparing(ExternalUsage::coordinate))
                .limit(limit).toList();
    }

    /** Which visible repos use an external or internal artifact, with versions. */
    public List<RepoEdge> usersOf(String coordinateKey, Predicate<String> visible) {
        List<RepoEdge> result = new ArrayList<>();
        for (var e : manifests.entrySet()) {
            if (!visible.test(e.getKey())) continue;
            for (ModuleManifest m : e.getValue().modules()) {
                for (Dependency d : m.dependencies()) {
                    if (d.coordinate().key().equals(coordinateKey)) {
                        result.add(new RepoEdge(e.getKey(), null, coordinateKey, d.resolvedVersion(), d.scope(),
                                false, m.manifestPath()));
                    }
                }
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------

    private static List<List<String>> tarjan(Set<String> nodes, Map<String, Set<String>> adj) {
        Map<String, Integer> index = new HashMap<>(), low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        List<List<String>> result = new ArrayList<>();
        int[] counter = {0};
        for (String n : nodes) {
            if (!index.containsKey(n)) strongConnect(n, adj, index, low, stack, onStack, result, counter);
        }
        return result;
    }

    private static void strongConnect(String v, Map<String, Set<String>> adj, Map<String, Integer> index,
                                      Map<String, Integer> low, Deque<String> stack, Set<String> onStack,
                                      List<List<String>> result, int[] counter) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.add(v);
        for (String w : adj.getOrDefault(v, Set.of())) {
            if (!index.containsKey(w)) {
                strongConnect(w, adj, index, low, stack, onStack, result, counter);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            List<String> comp = new ArrayList<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                comp.add(w);
            } while (!w.equals(v));
            result.add(comp);
        }
    }

    /** Compares dotted numeric versions ("3.0.10" > "3.0.9"); non-numeric parts compare as text. */
    public static int compareVersions(String a, String b) {
        String[] pa = a.replaceFirst("^v", "").split("[.\\-+]");
        String[] pb = b.replaceFirst("^v", "").split("[.\\-+]");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            String x = i < pa.length ? pa[i] : "0", y = i < pb.length ? pb[i] : "0";
            int c;
            if (x.matches("\\d+") && y.matches("\\d+")) c = Long.compare(Long.parseLong(x), Long.parseLong(y));
            else c = x.compareTo(y);
            if (c != 0) return c;
        }
        return 0;
    }
}
