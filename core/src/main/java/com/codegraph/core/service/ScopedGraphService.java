package com.codegraph.core.service;

import com.codegraph.core.model.*;
import com.codegraph.core.security.Principal;
import com.codegraph.core.security.RequestContext;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * A {@link GraphService} that only returns what the current {@link Principal} may see.
 *
 * <p>Every REST route and MCP tool reads through this wrapper, so visibility is enforced
 * in one place. Background work (indexing, sync, resolution) uses the unwrapped service.
 *
 * <p>Rules for a caller without private access:
 * <ul>
 *   <li>A node is visible when its owning repo is public. Repo ids starting with
 *       {@code _} are shared pseudo-repos (external artifacts, vocabulary) and are public.
 *       A repo with unknown visibility is treated as private.</li>
 *   <li>An edge is visible when its source is visible and its target is visible or
 *       absent (an unresolved id carries no information).</li>
 *   <li>Search filters by repo inside Lucene so counts and limits stay correct.</li>
 *   <li>Path searches never traverse a hidden node; similarity is recomputed over
 *       visible neighbours only.</li>
 * </ul>
 * Writes pass straight through; callers guard them with {@link Principal#admin()}.
 */
public class ScopedGraphService implements GraphService {

    private static final long REPO_CACHE_TTL_MS = 5_000;

    private final GraphService delegate;

    private volatile Map<String, Boolean> publicRepoCache = Map.of();
    private volatile long publicRepoCacheAt = 0;

    public ScopedGraphService(GraphService delegate) {
        this.delegate = delegate;
    }

    /** The unscoped service, for internal work that must see everything. */
    public GraphService unwrap() { return delegate; }

    // -------------------------------------------------------------------------
    // Visibility checks
    // -------------------------------------------------------------------------

    private static boolean unrestricted() {
        return RequestContext.current().seesPrivate();
    }

    /** True if the current caller may see data owned by {@code repoId}. */
    public boolean canSeeRepo(String repoId) {
        if (unrestricted()) return true;
        if (repoId == null) return false;
        if (repoId.startsWith("_")) return true;
        return Boolean.TRUE.equals(publicRepos().get(repoId));
    }

    private Map<String, Boolean> publicRepos() {
        long now = System.currentTimeMillis();
        if (now - publicRepoCacheAt > REPO_CACHE_TTL_MS) {
            Map<String, Boolean> m = new HashMap<>();
            for (Repository r : delegate.listRepositories()) m.put(r.getId(), r.isPublic());
            publicRepoCache = m;
            publicRepoCacheAt = now;
        }
        return publicRepoCache;
    }

    private void invalidateRepoCache() { publicRepoCacheAt = 0; }

    private boolean visible(CodeElement e) {
        return e != null && canSeeRepo(e.getRepoId());
    }

    private boolean visibleId(String id) {
        if (unrestricted()) return true;
        return delegate.getElementById(id).map(this::visible).orElse(false);
    }

    /** Visible, or not stored at all (a dangling reference reveals nothing). */
    private boolean visibleOrAbsent(String id) {
        if (unrestricted()) return true;
        return delegate.getElementById(id).map(this::visible).orElse(true);
    }

    private boolean visible(CodeEdge edge) {
        return edge != null && visibleOrAbsent(edge.getFromId()) && visibleOrAbsent(edge.getToId());
    }

    private List<CodeElement> filter(List<CodeElement> elements) {
        if (unrestricted() || elements == null) return elements;
        return elements.stream().filter(this::visible).collect(Collectors.toList());
    }

    private Optional<CodeElement> filter(Optional<CodeElement> element) {
        if (unrestricted()) return element;
        return element.filter(this::visible);
    }

    private List<CodeEdge> filterEdges(List<CodeEdge> edges) {
        if (unrestricted() || edges == null) return edges;
        return edges.stream().filter(this::visible).collect(Collectors.toList());
    }

    /** Lookups by id answer as if a hidden element did not exist. */
    private boolean hidden(String elementId) {
        return !unrestricted() && !visibleId(elementId);
    }

    // -------------------------------------------------------------------------
    // Writes: pass through
    // -------------------------------------------------------------------------

    @Override public void saveElement(CodeElement element) { delegate.saveElement(element); }
    @Override public void saveElements(List<CodeElement> elements) { delegate.saveElements(elements); }
    @Override public void saveEdge(CodeEdge edge) { delegate.saveEdge(edge); }
    @Override public void saveEdges(List<CodeEdge> edges) { delegate.saveEdges(edges); }
    @Override public void deleteElementsForFile(String repoId, String filePath) { delegate.deleteElementsForFile(repoId, filePath); }

    @Override
    public void deleteRepo(String repoId) {
        delegate.deleteRepo(repoId);
        invalidateRepoCache();
    }

    @Override
    public void saveRepository(Repository repo) {
        delegate.saveRepository(repo);
        invalidateRepoCache();
    }

    // -------------------------------------------------------------------------
    // Element retrieval
    // -------------------------------------------------------------------------

    @Override
    public Optional<CodeElement> getElementById(String id) { return filter(delegate.getElementById(id)); }

    @Override
    public List<CodeElement> getElementsByFile(String repoId, String filePath) {
        return canSeeRepo(repoId) ? delegate.getElementsByFile(repoId, filePath) : List.of();
    }

    @Override
    public List<CodeElement> getElementsByQualifiedName(String repoId, String qualifiedName) {
        if (repoId != null && !canSeeRepo(repoId)) return List.of();
        return filter(delegate.getElementsByQualifiedName(repoId, qualifiedName));
    }

    // -------------------------------------------------------------------------
    // Traversal: hide the start node, then filter results
    // -------------------------------------------------------------------------

    @Override
    public Optional<CodeElement> getParent(String id) { return hidden(id) ? Optional.empty() : filter(delegate.getParent(id)); }

    @Override
    public List<CodeElement> getAncestors(String id) { return hidden(id) ? List.of() : filter(delegate.getAncestors(id)); }

    @Override
    public List<CodeElement> getChildren(String id) { return hidden(id) ? List.of() : filter(delegate.getChildren(id)); }

    @Override
    public List<CodeElement> getChildren(String id, ElementType type) { return hidden(id) ? List.of() : filter(delegate.getChildren(id, type)); }

    @Override
    public List<CodeElement> getSiblings(String id) { return hidden(id) ? List.of() : filter(delegate.getSiblings(id)); }

    @Override
    public Optional<CodeElement> getSuperclass(String id) { return hidden(id) ? Optional.empty() : filter(delegate.getSuperclass(id)); }

    @Override
    public List<CodeElement> getInterfaces(String id) { return hidden(id) ? List.of() : filter(delegate.getInterfaces(id)); }

    @Override
    public List<CodeElement> getSubclasses(String id) { return hidden(id) ? List.of() : filter(delegate.getSubclasses(id)); }

    @Override
    public List<CodeElement> getImplementors(String id) { return hidden(id) ? List.of() : filter(delegate.getImplementors(id)); }

    @Override
    public Optional<CodeElement> getOverriddenMethod(String id) { return hidden(id) ? Optional.empty() : filter(delegate.getOverriddenMethod(id)); }

    @Override
    public List<CodeElement> getOverriders(String id) { return hidden(id) ? List.of() : filter(delegate.getOverriders(id)); }

    @Override
    public List<CodeElement> getCallers(String id) { return hidden(id) ? List.of() : filter(delegate.getCallers(id)); }

    @Override
    public List<CodeElement> getCallees(String id) { return hidden(id) ? List.of() : filter(delegate.getCallees(id)); }

    @Override
    public List<List<CodeElement>> getCallChain(String fromId, String toId, int maxDepth) {
        if (hidden(fromId) || hidden(toId)) return List.of();
        List<List<CodeElement>> chains = delegate.getCallChain(fromId, toId, maxDepth);
        if (unrestricted()) return chains;
        return chains.stream().filter(c -> c.stream().allMatch(this::visible)).collect(Collectors.toList());
    }

    @Override
    public List<CodeElement> getComments(String id) { return hidden(id) ? List.of() : filter(delegate.getComments(id)); }

    @Override
    public List<CodeElement> getAnnotations(String id) { return hidden(id) ? List.of() : filter(delegate.getAnnotations(id)); }

    @Override
    public List<CodeElement> getImports(String id) { return hidden(id) ? List.of() : filter(delegate.getImports(id)); }

    @Override
    public List<CodeElement> getImportedBy(String id) { return hidden(id) ? List.of() : filter(delegate.getImportedBy(id)); }

    @Override
    public List<CodeElement> getUsages(String id) { return hidden(id) ? List.of() : filter(delegate.getUsages(id)); }

    // -------------------------------------------------------------------------
    // Repositories
    // -------------------------------------------------------------------------

    @Override
    public List<Repository> listRepositories() {
        List<Repository> all = delegate.listRepositories();
        if (unrestricted()) return all;
        return all.stream().filter(Repository::isPublic).collect(Collectors.toList());
    }

    @Override
    public Optional<Repository> getRepository(String repoId) {
        Optional<Repository> repo = delegate.getRepository(repoId);
        if (unrestricted()) return repo;
        return repo.filter(Repository::isPublic);
    }

    @Override
    public List<String> listFilePaths(String repoId) {
        return canSeeRepo(repoId) ? delegate.listFilePaths(repoId) : List.of();
    }

    // -------------------------------------------------------------------------
    // Edges
    // -------------------------------------------------------------------------

    @Override
    public List<CodeEdge> getEdgesFrom(String id) { return hidden(id) ? List.of() : filterEdges(delegate.getEdgesFrom(id)); }

    @Override
    public List<CodeEdge> getEdgesTo(String id) { return hidden(id) ? List.of() : filterEdges(delegate.getEdgesTo(id)); }

    @Override
    public List<CodeEdge> getEdgesFrom(String id, EdgeType type) { return hidden(id) ? List.of() : filterEdges(delegate.getEdgesFrom(id, type)); }

    @Override
    public List<CodeEdge> getEdgesTo(String id, EdgeType type) { return hidden(id) ? List.of() : filterEdges(delegate.getEdgesTo(id, type)); }

    // -------------------------------------------------------------------------
    // Search: restrict repos inside the query so limits and counts stay correct
    // -------------------------------------------------------------------------

    @Override
    public List<CodeElement> search(SearchQuery query) {
        SearchQuery scoped = scope(query);
        return scoped == null ? List.of() : delegate.search(scoped);
    }

    @Override
    public long count(SearchQuery query) {
        SearchQuery scoped = scope(query);
        return scoped == null ? 0 : delegate.count(scoped);
    }

    /** Returns the query restricted to visible repos, or null if nothing is visible. */
    private SearchQuery scope(SearchQuery query) {
        if (unrestricted()) return query;
        List<String> allowed = publicRepos().entrySet().stream()
                .filter(Map.Entry::getValue).map(Map.Entry::getKey).collect(Collectors.toList());
        List<String> requested = query.getRepoIds();
        List<String> effective = (requested == null || requested.isEmpty())
                ? allowed
                : requested.stream().filter(this::canSeeRepo).collect(Collectors.toList());
        if (effective.isEmpty()) return null;
        query.setRepoIds(effective);
        return query;
    }

    // -------------------------------------------------------------------------
    // Connectivity
    // -------------------------------------------------------------------------

    private PathResult scopedPaths(PathQuery query, java.util.function.Function<PathQuery, PathResult> run) {
        if (unrestricted()) return run.apply(query);
        if (hidden(query.getFromId()) || hidden(query.getToId())) {
            return new PathResult(List.of(), List.of(), -1, 0);
        }
        Predicate<String> previous = query.getNodeFilter();
        Predicate<String> mine = this::visibleId;
        query.setNodeFilter(previous == null ? mine : previous.and(mine));
        return run.apply(query);
    }

    @Override
    public PathResult findShortestPath(PathQuery query) { return scopedPaths(query, delegate::findShortestPath); }

    @Override
    public PathResult findAllShortestPaths(PathQuery query) { return scopedPaths(query, delegate::findAllShortestPaths); }

    @Override
    public PathResult findAllPaths(PathQuery query) { return scopedPaths(query, delegate::findAllPaths); }

    @Override
    public SimilarityResult computeSimilarity(String a, String b, EdgeDirection direction, List<EdgeType> edgeTypes) {
        if (unrestricted()) return delegate.computeSimilarity(a, b, direction, edgeTypes);
        SimilarityResult result = new SimilarityResult();
        if (hidden(a) || hidden(b)) return result;
        long start = System.currentTimeMillis();
        delegate.getElementById(a).ifPresent(result::setElementA);
        delegate.getElementById(b).ifPresent(result::setElementB);

        Set<String> na = visibleNeighbours(a, direction, edgeTypes);
        Set<String> nb = visibleNeighbours(b, direction, edgeTypes);
        Set<String> common = new LinkedHashSet<>(na);
        common.retainAll(nb);

        result.setNeighborCountA(na.size());
        result.setNeighborCountB(nb.size());
        result.setCommonNeighbors(common.stream().map(delegate::getElementById)
                .flatMap(Optional::stream).collect(Collectors.toList()));
        int union = na.size() + nb.size() - common.size();
        result.setJaccardSimilarity(union > 0 ? (double) common.size() / union : 0.0);
        double aa = 0;
        for (String c : common) {
            int degree = visibleNeighbours(c, direction, edgeTypes).size();
            if (degree > 1) aa += 1.0 / Math.log(degree);
        }
        result.setAdamicAdarIndex(aa);
        result.setComputeTimeMs(System.currentTimeMillis() - start);
        return result;
    }

    private Set<String> visibleNeighbours(String id, EdgeDirection direction, List<EdgeType> types) {
        Set<String> out = new LinkedHashSet<>();
        boolean outgoing = direction == EdgeDirection.OUTGOING || direction == EdgeDirection.BOTH;
        boolean incoming = direction == EdgeDirection.INCOMING || direction == EdgeDirection.BOTH;
        List<EdgeType> each = (types == null || types.isEmpty()) ? Collections.singletonList(null) : types;
        for (EdgeType t : each) {
            if (outgoing) {
                for (CodeEdge e : t == null ? delegate.getEdgesFrom(id) : delegate.getEdgesFrom(id, t)) {
                    if (visibleId(e.getToId())) out.add(e.getToId());
                }
            }
            if (incoming) {
                for (CodeEdge e : t == null ? delegate.getEdgesTo(id) : delegate.getEdgesTo(id, t)) {
                    if (visibleId(e.getFromId())) out.add(e.getFromId());
                }
            }
        }
        return out;
    }

    @Override
    public SubGraph getSubGraph(String rootId, int depth, List<EdgeType> edgeTypes) {
        if (unrestricted()) return delegate.getSubGraph(rootId, depth, edgeTypes);
        if (hidden(rootId)) return new SubGraph(List.of(), List.of());
        SubGraph g = delegate.getSubGraph(rootId, depth, edgeTypes);
        List<CodeElement> nodes = filter(g.nodes());
        Set<String> kept = nodes.stream().map(CodeElement::getId).collect(Collectors.toSet());
        List<CodeEdge> edges = g.edges().stream()
                .filter(e -> kept.contains(e.getFromId()) && kept.contains(e.getToId()))
                .collect(Collectors.toList());
        return new SubGraph(nodes, edges);
    }

    @Override
    public void close() { delegate.close(); }
}
