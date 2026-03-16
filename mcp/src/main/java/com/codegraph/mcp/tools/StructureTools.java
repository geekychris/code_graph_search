package com.codegraph.mcp.tools;

import com.codegraph.core.model.*;
import com.codegraph.core.service.GraphService;
import com.codegraph.mcp.McpTool;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Tools for navigating the structural containment hierarchy of the code graph.
 */
public class StructureTools {

    // -------------------------------------------------------------------------
    // get_parent
    // -------------------------------------------------------------------------

    public static class GetParent implements McpTool {
        private final GraphService graphService;
        public GetParent(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_parent"; }

        @Override
        public String getDescription() {
            return "Get the immediate parent (containing element) of a code element. For a method, this is the "
                    + "class. For a class, this is the package or file. For a field, this is its class. "
                    + "Use this to understand what a code element belongs to.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return buildIdSchema("The element ID to find the parent of.");
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            Optional<CodeElement> parent = graphService.getParent(id);
            if (parent.isEmpty()) return "No parent found for element: " + id;
            StringBuilder sb = new StringBuilder("## Parent Element\n\n");
            SearchTools.appendElementSummary(sb, parent.get());
            sb.append("\n\n**Element ID:** ").append(parent.get().getId());
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_ancestors
    // -------------------------------------------------------------------------

    public static class GetAncestors implements McpTool {
        private final GraphService graphService;
        public GetAncestors(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_ancestors"; }

        @Override
        public String getDescription() {
            return "Get the full ancestor chain of a code element, from its immediate parent up to the repo root. "
                    + "Returns the containment hierarchy: method -> class -> package -> file -> repo. "
                    + "Use this to understand the full qualified context of an element.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return buildIdSchema("The element ID to trace ancestors for.");
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            List<CodeElement> ancestors = graphService.getAncestors(id);
            if (ancestors.isEmpty()) return "No ancestors found for element: " + id;

            StringBuilder sb = new StringBuilder("## Ancestor Chain\n\n");
            String indent = "";
            for (int i = 0; i < ancestors.size(); i++) {
                CodeElement el = ancestors.get(i);
                sb.append(indent).append(i == 0 ? "PARENT: " : "       ");
                sb.append("[").append(el.getElementType()).append("] ");
                sb.append(el.getQualifiedName() != null ? el.getQualifiedName() : el.getName());
                if (el.getFilePath() != null && el.getLineStart() > 0) {
                    sb.append(" (").append(el.getFilePath()).append(":").append(el.getLineStart()).append(")");
                }
                sb.append("\n");
                indent += "  ";
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_children
    // -------------------------------------------------------------------------

    public static class GetChildren implements McpTool {
        private final GraphService graphService;
        public GetChildren(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_children"; }

        @Override
        public String getDescription() {
            return "Get all direct children of a code element, optionally filtered by element type. "
                    + "For a class, returns methods, fields, constructors, etc. For a package, returns "
                    + "classes and sub-packages. Results are grouped by element type for easy navigation.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();

            Map<String, Object> id = new LinkedHashMap<>();
            id.put("type", "string");
            id.put("description", "The element ID to get children of.");
            props.put("id", id);

            Map<String, Object> type = new LinkedHashMap<>();
            type.put("type", "string");
            type.put("description", "Optional ElementType filter (e.g. METHOD, FIELD, CLASS, CONSTRUCTOR). "
                    + "If omitted, all children are returned.");
            props.put("type", type);

            schema.put("properties", props);
            schema.put("required", List.of("id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            String typeStr = (String) args.get("type");

            List<CodeElement> children;
            if (typeStr != null) {
                try {
                    ElementType et = ElementType.valueOf(typeStr.toUpperCase());
                    children = graphService.getChildren(id, et);
                } catch (IllegalArgumentException e) {
                    return "Unknown element type: " + typeStr;
                }
            } else {
                children = graphService.getChildren(id);
            }

            if (children.isEmpty()) return "No children found for element: " + id;

            children = new ArrayList<>(children);
            children.sort(Comparator.comparingInt(CodeElement::getLineStart));

            // Group by type
            Map<ElementType, List<CodeElement>> grouped = new LinkedHashMap<>();
            for (CodeElement el : children) {
                grouped.computeIfAbsent(el.getElementType(), k -> new ArrayList<>()).add(el);
            }

            StringBuilder sb = new StringBuilder("## Children of ");
            sb.append(id).append(" (").append(children.size()).append(" total)\n\n");

            for (Map.Entry<ElementType, List<CodeElement>> entry : grouped.entrySet()) {
                sb.append("### ").append(entry.getKey()).append(" (").append(entry.getValue().size()).append(")\n");
                for (CodeElement el : entry.getValue()) {
                    sb.append("- ");
                    SearchTools.appendElementSummary(sb, el);
                    sb.append("\n");
                }
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_siblings
    // -------------------------------------------------------------------------

    public static class GetSiblings implements McpTool {
        private final GraphService graphService;
        public GetSiblings(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_siblings"; }

        @Override
        public String getDescription() {
            return "Get all sibling elements (other elements in the same container) sorted by line number. "
                    + "The element you passed in is marked [CURRENT] in the output. Use this to see what "
                    + "other methods/fields are alongside a given method in a class.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return buildIdSchema("The element ID to find siblings of.");
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            List<CodeElement> siblings = graphService.getSiblings(id);
            if (siblings.isEmpty()) return "No siblings found for element: " + id;

            List<CodeElement> sorted = new ArrayList<>(siblings);
            sorted.sort(Comparator.comparingInt(CodeElement::getLineStart));

            StringBuilder sb = new StringBuilder("## Siblings\n\n");
            for (CodeElement el : sorted) {
                boolean isCurrent = id.equals(el.getId());
                sb.append(isCurrent ? "[CURRENT] " : "          ");
                SearchTools.appendElementSummary(sb, el);
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_methods
    // -------------------------------------------------------------------------

    public static class GetMethods implements McpTool {
        private final GraphService graphService;
        public GetMethods(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_methods"; }

        @Override
        public String getDescription() {
            return "Get all methods, functions, and constructors of a class, interface, struct, or trait element. "
                    + "Returns full signatures, visibility, modifiers, and line numbers. Use this to quickly "
                    + "see what operations a class exposes without reading the full source.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return buildIdSchema("The class/interface/struct element ID to list methods for.");
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            List<CodeElement> methods = new ArrayList<>();
            methods.addAll(graphService.getChildren(id, ElementType.METHOD));
            methods.addAll(graphService.getChildren(id, ElementType.FUNCTION));
            methods.addAll(graphService.getChildren(id, ElementType.CONSTRUCTOR));
            methods.sort(Comparator.comparingInt(CodeElement::getLineStart));

            if (methods.isEmpty()) return "No methods/functions/constructors found for element: " + id;

            // Get the class itself for context
            Optional<CodeElement> self = graphService.getElementById(id);

            StringBuilder sb = new StringBuilder("## Methods");
            self.ifPresent(el -> sb.append(" of [").append(el.getElementType()).append("] ").append(el.getQualifiedName() != null ? el.getQualifiedName() : el.getName()));
            sb.append("\n\n");

            for (CodeElement m : methods) {
                sb.append("**[").append(m.getElementType()).append("]** ");
                if (m.getVisibility() != null) sb.append(m.getVisibility()).append(" ");
                if (m.getModifiers() != null) sb.append(String.join(" ", m.getModifiers())).append(" ");
                if (m.getReturnType() != null) sb.append(m.getReturnType()).append(" ");
                sb.append(m.getName() != null ? m.getName() : "(unnamed)");
                if (m.getSignature() != null) sb.append(m.getSignature());
                if (m.getLineStart() > 0) {
                    sb.append("  (line ").append(m.getLineStart());
                    if (m.getLineEnd() > m.getLineStart()) sb.append("-").append(m.getLineEnd());
                    sb.append(")");
                }
                sb.append("\n  ID: ").append(m.getId()).append("\n");
                if (m.getDocComment() != null && !m.getDocComment().isBlank()) {
                    String doc = m.getDocComment().strip();
                    if (doc.length() > 120) doc = doc.substring(0, 120) + "...";
                    sb.append("  Doc: ").append(doc).append("\n");
                }
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_fields
    // -------------------------------------------------------------------------

    public static class GetFields implements McpTool {
        private final GraphService graphService;
        public GetFields(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_fields"; }

        @Override
        public String getDescription() {
            return "Get all fields and properties of a class or struct element, including their types, "
                    + "visibility, and modifiers. Use this to understand the data model of a class.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return buildIdSchema("The class/struct element ID to list fields for.");
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            List<CodeElement> fields = new ArrayList<>();
            fields.addAll(graphService.getChildren(id, ElementType.FIELD));
            fields.addAll(graphService.getChildren(id, ElementType.PROPERTY));
            fields.sort(Comparator.comparingInt(CodeElement::getLineStart));

            if (fields.isEmpty()) return "No fields or properties found for element: " + id;

            Optional<CodeElement> self = graphService.getElementById(id);

            StringBuilder sb = new StringBuilder("## Fields");
            self.ifPresent(el -> sb.append(" of [").append(el.getElementType()).append("] ")
                    .append(el.getQualifiedName() != null ? el.getQualifiedName() : el.getName()));
            sb.append("\n\n");

            for (CodeElement f : fields) {
                sb.append("**[").append(f.getElementType()).append("]** ");
                if (f.getVisibility() != null) sb.append(f.getVisibility()).append(" ");
                if (f.getModifiers() != null && !f.getModifiers().isEmpty()) {
                    sb.append(String.join(" ", f.getModifiers())).append(" ");
                }
                if (f.getReturnType() != null) sb.append(f.getReturnType()).append(" ");
                sb.append(f.getName() != null ? f.getName() : "(unnamed)");
                if (f.getLineStart() > 0) sb.append("  (line ").append(f.getLineStart()).append(")");
                sb.append("\n  ID: ").append(f.getId()).append("\n\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_constructors
    // -------------------------------------------------------------------------

    public static class GetConstructors implements McpTool {
        private final GraphService graphService;
        public GetConstructors(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_constructors"; }

        @Override
        public String getDescription() {
            return "Get all constructors of a class element. Returns each constructor's signature, "
                    + "visibility, parameter types, and line number.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return buildIdSchema("The class element ID to list constructors for.");
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            List<CodeElement> ctors = graphService.getChildren(id, ElementType.CONSTRUCTOR);
            ctors = new ArrayList<>(ctors);
            ctors.sort(Comparator.comparingInt(CodeElement::getLineStart));

            if (ctors.isEmpty()) return "No constructors found for element: " + id;

            Optional<CodeElement> self = graphService.getElementById(id);

            StringBuilder sb = new StringBuilder("## Constructors");
            self.ifPresent(el -> sb.append(" of ").append(el.getQualifiedName() != null ? el.getQualifiedName() : el.getName()));
            sb.append("\n\n");

            for (CodeElement c : ctors) {
                sb.append("**[CONSTRUCTOR]** ");
                if (c.getVisibility() != null) sb.append(c.getVisibility()).append(" ");
                sb.append(c.getName() != null ? c.getName() : "(unnamed)");
                if (c.getSignature() != null) sb.append(c.getSignature());
                if (c.getParameterTypes() != null && !c.getParameterTypes().isEmpty()) {
                    sb.append("\n  Parameters: ").append(String.join(", ", c.getParameterTypes()));
                }
                if (c.getLineStart() > 0) {
                    sb.append("  (line ").append(c.getLineStart());
                    if (c.getLineEnd() > c.getLineStart()) sb.append("-").append(c.getLineEnd());
                    sb.append(")");
                }
                sb.append("\n  ID: ").append(c.getId()).append("\n\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // Shared helpers
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
