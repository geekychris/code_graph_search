package com.codegraph.indexer;

import com.codegraph.core.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

/**
 * Friend-of-a-Friend (FOAF) graph connectivity algorithms.
 * Operates on the raw graph store for performance, resolving elements only at the end.
 */
public class FoafAlgorithms {

    private static final Logger log = LoggerFactory.getLogger(FoafAlgorithms.class);
    private static final long TIMEOUT_MS = 10_000;

    // -------------------------------------------------------------------------
    // Neighbor helpers
    // -------------------------------------------------------------------------

    /**
     * Get neighbor IDs for an element respecting direction and edge type filters.
     * Returns a map from neighborId to the edge connecting them.
     */
    static Map<String, CodeEdge> getNeighborEdges(GraphStore store, String elementId,
                                                    EdgeDirection direction, List<EdgeType> edgeTypes) {
        Map<String, CodeEdge> neighbors = new LinkedHashMap<>();

        if (direction == EdgeDirection.OUTGOING || direction == EdgeDirection.BOTH) {
            List<CodeEdge> outEdges;
            if (edgeTypes == null || edgeTypes.isEmpty()) {
                outEdges = store.getEdgesFrom(elementId);
            } else {
                outEdges = new ArrayList<>();
                for (EdgeType et : edgeTypes) {
                    outEdges.addAll(store.getEdgesFrom(elementId, et));
                }
            }
            for (CodeEdge e : outEdges) {
                neighbors.putIfAbsent(e.getToId(), e);
            }
        }

        if (direction == EdgeDirection.INCOMING || direction == EdgeDirection.BOTH) {
            List<CodeEdge> inEdges;
            if (edgeTypes == null || edgeTypes.isEmpty()) {
                inEdges = store.getEdgesTo(elementId);
            } else {
                inEdges = new ArrayList<>();
                for (EdgeType et : edgeTypes) {
                    inEdges.addAll(store.getEdgesTo(elementId, et));
                }
            }
            for (CodeEdge e : inEdges) {
                neighbors.putIfAbsent(e.getFromId(), e);
            }
        }

        return neighbors;
    }

    static Set<String> getNeighborIds(GraphStore store, String elementId,
                                       EdgeDirection direction, List<EdgeType> edgeTypes) {
        return getNeighborEdges(store, elementId, direction, edgeTypes).keySet();
    }

    // -------------------------------------------------------------------------
    // Algorithm 1: Shortest Path (BFS)
    // -------------------------------------------------------------------------

    public static PathResult shortestPath(GraphStore store, Function<String, Optional<CodeElement>> resolver,
                                           PathQuery query) {
        long start = System.currentTimeMillis();
        String fromId = query.getFromId();
        String toId = query.getToId();
        EdgeDirection direction = query.getDirection();
        List<EdgeType> edgeTypes = query.getEdgeTypes();
        int maxDepth = query.getMaxDepth();

        if (fromId.equals(toId)) {
            return singleNodePath(resolver, fromId, start);
        }

        // BFS with parent tracking
        Map<String, String> parentNode = new LinkedHashMap<>();
        Map<String, CodeEdge> parentEdge = new LinkedHashMap<>();
        parentNode.put(fromId, null);

        Queue<String> queue = new ArrayDeque<>();
        queue.offer(fromId);
        int depth = 0;
        int levelSize = 1;
        boolean found = false;

        while (!queue.isEmpty() && depth <= maxDepth && !timedOut(start)) {
            String current = queue.poll();
            levelSize--;

            if (current.equals(toId)) {
                found = true;
                break;
            }

            Map<String, CodeEdge> neighbors = getNeighborEdges(store, current, direction, edgeTypes);
            for (Map.Entry<String, CodeEdge> entry : neighbors.entrySet()) {
                if (!parentNode.containsKey(entry.getKey())) {
                    parentNode.put(entry.getKey(), current);
                    parentEdge.put(entry.getKey(), entry.getValue());
                    queue.offer(entry.getKey());
                }
            }

            if (levelSize == 0) {
                depth++;
                levelSize = queue.size();
            }
        }

        if (!found) {
            return new PathResult(List.of(), List.of(), -1, elapsed(start));
        }

        // Reconstruct path
        List<String> pathIds = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        String cur = toId;
        while (cur != null) {
            pathIds.add(cur);
            CodeEdge edge = parentEdge.get(cur);
            if (edge != null) edges.add(edge);
            cur = parentNode.get(cur);
        }
        Collections.reverse(pathIds);
        Collections.reverse(edges);

        List<CodeElement> resolvedPath = resolveIds(resolver, pathIds);
        return new PathResult(List.of(resolvedPath), List.of(edges),
                resolvedPath.size() - 1, elapsed(start));
    }

    // -------------------------------------------------------------------------
    // Algorithm 2: All Shortest Paths (BFS level-by-level)
    // -------------------------------------------------------------------------

    public static PathResult allShortestPaths(GraphStore store, Function<String, Optional<CodeElement>> resolver,
                                               PathQuery query) {
        long start = System.currentTimeMillis();
        String fromId = query.getFromId();
        String toId = query.getToId();
        EdgeDirection direction = query.getDirection();
        List<EdgeType> edgeTypes = query.getEdgeTypes();
        int maxDepth = query.getMaxDepth();
        int maxPaths = query.getMaxPaths();

        if (fromId.equals(toId)) {
            return singleNodePath(resolver, fromId, start);
        }

        // BFS tracking multiple parents per node (at the same depth)
        Map<String, Set<String>> parents = new LinkedHashMap<>();
        Map<String, Map<String, CodeEdge>> parentEdges = new LinkedHashMap<>(); // node -> parentNode -> edge
        Map<String, Integer> depthMap = new HashMap<>();
        parents.put(fromId, new HashSet<>());
        parentEdges.put(fromId, new HashMap<>());
        depthMap.put(fromId, 0);

        Queue<String> queue = new ArrayDeque<>();
        queue.offer(fromId);
        int foundDepth = -1;

        while (!queue.isEmpty() && !timedOut(start)) {
            String current = queue.poll();
            int currentDepth = depthMap.get(current);

            if (foundDepth >= 0 && currentDepth > foundDepth) break;

            if (current.equals(toId)) {
                foundDepth = currentDepth;
                continue; // continue to find other shortest paths at same depth
            }

            if (currentDepth >= maxDepth) continue;

            Map<String, CodeEdge> neighbors = getNeighborEdges(store, current, direction, edgeTypes);
            for (Map.Entry<String, CodeEdge> entry : neighbors.entrySet()) {
                String neighborId = entry.getKey();
                int neighborDepth = depthMap.getOrDefault(neighborId, Integer.MAX_VALUE);

                if (currentDepth + 1 < neighborDepth) {
                    // First time seeing this neighbor at this depth
                    depthMap.put(neighborId, currentDepth + 1);
                    parents.put(neighborId, new HashSet<>(Set.of(current)));
                    parentEdges.put(neighborId, new HashMap<>(Map.of(current, entry.getValue())));
                    queue.offer(neighborId);
                } else if (currentDepth + 1 == neighborDepth) {
                    // Another parent at the same depth
                    parents.get(neighborId).add(current);
                    parentEdges.get(neighborId).put(current, entry.getValue());
                }
            }
        }

        if (foundDepth < 0) {
            return new PathResult(List.of(), List.of(), -1, elapsed(start));
        }

        // Enumerate all shortest paths by DFS backward from toId
        List<List<String>> allPathIds = new ArrayList<>();
        List<List<CodeEdge>> allPathEdges = new ArrayList<>();
        enumeratePaths(toId, fromId, parents, parentEdges, new ArrayDeque<>(), new ArrayDeque<>(),
                allPathIds, allPathEdges, maxPaths);

        List<List<CodeElement>> resolvedPaths = new ArrayList<>();
        for (List<String> pathIds : allPathIds) {
            resolvedPaths.add(resolveIds(resolver, pathIds));
        }

        return new PathResult(resolvedPaths, allPathEdges, foundDepth, elapsed(start));
    }

    private static void enumeratePaths(String current, String target,
                                         Map<String, Set<String>> parents,
                                         Map<String, Map<String, CodeEdge>> parentEdges,
                                         Deque<String> currentPath, Deque<CodeEdge> currentEdges,
                                         List<List<String>> allPaths, List<List<CodeEdge>> allEdges,
                                         int maxPaths) {
        currentPath.addFirst(current);

        if (current.equals(target)) {
            allPaths.add(new ArrayList<>(currentPath));
            allEdges.add(new ArrayList<>(currentEdges));
        } else {
            Set<String> pars = parents.getOrDefault(current, Set.of());
            for (String parent : pars) {
                if (allPaths.size() >= maxPaths) break;
                CodeEdge edge = parentEdges.getOrDefault(current, Map.of()).get(parent);
                if (edge != null) currentEdges.addFirst(edge);
                enumeratePaths(parent, target, parents, parentEdges, currentPath, currentEdges,
                        allPaths, allEdges, maxPaths);
                if (edge != null) currentEdges.removeFirst();
            }
        }

        currentPath.removeFirst();
    }

    // -------------------------------------------------------------------------
    // Algorithm 3: All Paths (bounded DFS)
    // -------------------------------------------------------------------------

    public static PathResult allPaths(GraphStore store, Function<String, Optional<CodeElement>> resolver,
                                       PathQuery query) {
        long start = System.currentTimeMillis();
        String fromId = query.getFromId();
        String toId = query.getToId();
        EdgeDirection direction = query.getDirection();
        List<EdgeType> edgeTypes = query.getEdgeTypes();
        int maxDepth = query.getMaxDepth();
        int maxPaths = query.getMaxPaths();

        List<List<String>> allPathIds = new ArrayList<>();
        List<List<CodeEdge>> allPathEdges = new ArrayList<>();

        // DFS with path-local visited set
        Deque<String> currentPath = new ArrayDeque<>();
        Deque<CodeEdge> currentEdges = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();

        dfsAllPaths(store, fromId, toId, direction, edgeTypes, maxDepth, maxPaths,
                currentPath, currentEdges, visited, allPathIds, allPathEdges, start);

        int shortest = allPathIds.stream().mapToInt(p -> p.size() - 1).min().orElse(-1);

        List<List<CodeElement>> resolvedPaths = new ArrayList<>();
        for (List<String> pathIds : allPathIds) {
            resolvedPaths.add(resolveIds(resolver, pathIds));
        }

        return new PathResult(resolvedPaths, allPathEdges, shortest, elapsed(start));
    }

    private static void dfsAllPaths(GraphStore store, String current, String target,
                                      EdgeDirection direction, List<EdgeType> edgeTypes,
                                      int maxDepth, int maxPaths,
                                      Deque<String> currentPath, Deque<CodeEdge> currentEdges,
                                      Set<String> visited,
                                      List<List<String>> allPaths, List<List<CodeEdge>> allEdges,
                                      long startTime) {
        if (allPaths.size() >= maxPaths || timedOut(startTime)) return;

        currentPath.addLast(current);
        visited.add(current);

        if (current.equals(target)) {
            allPaths.add(new ArrayList<>(currentPath));
            allEdges.add(new ArrayList<>(currentEdges));
        } else if (currentPath.size() <= maxDepth) {
            Map<String, CodeEdge> neighbors = getNeighborEdges(store, current, direction, edgeTypes);
            for (Map.Entry<String, CodeEdge> entry : neighbors.entrySet()) {
                if (!visited.contains(entry.getKey())) {
                    currentEdges.addLast(entry.getValue());
                    dfsAllPaths(store, entry.getKey(), target, direction, edgeTypes, maxDepth, maxPaths,
                            currentPath, currentEdges, visited, allPaths, allEdges, startTime);
                    currentEdges.removeLast();
                }
            }
        }

        currentPath.removeLast();
        visited.remove(current);
    }

    // -------------------------------------------------------------------------
    // Algorithm 4-6: Similarity (Common Neighbors + Jaccard + Adamic-Adar)
    // -------------------------------------------------------------------------

    public static SimilarityResult similarity(GraphStore store, Function<String, Optional<CodeElement>> resolver,
                                               String elementIdA, String elementIdB,
                                               EdgeDirection direction, List<EdgeType> edgeTypes) {
        long start = System.currentTimeMillis();
        SimilarityResult result = new SimilarityResult();

        resolver.apply(elementIdA).ifPresent(result::setElementA);
        resolver.apply(elementIdB).ifPresent(result::setElementB);

        Set<String> neighborsA = getNeighborIds(store, elementIdA, direction, edgeTypes);
        Set<String> neighborsB = getNeighborIds(store, elementIdB, direction, edgeTypes);

        result.setNeighborCountA(neighborsA.size());
        result.setNeighborCountB(neighborsB.size());

        // Common neighbors = intersection
        Set<String> common = new LinkedHashSet<>(neighborsA);
        common.retainAll(neighborsB);

        List<CodeElement> resolvedCommon = resolveIds(resolver, new ArrayList<>(common));
        result.setCommonNeighbors(resolvedCommon);

        // Jaccard = |intersection| / |union|
        int unionSize = neighborsA.size() + neighborsB.size() - common.size();
        result.setJaccardSimilarity(unionSize > 0 ? (double) common.size() / unionSize : 0.0);

        // Adamic-Adar = sum(1 / log(degree(c))) for each common neighbor c
        double adamicAdar = 0.0;
        for (String commonId : common) {
            int degree = getNeighborIds(store, commonId, direction, edgeTypes).size();
            if (degree > 1) {
                adamicAdar += 1.0 / Math.log(degree);
            }
            // degree <= 1: skip (1/log(1) = infinity)
        }
        result.setAdamicAdarIndex(adamicAdar);

        result.setComputeTimeMs(elapsed(start));
        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static PathResult singleNodePath(Function<String, Optional<CodeElement>> resolver,
                                              String nodeId, long startTime) {
        Optional<CodeElement> el = resolver.apply(nodeId);
        if (el.isEmpty()) {
            return new PathResult(List.of(), List.of(), -1, elapsed(startTime));
        }
        return new PathResult(List.of(List.of(el.get())), List.of(List.of()), 0, elapsed(startTime));
    }

    private static List<CodeElement> resolveIds(Function<String, Optional<CodeElement>> resolver,
                                                  List<String> ids) {
        List<CodeElement> result = new ArrayList<>();
        for (String id : ids) {
            resolver.apply(id).ifPresent(result::add);
        }
        return result;
    }

    private static boolean timedOut(long startMs) {
        return System.currentTimeMillis() - startMs > TIMEOUT_MS;
    }

    private static long elapsed(long startMs) {
        return System.currentTimeMillis() - startMs;
    }
}
