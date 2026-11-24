package com.codegraph.app.knowledge;

import com.codegraph.core.knowledge.Architecture;
import com.codegraph.core.model.*;
import com.codegraph.indexer.IndexerService;

import java.util.*;
import java.util.function.Predicate;

/**
 * Builds a {@link FileGraph} from the code graph, and folds it into the groups at any
 * level of a repo's directory tree.
 *
 * <p>Paths are normalised before grouping: build-layout directories
 * ({@code src/main/java} and the like) are dropped, and a chain of directories with a
 * single child ({@code com/hitorro/util}) is walked through, so each level shows a
 * real choice of modules or packages.
 */
public final class ArchitectureBuilder {

    /** Reference kinds that say one piece of code depends on another. */
    static final Set<EdgeType> REFERENCE_KINDS = EnumSet.of(EdgeType.CALLS, EdgeType.EXTENDS, EdgeType.IMPLEMENTS,
            EdgeType.INSTANTIATES, EdgeType.USES_TYPE, EdgeType.IMPORTS, EdgeType.DEPENDS_ON, EdgeType.OVERRIDES,
            EdgeType.MIXES_IN, EdgeType.CALLS_RPC, EdgeType.IMPLEMENTS_PROTO);

    static final Set<ElementType> TYPE_KINDS = EnumSet.of(ElementType.CLASS, ElementType.INTERFACE, ElementType.ENUM,
            ElementType.STRUCT, ElementType.TRAIT, ElementType.PROTOCOL, ElementType.FUNCTION);

    private static final Set<String> LAYOUT_DIRS = Set.of("src", "main", "java", "kotlin", "scala", "groovy");
    private static final Set<Language> NON_CODE = EnumSet.of(Language.MARKDOWN, Language.YAML, Language.JSON, Language.UNKNOWN);
    static final int MAX_GROUPS = 60;
    static final int MAX_KEY_TYPES = 15;

    private ArchitectureBuilder() {}

    // ---------------------------------------------------------------- build

    public static FileGraph build(IndexerService indexer, Repository repo) {
        String repoId = repo.getId();
        List<String> paths = new ArrayList<>(indexer.listFilePaths(repoId));
        Collections.sort(paths);

        List<FileGraph.FileNode> files = new ArrayList<>();
        Map<String, Integer> fileIndex = new HashMap<>();
        Map<String, List<CodeElement>> elementsByFile = new HashMap<>();
        for (String path : paths) {
            List<CodeElement> els = indexer.getElementsByFile(repoId, path);
            if (els.isEmpty()) continue;
            Language lang = els.get(0).getLanguage();
            if (lang == null || NON_CODE.contains(lang)) continue;
            int types = (int) els.stream().filter(e -> TYPE_KINDS.contains(e.getElementType())).count();
            fileIndex.put(path, files.size());
            files.add(new FileGraph.FileNode(path, lang.name().toLowerCase(), els.size(), types));
            elementsByFile.put(path, els);
        }

        Resolver resolver = new Resolver(indexer);
        Map<Long, Map<String, Integer>> internal = new HashMap<>();          // (from,to) -> kinds
        Map<String, Map<String, Integer>> external = new HashMap<>();        // from|repo|target -> kinds
        Map<String, int[]> inbound = new HashMap<>();                        // type id -> {inRepo, crossRepo}
        Map<String, CodeElement> typeElements = new HashMap<>();

        for (var entry : elementsByFile.entrySet()) {
            int from = fileIndex.get(entry.getKey());
            for (CodeElement el : entry.getValue()) {
                if (TYPE_KINDS.contains(el.getElementType())) typeElements.put(el.getId(), el);
                for (CodeEdge edge : indexer.getEdgesFrom(el.getId())) {
                    if (!REFERENCE_KINDS.contains(edge.getEdgeType())) continue;
                    Resolver.Target t = resolver.target(edge.getToId());
                    if (t == null) continue;
                    String kind = edge.getEdgeType().name();
                    if (repoId.equals(t.repoId())) {
                        Integer to = fileIndex.get(t.path());
                        if (to == null || to == from) continue;
                        internal.computeIfAbsent(((long) from << 32) | to, k -> new TreeMap<>()).merge(kind, 1, Integer::sum);
                        if (t.ownerTypeId() != null) inbound.computeIfAbsent(t.ownerTypeId(), k -> new int[2])[0]++;
                    } else {
                        String target = t.ownerTypeName() != null ? t.ownerTypeName() : t.path();
                        external.computeIfAbsent(from + "|" + t.repoId() + "|" + target, k -> new TreeMap<>())
                                .merge(kind, 1, Integer::sum);
                    }
                }
                // references into this repo from other repos
                if (TYPE_KINDS.contains(el.getElementType()) || el.getElementType() == ElementType.METHOD
                        || el.getElementType() == ElementType.CONSTRUCTOR) {
                    for (CodeEdge edge : indexer.getEdgesTo(el.getId())) {
                        if (!REFERENCE_KINDS.contains(edge.getEdgeType())) continue;
                        Resolver.Target src = resolver.target(edge.getFromId());
                        if (src == null || repoId.equals(src.repoId())) continue;
                        String owner = TYPE_KINDS.contains(el.getElementType()) ? el.getId()
                                : resolver.ownerType(el.getId());
                        if (owner != null) inbound.computeIfAbsent(owner, k -> new int[2])[1]++;
                    }
                }
            }
        }

        List<FileGraph.FileEdge> edges = new ArrayList<>();
        internal.forEach((key, kinds) -> edges.add(new FileGraph.FileEdge((int) (key >> 32), (int) (long) key, null, null, kinds)));
        external.forEach((key, kinds) -> {
            String[] parts = key.split("\\|", 3);
            edges.add(new FileGraph.FileEdge(Integer.parseInt(parts[0]), -1, parts[1], parts[2], kinds));
        });

        List<FileGraph.TypeNode> types = new ArrayList<>();
        for (CodeElement t : typeElements.values()) {
            int[] in = inbound.getOrDefault(t.getId(), new int[2]);
            Integer file = fileIndex.get(t.getFilePath());
            if (file == null) continue;
            types.add(new FileGraph.TypeNode(t.getId(), t.getName(), t.getQualifiedName(),
                    t.getElementType().name(), file, in[0], in[1]));
        }
        types.sort(Comparator.comparingInt((FileGraph.TypeNode n) -> -(n.inbound() + n.crossRepoInbound()))
                .thenComparing(FileGraph.TypeNode::qualifiedName, Comparator.nullsLast(Comparator.naturalOrder())));
        if (types.size() > 3000) types = new ArrayList<>(types.subList(0, 3000));

        return new FileGraph(repoId, commitOf(repo), files, edges, types);
    }

    static String commitOf(Repository repo) {
        if (repo.getIndexedCommit() != null) return repo.getIndexedCommit();
        return repo.getLastIndexed() == null ? "" : "t" + repo.getLastIndexed().toEpochMilli();
    }

    /** Looks up edge endpoints once each: their repo, file and enclosing type. */
    static final class Resolver {
        record Target(String repoId, String path, String ownerTypeId, String ownerTypeName) {}

        private final IndexerService indexer;
        private final Map<String, Optional<Target>> cache = new HashMap<>();
        private final Map<String, Optional<CodeElement>> elements = new HashMap<>();

        Resolver(IndexerService indexer) {
            this.indexer = indexer;
        }

        Target target(String id) {
            return cache.computeIfAbsent(id, k -> element(k).map(e -> {
                CodeElement owner = owner(e);
                return new Target(e.getRepoId(), e.getFilePath(), owner == null ? null : owner.getId(),
                        owner == null ? null : (owner.getQualifiedName() != null ? owner.getQualifiedName() : owner.getName()));
            })).orElse(null);
        }

        String ownerType(String id) {
            Target t = target(id);
            return t == null ? null : t.ownerTypeId();
        }

        private Optional<CodeElement> element(String id) {
            return elements.computeIfAbsent(id, indexer::getElementById);
        }

        private CodeElement owner(CodeElement e) {
            CodeElement cur = e;
            for (int i = 0; i < 8 && cur != null; i++) {
                if (TYPE_KINDS.contains(cur.getElementType())) return cur;
                if (cur.getParentId() == null) return null;
                cur = element(cur.getParentId()).orElse(null);
            }
            return null;
        }
    }

    // ---------------------------------------------------------------- fold

    /** Path segments used for grouping: directories without build-layout noise, then the file name. */
    static List<String> segments(String path) {
        String[] raw = path.split("/");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < raw.length - 1; i++) {
            if (!raw[i].isEmpty() && !LAYOUT_DIRS.contains(raw[i])) out.add(raw[i]);
        }
        out.add(raw[raw.length - 1]);
        return out;
    }

    /**
     * The groups directly under {@code path} (a "/"-joined segment prefix, "" for the
     * top), with chains of single-child directories walked through.
     */
    public static Architecture fold(FileGraph g, String path, Predicate<String> visible) {
        List<List<String>> segs = new ArrayList<>();
        for (FileGraph.FileNode f : g.files()) segs.add(segments(f.path()));

        List<String> prefix = path == null || path.isBlank() ? new ArrayList<>() : new ArrayList<>(List.of(path.split("/")));
        // Walk through directories that have only one child
        while (true) {
            Set<String> children = new HashSet<>();
            boolean fileHere = false;
            int under = 0;
            for (List<String> s : segs) {
                if (!startsWith(s, prefix)) continue;
                under++;
                if (s.size() == prefix.size() + 1) fileHere = true;
                else children.add(s.get(prefix.size()));
            }
            if (under == 0 || fileHere || children.size() != 1) break;
            prefix.add(children.iterator().next());
        }
        String base = String.join("/", prefix);

        // Groups: the directories (chains of single children walked through) and files under the
        // prefix. A directory holding most of the code is opened in place, so the level shows
        // real modules rather than "com" or "src".
        List<Integer> under = new ArrayList<>();
        for (int i = 0; i < segs.size(); i++) if (startsWith(segs.get(i), prefix)) under.add(i);
        Map<String, List<Integer>> frontier = children(segs, prefix, under);
        Map<String, Integer> parentDepth = new HashMap<>();
        frontier.keySet().forEach(k -> parentDepth.put(k, prefix.size()));
        int total = under.stream().mapToInt(i -> g.files().get(i).elements()).sum();
        for (int round = 0; round < 6 && frontier.size() < MAX_GROUPS; round++) {
            String dominant = null;
            for (var e : frontier.entrySet()) {
                List<String> key = List.of(e.getKey().split("/"));
                boolean dir = e.getValue().stream().anyMatch(i -> segs.get(i).size() > key.size());
                int els = e.getValue().stream().mapToInt(i -> g.files().get(i).elements()).sum();
                if (dir && els > total * 0.6) dominant = e.getKey();
            }
            if (dominant == null) break;
            List<Integer> files = frontier.remove(dominant);
            Map<String, List<Integer>> inner = children(segs, List.of(dominant.split("/")), files);
            if (inner.size() + frontier.size() > MAX_GROUPS || inner.size() < 2) {
                frontier.put(dominant, files);
                break;
            }
            frontier.putAll(inner);
            int depth = dominant.split("/").length;
            inner.keySet().forEach(k -> parentDepth.put(k, depth));
        }

        String[] groupOf = new String[segs.size()];
        Map<String, int[]> stats = new LinkedHashMap<>();                    // files, elements, types
        Map<String, Map<String, Integer>> langs = new HashMap<>();
        Map<String, Boolean> isDir = new HashMap<>();
        for (var e : frontier.entrySet()) {
            String key = e.getKey();
            int depth = key.split("/").length;
            for (int i : e.getValue()) {
                groupOf[i] = key;
                int[] st = stats.computeIfAbsent(key, k -> new int[3]);
                FileGraph.FileNode f = g.files().get(i);
                st[0]++;
                st[1] += f.elements();
                st[2] += f.types();
                langs.computeIfAbsent(key, k -> new TreeMap<>()).merge(f.language(), 1, Integer::sum);
                isDir.merge(key, segs.get(i).size() > depth, Boolean::logicalOr);
            }
        }

        // Keep the biggest groups; fold the rest into one
        String other = join(base, "…other");
        if (stats.size() > MAX_GROUPS) {
            List<String> keep = stats.entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<String, int[]> e) -> -e.getValue()[1]))
                    .limit(MAX_GROUPS - 1).map(Map.Entry::getKey).toList();
            Set<String> keepSet = new HashSet<>(keep);
            for (int i = 0; i < groupOf.length; i++) {
                if (groupOf[i] != null && !keepSet.contains(groupOf[i])) groupOf[i] = other;
            }
            Map<String, int[]> merged = new LinkedHashMap<>();
            for (String k : keep) merged.put(k, stats.get(k));
            int[] o = new int[3];
            stats.forEach((k, v) -> { if (!keepSet.contains(k)) { o[0] += v[0]; o[1] += v[1]; o[2] += v[2]; } });
            merged.put(other, o);
            stats = merged;
            isDir.put(other, false);
        }

        String outside = "..";
        Map<String, Map<String, Integer>> edgeKinds = new TreeMap<>();
        Map<String, int[]> inOut = new HashMap<>();
        Map<String, Integer> extWeight = new TreeMap<>();
        Map<String, Map<String, Integer>> extTargets = new HashMap<>();
        Map<String, Map<String, Integer>> extFrom = new HashMap<>();
        for (FileGraph.FileEdge e : g.edges()) {
            String from = groupOf[e.from()];
            if (e.to() < 0) {
                if (from == null || e.repo() == null || !visible.test(e.repo())) continue;
                extWeight.merge(e.repo(), e.weight(), Integer::sum);
                extTargets.computeIfAbsent(e.repo(), k -> new HashMap<>()).merge(e.target(), e.weight(), Integer::sum);
                extFrom.computeIfAbsent(e.repo(), k -> new TreeMap<>()).merge(from, e.weight(), Integer::sum);
                inOut.computeIfAbsent(from, k -> new int[2])[1] += e.weight();
                continue;
            }
            String to = groupOf[e.to()];
            if (from == null && to == null) continue;
            if (from == null) from = outside;
            if (to == null) to = outside;
            if (from.equals(to)) continue;
            Map<String, Integer> kinds = edgeKinds.computeIfAbsent(from + "\u0000" + to, k -> new TreeMap<>());
            e.kinds().forEach((k, v) -> kinds.merge(k, v, Integer::sum));
            inOut.computeIfAbsent(from, k -> new int[2])[1] += e.weight();
            inOut.computeIfAbsent(to, k -> new int[2])[0] += e.weight();
        }

        // Label by last segment, with its parent when two groups would otherwise look alike
        Map<String, Long> lastCounts = new HashMap<>();
        stats.keySet().forEach(k -> lastCounts.merge(last(k, 1), 1L, Long::sum));
        List<Architecture.Group> groups = new ArrayList<>();
        stats.forEach((key, st) -> {
            int[] io = inOut.getOrDefault(key, new int[2]);
            String[] parts = key.split("/");
            int from = parentDepth.getOrDefault(key, Math.max(0, parts.length - 1));
            // a chain of single directories reads "test/…/util"
            String label = parts.length - from > 2 ? parts[from] + "/…/" + parts[parts.length - 1]
                    : parts.length - from == 2 ? parts[from] + "/" + parts[parts.length - 1]
                    : lastCounts.get(last(key, 1)) > 1 ? last(key, 2) : last(key, 1);
            groups.add(new Architecture.Group(key, label, st[0], st[1], st[2], langs.getOrDefault(key, Map.of()),
                    isDir.getOrDefault(key, false), io[0], io[1]));
        });
        boolean hasOutside = edgeKinds.keySet().stream().anyMatch(k -> k.startsWith(outside + "\u0000") || k.endsWith("\u0000" + outside));
        if (hasOutside) {
            int[] io = inOut.getOrDefault(outside, new int[2]);
            groups.add(new Architecture.Group(outside, "rest of repo", 0, 0, 0, Map.of(), false, io[0], io[1]));
        }

        List<Architecture.Edge> edges = new ArrayList<>();
        edgeKinds.forEach((k, kinds) -> {
            String[] ft = k.split("\u0000");
            edges.add(new Architecture.Edge(ft[0], ft[1], kinds.values().stream().mapToInt(Integer::intValue).sum(), kinds));
        });

        List<Architecture.External> ext = new ArrayList<>();
        extWeight.forEach((repo, w) -> ext.add(new Architecture.External(repo, w, extFrom.get(repo),
                extTargets.get(repo).entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                        .limit(5).map(Map.Entry::getKey).toList())));
        ext.sort(Comparator.comparingInt(Architecture.External::weight).reversed());

        List<Architecture.KeyType> keyTypes = new ArrayList<>();
        for (FileGraph.TypeNode t : g.types()) {
            if (keyTypes.size() >= MAX_KEY_TYPES) break;
            String grp = groupOf[t.file()];
            if (grp == null || t.inbound() + t.crossRepoInbound() == 0) continue;
            keyTypes.add(new Architecture.KeyType(t.id(), t.name(), t.qualifiedName(), t.kind(),
                    g.files().get(t.file()).path(), grp, t.inbound(), t.crossRepoInbound()));
        }

        List<String> crumbs = new ArrayList<>();
        for (int i = 1; i <= prefix.size(); i++) crumbs.add(String.join("/", prefix.subList(0, i)));

        return new Architecture(g.repoId(), base, crumbs, groups, edges, ext, keyTypes, g.commit());
    }

    /**
     * The next level under {@code prefix}: each sub-directory (extended through chains of
     * single sub-directories) or file, with the files it holds.
     */
    static Map<String, List<Integer>> children(List<List<String>> segs, List<String> prefix, List<Integer> files) {
        Map<String, List<Integer>> direct = new TreeMap<>();
        for (int i : files) direct.computeIfAbsent(segs.get(i).get(prefix.size()), k -> new ArrayList<>()).add(i);
        Map<String, List<Integer>> out = new TreeMap<>();
        for (var e : direct.entrySet()) {
            List<String> key = new ArrayList<>(prefix);
            key.add(e.getKey());
            while (true) {
                Set<String> next = new HashSet<>();
                boolean fileHere = false;
                for (int i : e.getValue()) {
                    List<String> s = segs.get(i);
                    // key is itself a file, or a directory with a file directly in it
                    if (s.size() <= key.size() + 1) fileHere = true;
                    else next.add(s.get(key.size()));
                }
                if (fileHere || next.size() != 1) break;
                key.add(next.iterator().next());
            }
            out.put(String.join("/", key), e.getValue());
        }
        return out;
    }

    private static String last(String key, int n) {
        String[] parts = key.split("/");
        return String.join("/", Arrays.copyOfRange(parts, Math.max(0, parts.length - n), parts.length));
    }

    private static boolean startsWith(List<String> s, List<String> prefix) {
        if (s.size() <= prefix.size()) return false;
        for (int i = 0; i < prefix.size(); i++) if (!s.get(i).equals(prefix.get(i))) return false;
        return true;
    }

    private static String join(String base, String seg) {
        return base.isEmpty() ? seg : base + "/" + seg;
    }
}
