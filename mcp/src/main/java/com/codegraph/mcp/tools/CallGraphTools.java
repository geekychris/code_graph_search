package com.codegraph.mcp.tools;

import com.codegraph.core.model.*;
import com.codegraph.core.service.GraphService;
import com.codegraph.mcp.McpTool;

import java.util.*;

/**
 * Tools for navigating the call graph between methods and functions.
 */
public class CallGraphTools {

    // -------------------------------------------------------------------------
    // get_callers
    // -------------------------------------------------------------------------

    public static class GetCallers implements McpTool {
        private final GraphService graphService;
        public GetCallers(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_callers"; }

        @Override
        public String getDescription() {
            return "Get all methods/functions that call a given method or function. Follows CALLS edges "
                    + "inbound. Includes the calling method's class context and file location. Use this "
                    + "to understand who uses a method — essential for impact analysis when refactoring.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();

            Map<String, Object> id = new LinkedHashMap<>();
            id.put("type", "string");
            id.put("description", "The method/function element ID to find callers of.");
            props.put("id", id);

            Map<String, Object> limit = new LinkedHashMap<>();
            limit.put("type", "integer");
            limit.put("description", "Maximum number of callers to return (default 20).");
            limit.put("default", 20);
            props.put("limit", limit);

            schema.put("properties", props);
            schema.put("required", List.of("id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            int limit = ((Number) args.getOrDefault("limit", 20)).intValue();

            List<CodeElement> callers = graphService.getCallers(id);
            if (callers.isEmpty()) return "No callers found for: " + id;

            if (callers.size() > limit) callers = callers.subList(0, limit);

            Optional<CodeElement> self = graphService.getElementById(id);
            StringBuilder sb = new StringBuilder("## Callers of ");
            self.ifPresent(el -> sb.append("[").append(el.getElementType()).append("] ")
                    .append(el.getQualifiedName() != null ? el.getQualifiedName() : el.getName()));
            sb.append(" (showing ").append(callers.size()).append(")\n\n");

            for (int i = 0; i < callers.size(); i++) {
                CodeElement caller = callers.get(i);
                sb.append(i + 1).append(". ");
                SearchTools.appendElementSummary(sb, caller);
                // Show the class context
                if (caller.getParentId() != null) {
                    Optional<CodeElement> parent = graphService.getElementById(caller.getParentId());
                    parent.ifPresent(p -> sb.append("\n   Class: [").append(p.getElementType()).append("] ")
                            .append(p.getQualifiedName() != null ? p.getQualifiedName() : p.getName()));
                }
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_callees
    // -------------------------------------------------------------------------

    public static class GetCallees implements McpTool {
        private final GraphService graphService;
        public GetCallees(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_callees"; }

        @Override
        public String getDescription() {
            return "Get all methods/functions called by a given method or function. Follows CALLS edges "
                    + "outbound. Use this to understand what a method depends on and which external "
                    + "systems or components it interacts with.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();

            Map<String, Object> id = new LinkedHashMap<>();
            id.put("type", "string");
            id.put("description", "The method/function element ID to find callees of.");
            props.put("id", id);

            Map<String, Object> limit = new LinkedHashMap<>();
            limit.put("type", "integer");
            limit.put("description", "Maximum number of callees to return (default 20).");
            limit.put("default", 20);
            props.put("limit", limit);

            schema.put("properties", props);
            schema.put("required", List.of("id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            int limit = ((Number) args.getOrDefault("limit", 20)).intValue();

            List<CodeElement> callees = graphService.getCallees(id);
            if (callees.isEmpty()) return "No callees found for: " + id;

            if (callees.size() > limit) callees = callees.subList(0, limit);

            Optional<CodeElement> self = graphService.getElementById(id);
            StringBuilder sb = new StringBuilder("## Methods Called by ");
            self.ifPresent(el -> sb.append("[").append(el.getElementType()).append("] ")
                    .append(el.getQualifiedName() != null ? el.getQualifiedName() : el.getName()));
            sb.append(" (showing ").append(callees.size()).append(")\n\n");

            for (int i = 0; i < callees.size(); i++) {
                CodeElement callee = callees.get(i);
                sb.append(i + 1).append(". ");
                SearchTools.appendElementSummary(sb, callee);
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_call_chain
    // -------------------------------------------------------------------------

    public static class GetCallChain implements McpTool {
        private final GraphService graphService;
        public GetCallChain(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_call_chain"; }

        @Override
        public String getDescription() {
            return "Find all call paths between two methods/functions using BFS traversal. Returns every "
                    + "possible call path from the source method to the target method within the given depth. "
                    + "Use this to understand how control flow reaches a specific method from a given entry point.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();

            Map<String, Object> fromId = new LinkedHashMap<>();
            fromId.put("type", "string");
            fromId.put("description", "The element ID of the starting (calling) method.");
            props.put("from_id", fromId);

            Map<String, Object> toId = new LinkedHashMap<>();
            toId.put("type", "string");
            toId.put("description", "The element ID of the target (called) method to trace a path to.");
            props.put("to_id", toId);

            Map<String, Object> maxDepth = new LinkedHashMap<>();
            maxDepth.put("type", "integer");
            maxDepth.put("description", "Maximum call chain depth to search (default 5).");
            maxDepth.put("default", 5);
            props.put("max_depth", maxDepth);

            schema.put("properties", props);
            schema.put("required", List.of("from_id", "to_id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String fromId = (String) args.get("from_id");
            String toId = (String) args.get("to_id");
            int maxDepth = ((Number) args.getOrDefault("max_depth", 5)).intValue();

            List<List<CodeElement>> chains = graphService.getCallChain(fromId, toId, maxDepth);
            if (chains.isEmpty()) {
                return "No call path found from " + fromId + " to " + toId
                        + " within depth " + maxDepth + ".";
            }

            StringBuilder sb = new StringBuilder("## Call Chains\n");
            sb.append("Found ").append(chains.size()).append(" path(s) from ").append(fromId)
              .append(" to ").append(toId).append(".\n\n");

            for (int c = 0; c < chains.size(); c++) {
                List<CodeElement> chain = chains.get(c);
                sb.append("### Path ").append(c + 1).append(" (").append(chain.size()).append(" steps)\n");
                for (int i = 0; i < chain.size(); i++) {
                    CodeElement el = chain.get(i);
                    sb.append("  ".repeat(i)).append(i == 0 ? "" : "-> ");
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
    }

    // -------------------------------------------------------------------------
    // get_call_hierarchy
    // -------------------------------------------------------------------------

    public static class GetCallHierarchy implements McpTool {
        private final GraphService graphService;
        public GetCallHierarchy(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_call_hierarchy"; }

        @Override
        public String getDescription() {
            return "Build a recursive call hierarchy tree for a method, showing either all callers (who calls "
                    + "this, and who calls them) or all callees (what this calls, and what those call). "
                    + "Displayed as an indented tree. Use this for comprehensive call graph analysis.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();

            Map<String, Object> id = new LinkedHashMap<>();
            id.put("type", "string");
            id.put("description", "The method/function element ID to build the hierarchy for.");
            props.put("id", id);

            Map<String, Object> direction = new LinkedHashMap<>();
            direction.put("type", "string");
            direction.put("enum", List.of("callers", "callees"));
            direction.put("description", "\"callees\" to show what this method calls (default), "
                    + "\"callers\" to show what calls this method.");
            direction.put("default", "callees");
            props.put("direction", direction);

            Map<String, Object> depth = new LinkedHashMap<>();
            depth.put("type", "integer");
            depth.put("description", "Maximum depth of the hierarchy to traverse (default 3).");
            depth.put("default", 3);
            props.put("depth", depth);

            schema.put("properties", props);
            schema.put("required", List.of("id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            String direction = (String) args.getOrDefault("direction", "callees");
            int depth = ((Number) args.getOrDefault("depth", 3)).intValue();

            Optional<CodeElement> root = graphService.getElementById(id);
            if (root.isEmpty()) return "Element not found: " + id;

            boolean isCallees = !"callers".equalsIgnoreCase(direction);

            StringBuilder sb = new StringBuilder("## Call Hierarchy (");
            sb.append(isCallees ? "callees" : "callers");
            sb.append(", depth=").append(depth).append(")\n\n");

            Set<String> visited = new HashSet<>();
            appendHierarchy(sb, root.get(), isCallees, depth, 0, visited);

            return sb.toString();
        }

        private void appendHierarchy(StringBuilder sb, CodeElement el, boolean isCallees,
                                      int maxDepth, int currentDepth, Set<String> visited) {
            sb.append("  ".repeat(currentDepth));
            if (currentDepth > 0) sb.append("-> ");
            sb.append("[").append(el.getElementType()).append("] ");
            sb.append(el.getQualifiedName() != null ? el.getQualifiedName() : el.getName());
            if (el.getFilePath() != null && el.getLineStart() > 0) {
                sb.append(" (").append(el.getFilePath()).append(":").append(el.getLineStart()).append(")");
            }
            sb.append("\n");

            if (currentDepth >= maxDepth) return;
            if (visited.contains(el.getId())) {
                sb.append("  ".repeat(currentDepth + 1)).append("[...recursive, stopping]\n");
                return;
            }
            visited.add(el.getId());

            List<CodeElement> next = isCallees
                    ? graphService.getCallees(el.getId())
                    : graphService.getCallers(el.getId());

            for (CodeElement neighbor : next) {
                appendHierarchy(sb, neighbor, isCallees, maxDepth, currentDepth + 1, new HashSet<>(visited));
            }
        }
    }
}
