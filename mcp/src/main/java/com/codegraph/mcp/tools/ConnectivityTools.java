package com.codegraph.mcp.tools;

import com.codegraph.core.model.*;
import com.codegraph.core.service.GraphService;
import com.codegraph.mcp.McpTool;

import java.util.*;

/**
 * MCP tools for FOAF / connectivity algorithms: shortest paths, all paths, similarity.
 */
public class ConnectivityTools {

    private static EdgeDirection parseDirection(Object val) {
        if (val == null) return EdgeDirection.BOTH;
        try { return EdgeDirection.valueOf(val.toString().toUpperCase()); } catch (Exception e) { return EdgeDirection.BOTH; }
    }

    private static List<EdgeType> parseEdgeTypes(Object val) {
        if (val == null) return null;
        if (val instanceof List<?> list) {
            List<EdgeType> types = new ArrayList<>();
            for (Object item : list) {
                try { types.add(EdgeType.valueOf(item.toString().toUpperCase())); } catch (Exception ignored) {}
            }
            return types.isEmpty() ? null : types;
        }
        return null;
    }

    private static PathQuery buildPathQuery(Map<String, Object> args) {
        PathQuery pq = new PathQuery();
        pq.setFromId((String) args.get("from_id"));
        pq.setToId((String) args.get("to_id"));
        if (args.containsKey("max_depth")) pq.setMaxDepth(((Number) args.get("max_depth")).intValue());
        pq.setDirection(parseDirection(args.get("direction")));
        pq.setEdgeTypes(parseEdgeTypes(args.get("edge_types")));
        if (args.containsKey("max_paths")) pq.setMaxPaths(((Number) args.get("max_paths")).intValue());
        return pq;
    }

    private static Map<String, Object> pathQuerySchema(boolean includeMaxPaths) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> fromId = new LinkedHashMap<>();
        fromId.put("type", "string");
        fromId.put("description", "The element ID to start from.");
        props.put("from_id", fromId);

        Map<String, Object> toId = new LinkedHashMap<>();
        toId.put("type", "string");
        toId.put("description", "The target element ID to find paths to.");
        props.put("to_id", toId);

        Map<String, Object> maxDepth = new LinkedHashMap<>();
        maxDepth.put("type", "integer");
        maxDepth.put("description", "Maximum traversal depth (default 6, max 10).");
        maxDepth.put("default", 6);
        props.put("max_depth", maxDepth);

        Map<String, Object> direction = new LinkedHashMap<>();
        direction.put("type", "string");
        direction.put("enum", List.of("BOTH", "OUTGOING", "INCOMING"));
        direction.put("description", "Edge traversal direction: BOTH (undirected), OUTGOING, or INCOMING. Default: BOTH.");
        direction.put("default", "BOTH");
        props.put("direction", direction);

        Map<String, Object> edgeTypes = new LinkedHashMap<>();
        edgeTypes.put("type", "array");
        edgeTypes.put("items", Map.of("type", "string"));
        edgeTypes.put("description", "Filter by edge types (e.g. CALLS, EXTENDS, IMPLEMENTS). Empty = all types.");
        props.put("edge_types", edgeTypes);

        if (includeMaxPaths) {
            Map<String, Object> maxPaths = new LinkedHashMap<>();
            maxPaths.put("type", "integer");
            maxPaths.put("description", "Maximum number of paths to return (default 50, max 200).");
            maxPaths.put("default", 50);
            props.put("max_paths", maxPaths);
        }

        schema.put("properties", props);
        schema.put("required", List.of("from_id", "to_id"));
        return schema;
    }

    private static String formatPathResult(PathResult result, String title) {
        if (result.getPaths() == null || result.getPaths().isEmpty()) {
            return title + "\n\nNo paths found. (computed in " + result.getComputeTimeMs() + "ms)";
        }

        StringBuilder sb = new StringBuilder(title);
        sb.append("\nFound ").append(result.getPaths().size()).append(" path(s)")
          .append(", shortest length: ").append(result.getShortestPathLength())
          .append(" (computed in ").append(result.getComputeTimeMs()).append("ms)\n\n");

        for (int p = 0; p < result.getPaths().size(); p++) {
            List<CodeElement> path = result.getPaths().get(p);
            List<CodeEdge> edges = (result.getPathEdges() != null && p < result.getPathEdges().size())
                    ? result.getPathEdges().get(p) : List.of();

            sb.append("### Path ").append(p + 1).append(" (").append(path.size() - 1).append(" hops)\n");
            for (int i = 0; i < path.size(); i++) {
                CodeElement el = path.get(i);
                sb.append("  ".repeat(i));
                if (i > 0 && i - 1 < edges.size()) {
                    sb.append("--[").append(edges.get(i - 1).getEdgeType()).append("]--> ");
                }
                sb.append("[").append(el.getElementType()).append("] ");
                sb.append(el.getQualifiedName() != null ? el.getQualifiedName() : el.getName());
                if (el.getFilePath() != null && el.getLineStart() > 0) {
                    sb.append(" (").append(el.getFilePath()).append(":").append(el.getLineStart()).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // find_shortest_path
    // -------------------------------------------------------------------------

    public static class FindShortestPath implements McpTool {
        private final GraphService graphService;
        public FindShortestPath(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "find_shortest_path"; }

        @Override
        public String getDescription() {
            return "Find the shortest path between two code elements using BFS. Supports configurable "
                    + "edge direction (BOTH/OUTGOING/INCOMING) and edge type filtering. Returns the single "
                    + "shortest path with all intermediate elements and the edges connecting them.";
        }

        @Override public Map<String, Object> getInputSchema() { return pathQuerySchema(false); }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            PathQuery pq = buildPathQuery(args);
            PathResult result = graphService.findShortestPath(pq);
            return formatPathResult(result, "## Shortest Path");
        }
    }

    // -------------------------------------------------------------------------
    // find_all_shortest_paths
    // -------------------------------------------------------------------------

    public static class FindAllShortestPaths implements McpTool {
        private final GraphService graphService;
        public FindAllShortestPaths(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "find_all_shortest_paths"; }

        @Override
        public String getDescription() {
            return "Find ALL shortest paths (same minimum length) between two code elements. "
                    + "Useful when there are multiple equally short connections between elements. "
                    + "Supports edge direction and type filtering.";
        }

        @Override public Map<String, Object> getInputSchema() { return pathQuerySchema(true); }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            PathQuery pq = buildPathQuery(args);
            PathResult result = graphService.findAllShortestPaths(pq);
            return formatPathResult(result, "## All Shortest Paths");
        }
    }

    // -------------------------------------------------------------------------
    // find_all_paths
    // -------------------------------------------------------------------------

    public static class FindAllPaths implements McpTool {
        private final GraphService graphService;
        public FindAllPaths(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "find_all_paths"; }

        @Override
        public String getDescription() {
            return "Find ALL paths between two code elements up to a maximum depth, using depth-bounded DFS. "
                    + "Returns every distinct path (no cycles) within the depth limit. Useful for understanding "
                    + "all the ways two elements are connected. Has a max_paths limit to prevent explosion.";
        }

        @Override public Map<String, Object> getInputSchema() { return pathQuerySchema(true); }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            PathQuery pq = buildPathQuery(args);
            PathResult result = graphService.findAllPaths(pq);
            return formatPathResult(result, "## All Paths");
        }
    }

    // -------------------------------------------------------------------------
    // get_similarity
    // -------------------------------------------------------------------------

    public static class GetSimilarity implements McpTool {
        private final GraphService graphService;
        public GetSimilarity(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_similarity"; }

        @Override
        public String getDescription() {
            return "Compute similarity metrics between two code elements based on their shared graph neighbors. "
                    + "Returns: (1) Common Neighbors - elements both connect to, (2) Jaccard Similarity - ratio "
                    + "of shared to total neighbors (0.0-1.0), (3) Adamic-Adar Index - weighted score favoring "
                    + "exclusive shared neighbors. Useful for finding how structurally related two elements are.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("type", "string");
            a.put("description", "Element ID of the first element.");
            props.put("element_a", a);

            Map<String, Object> b = new LinkedHashMap<>();
            b.put("type", "string");
            b.put("description", "Element ID of the second element.");
            props.put("element_b", b);

            Map<String, Object> direction = new LinkedHashMap<>();
            direction.put("type", "string");
            direction.put("enum", List.of("BOTH", "OUTGOING", "INCOMING"));
            direction.put("description", "Edge direction for neighbor computation. Default: BOTH.");
            direction.put("default", "BOTH");
            props.put("direction", direction);

            Map<String, Object> edgeTypes = new LinkedHashMap<>();
            edgeTypes.put("type", "array");
            edgeTypes.put("items", Map.of("type", "string"));
            edgeTypes.put("description", "Filter by edge types. Empty = all types.");
            props.put("edge_types", edgeTypes);

            schema.put("properties", props);
            schema.put("required", List.of("element_a", "element_b"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String a = (String) args.get("element_a");
            String b = (String) args.get("element_b");
            EdgeDirection direction = parseDirection(args.get("direction"));
            List<EdgeType> edgeTypes = parseEdgeTypes(args.get("edge_types"));

            SimilarityResult result = graphService.computeSimilarity(a, b, direction, edgeTypes);

            StringBuilder sb = new StringBuilder("## Similarity Analysis\n\n");

            if (result.getElementA() != null) {
                sb.append("**Element A:** [").append(result.getElementA().getElementType()).append("] ")
                  .append(result.getElementA().getQualifiedName() != null ? result.getElementA().getQualifiedName() : result.getElementA().getName())
                  .append(" (").append(result.getNeighborCountA()).append(" neighbors)\n");
            }
            if (result.getElementB() != null) {
                sb.append("**Element B:** [").append(result.getElementB().getElementType()).append("] ")
                  .append(result.getElementB().getQualifiedName() != null ? result.getElementB().getQualifiedName() : result.getElementB().getName())
                  .append(" (").append(result.getNeighborCountB()).append(" neighbors)\n");
            }

            sb.append("\n### Metrics\n");
            sb.append("- **Jaccard Similarity:** ").append(String.format("%.4f", result.getJaccardSimilarity()))
              .append(" (").append(String.format("%.1f%%", result.getJaccardSimilarity() * 100)).append(")\n");
            sb.append("- **Adamic-Adar Index:** ").append(String.format("%.4f", result.getAdamicAdarIndex())).append("\n");
            sb.append("- **Common Neighbors:** ").append(result.getCommonNeighbors().size()).append("\n");
            sb.append("\nComputed in ").append(result.getComputeTimeMs()).append("ms\n");

            if (!result.getCommonNeighbors().isEmpty()) {
                sb.append("\n### Common Neighbors\n\n");
                int i = 0;
                for (CodeElement cn : result.getCommonNeighbors()) {
                    i++;
                    sb.append(i).append(". [").append(cn.getElementType()).append("] ");
                    sb.append(cn.getQualifiedName() != null ? cn.getQualifiedName() : cn.getName());
                    if (cn.getFilePath() != null && cn.getLineStart() > 0) {
                        sb.append(" (").append(cn.getFilePath()).append(":").append(cn.getLineStart()).append(")");
                    }
                    sb.append("\n");
                    if (i >= 50) {
                        sb.append("... and ").append(result.getCommonNeighbors().size() - 50).append(" more\n");
                        break;
                    }
                }
            }

            return sb.toString();
        }
    }
}
