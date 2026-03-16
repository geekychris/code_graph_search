package com.codegraph.mcp.tools;

import com.codegraph.core.model.*;
import com.codegraph.core.service.GraphService;
import com.codegraph.mcp.McpTool;

import java.util.*;

/**
 * Tools for navigating type hierarchies: inheritance, interfaces, and method overrides.
 */
public class TypeHierarchyTools {

    // -------------------------------------------------------------------------
    // get_superclass
    // -------------------------------------------------------------------------

    public static class GetSuperclass implements McpTool {
        private final GraphService graphService;
        public GetSuperclass(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_superclass"; }

        @Override
        public String getDescription() {
            return "Get the direct superclass (parent class) of a class element. Follows the EXTENDS edge "
                    + "in the type hierarchy. Returns null/not-found if the class has no explicit superclass "
                    + "or extends only Object.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return buildIdSchema("The class element ID to get the superclass of.");
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            Optional<CodeElement> superclass = graphService.getSuperclass(id);
            if (superclass.isEmpty()) return "No superclass found for element: " + id;

            StringBuilder sb = new StringBuilder("## Superclass\n\n");
            SearchTools.appendElementSummary(sb, superclass.get());
            sb.append("\n\n**Element ID:** ").append(superclass.get().getId());
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_interfaces
    // -------------------------------------------------------------------------

    public static class GetInterfaces implements McpTool {
        private final GraphService graphService;
        public GetInterfaces(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_interfaces"; }

        @Override
        public String getDescription() {
            return "Get all interfaces/traits/protocols implemented by a class element. Follows IMPLEMENTS "
                    + "and MIXES_IN edges. Use this to understand the contracts a class fulfills.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return buildIdSchema("The class element ID to get implemented interfaces for.");
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            List<CodeElement> interfaces = graphService.getInterfaces(id);
            if (interfaces.isEmpty()) return "No interfaces found for element: " + id;

            StringBuilder sb = new StringBuilder("## Implemented Interfaces (").append(interfaces.size()).append(")\n\n");
            for (CodeElement iface : interfaces) {
                sb.append("- ");
                SearchTools.appendElementSummary(sb, iface);
                sb.append("\n  ID: ").append(iface.getId()).append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_subclasses
    // -------------------------------------------------------------------------

    public static class GetSubclasses implements McpTool {
        private final GraphService graphService;
        public GetSubclasses(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_subclasses"; }

        @Override
        public String getDescription() {
            return "Get all classes that directly extend a given class. Finds the inverse of the EXTENDS "
                    + "relationship. Use this to understand the class hierarchy below a given type, or to "
                    + "find all implementations of an abstract class.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return buildIdSchema("The class element ID to find subclasses of.");
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            List<CodeElement> subclasses = graphService.getSubclasses(id);
            if (subclasses.isEmpty()) return "No subclasses found for element: " + id;

            StringBuilder sb = new StringBuilder("## Subclasses (").append(subclasses.size()).append(")\n\n");
            for (CodeElement sub : subclasses) {
                sb.append("- ");
                SearchTools.appendElementSummary(sb, sub);
                sb.append("\n  ID: ").append(sub.getId()).append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_implementors
    // -------------------------------------------------------------------------

    public static class GetImplementors implements McpTool {
        private final GraphService graphService;
        public GetImplementors(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_implementors"; }

        @Override
        public String getDescription() {
            return "Get all classes/structs that implement a given interface or trait. Finds the inverse of "
                    + "the IMPLEMENTS relationship. Use this to find all concrete implementations of an "
                    + "interface — essential for understanding polymorphism and dependency injection patterns.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return buildIdSchema("The interface or trait element ID to find implementors of.");
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            List<CodeElement> implementors = graphService.getImplementors(id);
            if (implementors.isEmpty()) return "No implementors found for element: " + id;

            StringBuilder sb = new StringBuilder("## Implementors (").append(implementors.size()).append(")\n\n");
            for (CodeElement impl : implementors) {
                sb.append("- ");
                SearchTools.appendElementSummary(sb, impl);
                sb.append("\n  ID: ").append(impl.getId()).append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_overrides
    // -------------------------------------------------------------------------

    public static class GetOverrides implements McpTool {
        private final GraphService graphService;
        public GetOverrides(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_overrides"; }

        @Override
        public String getDescription() {
            return "Get the method that a given method overrides in its superclass or interface. Follows the "
                    + "OVERRIDES edge. Use this to find the original/parent declaration of an overridden method.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return buildIdSchema("The method element ID to find the overridden method for.");
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            Optional<CodeElement> overridden = graphService.getOverriddenMethod(id);
            if (overridden.isEmpty()) return "Method does not override any method, or not found: " + id;

            StringBuilder sb = new StringBuilder("## Overridden Method\n\n");
            SearchTools.appendElementSummary(sb, overridden.get());
            sb.append("\n\n**Element ID:** ").append(overridden.get().getId());
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_override_chain
    // -------------------------------------------------------------------------

    public static class GetOverrideChain implements McpTool {
        private final GraphService graphService;
        public GetOverrideChain(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_override_chain"; }

        @Override
        public String getDescription() {
            return "Get the full override chain for a method by recursively following OVERRIDES edges. "
                    + "Shows the complete inheritance path of method declarations from the deepest override "
                    + "up to the original declaration in the topmost superclass or interface.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return buildIdSchema("The method element ID to trace the override chain for.");
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");

            List<CodeElement> chain = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            String current = id;

            while (current != null && !visited.contains(current)) {
                visited.add(current);
                Optional<CodeElement> opt = graphService.getElementById(current);
                if (opt.isEmpty()) break;
                chain.add(opt.get());
                Optional<CodeElement> overridden = graphService.getOverriddenMethod(current);
                current = overridden.map(CodeElement::getId).orElse(null);
            }

            if (chain.isEmpty()) return "Element not found: " + id;
            if (chain.size() == 1) return "Method does not override any other method: " + id;

            StringBuilder sb = new StringBuilder("## Override Chain (").append(chain.size()).append(" levels)\n\n");
            for (int i = 0; i < chain.size(); i++) {
                CodeElement el = chain.get(i);
                sb.append(i == 0 ? "CURRENT: " : "OVERRIDES: ");
                SearchTools.appendElementSummary(sb, el);
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_overriders
    // -------------------------------------------------------------------------

    public static class GetOverriders implements McpTool {
        private final GraphService graphService;
        public GetOverriders(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_overriders"; }

        @Override
        public String getDescription() {
            return "Get all methods that override a given method in subclasses. Finds the inverse of the "
                    + "OVERRIDES relationship. Use this to find all concrete implementations of an abstract "
                    + "or interface method.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return buildIdSchema("The method element ID to find overriders of.");
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            List<CodeElement> overriders = graphService.getOverriders(id);
            if (overriders.isEmpty()) return "No overriding methods found for: " + id;

            StringBuilder sb = new StringBuilder("## Methods Overriding This (").append(overriders.size()).append(")\n\n");
            for (CodeElement m : overriders) {
                sb.append("- ");
                SearchTools.appendElementSummary(sb, m);
                sb.append("\n  ID: ").append(m.getId()).append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // Shared helper
    // -------------------------------------------------------------------------

    private static Map<String, Object> buildIdSchema(String idDescription) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> id = new LinkedHashMap<>();
        id.put("type", "string");
        id.put("description", idDescription);
        props.put("id", id);
        schema.put("properties", props);
        schema.put("required", List.of("id"));
        return schema;
    }
}
