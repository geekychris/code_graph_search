package com.codegraph.mcp.tools;

import com.codegraph.core.model.*;
import com.codegraph.core.service.GraphService;
import com.codegraph.mcp.McpTool;

import java.util.*;

/**
 * Tools for retrieving individual code elements and file-level views.
 */
public class ElementTools {

    // -------------------------------------------------------------------------
    // get_element
    // -------------------------------------------------------------------------

    public static class GetElement implements McpTool {

        private final GraphService graphService;

        public GetElement(GraphService graphService) {
            this.graphService = graphService;
        }

        @Override
        public String getName() { return "get_element"; }

        @Override
        public String getDescription() {
            return "Retrieve the full details of a specific code element by its ID. Returns all available "
                    + "information including type, qualified name, file location, visibility, modifiers, "
                    + "return type, parameter types, doc comment, and full code snippet. Use this after "
                    + "finding an element's ID through search to get complete information about it.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            Map<String, Object> id = new LinkedHashMap<>();
            id.put("type", "string");
            id.put("description", "The element ID (obtained from search results or other tool responses).");
            props.put("id", id);
            schema.put("properties", props);
            schema.put("required", List.of("id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            Optional<CodeElement> opt = graphService.getElementById(id);
            if (opt.isEmpty()) return "Element not found: " + id;
            return formatElementFull(opt.get());
        }
    }

    // -------------------------------------------------------------------------
    // get_snippet
    // -------------------------------------------------------------------------

    public static class GetSnippet implements McpTool {

        private final GraphService graphService;

        public GetSnippet(GraphService graphService) {
            this.graphService = graphService;
        }

        @Override
        public String getName() { return "get_snippet"; }

        @Override
        public String getDescription() {
            return "Retrieve the source code snippet for an element, formatted with line numbers and file path. "
                    + "The snippet includes the element's own stored code. Use this to read the actual "
                    + "implementation of a method, class body, or other code element. Always shows the file "
                    + "path and line range for easy reference.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();

            Map<String, Object> id = new LinkedHashMap<>();
            id.put("type", "string");
            id.put("description", "The element ID whose snippet to retrieve.");
            props.put("id", id);

            Map<String, Object> contextLines = new LinkedHashMap<>();
            contextLines.put("type", "integer");
            contextLines.put("description", "Number of context lines to show around the snippet (default 5, informational only).");
            contextLines.put("default", 5);
            props.put("context_lines", contextLines);

            schema.put("properties", props);
            schema.put("required", List.of("id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            Optional<CodeElement> opt = graphService.getElementById(id);
            if (opt.isEmpty()) return "Element not found: " + id;

            CodeElement el = opt.get();
            StringBuilder sb = new StringBuilder();
            sb.append("FILE: ").append(el.getFilePath() != null ? el.getFilePath() : "(unknown)").append("\n");
            if (el.getLineStart() > 0) {
                sb.append("LINES: ").append(el.getLineStart());
                if (el.getLineEnd() > el.getLineStart()) {
                    sb.append("-").append(el.getLineEnd());
                }
                sb.append("\n");
            }
            sb.append("ELEMENT: [").append(el.getElementType()).append("] ");
            sb.append(el.getQualifiedName() != null ? el.getQualifiedName() : el.getName()).append("\n");

            if (el.getSnippet() != null && !el.getSnippet().isBlank()) {
                String lang = el.getLanguage() != null ? el.getLanguage().id : "";
                sb.append("```").append(lang).append("\n");
                // Add line numbers to snippet
                String[] lines = el.getSnippet().split("\n", -1);
                int startLine = el.getLineStart() > 0 ? el.getLineStart() : 1;
                for (int i = 0; i < lines.length; i++) {
                    sb.append(String.format("%4d | %s%n", startLine + i, lines[i]));
                }
                sb.append("```\n");
            } else {
                sb.append("(No snippet available for this element)\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_file_outline
    // -------------------------------------------------------------------------

    public static class GetFileOutline implements McpTool {

        private final GraphService graphService;

        public GetFileOutline(GraphService graphService) {
            this.graphService = graphService;
        }

        @Override
        public String getName() { return "get_file_outline"; }

        @Override
        public String getDescription() {
            return "Get a structured outline of all code elements in a file, displayed as a hierarchical tree. "
                    + "Shows element type, name, line range, and visibility for each element. Useful for "
                    + "understanding the overall structure of a file without reading every line. Use this "
                    + "before diving into specific methods to understand the class/file organization.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();

            Map<String, Object> filePath = new LinkedHashMap<>();
            filePath.put("type", "string");
            filePath.put("description", "The file path (relative to repo root) to outline.");
            props.put("file_path", filePath);

            Map<String, Object> repoId = new LinkedHashMap<>();
            repoId.put("type", "string");
            repoId.put("description", "The repository ID containing the file.");
            props.put("repo_id", repoId);

            schema.put("properties", props);
            schema.put("required", List.of("file_path", "repo_id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String filePath = (String) args.get("file_path");
            String repoId = (String) args.get("repo_id");

            List<CodeElement> elements = graphService.getElementsByFile(repoId, filePath);
            if (elements.isEmpty()) {
                return "No elements found in file: " + filePath + " (repo: " + repoId + ")";
            }

            // Sort by line start, stable
            elements = new ArrayList<>(elements);
            elements.sort(Comparator.comparingInt(CodeElement::getLineStart));

            StringBuilder sb = new StringBuilder();
            sb.append("## File Outline: ").append(filePath).append("\n");
            sb.append("Repository: ").append(repoId).append("\n");
            sb.append("Total elements: ").append(elements.size()).append("\n\n");

            // Build parent -> children map for hierarchy
            Map<String, List<CodeElement>> childrenMap = new LinkedHashMap<>();
            List<CodeElement> roots = new ArrayList<>();
            for (CodeElement el : elements) {
                if (el.getParentId() == null) {
                    roots.add(el);
                } else {
                    childrenMap.computeIfAbsent(el.getParentId(), k -> new ArrayList<>()).add(el);
                }
            }

            for (CodeElement root : roots) {
                appendOutlineNode(sb, root, childrenMap, 0);
            }

            return sb.toString();
        }

        private void appendOutlineNode(StringBuilder sb, CodeElement el,
                                       Map<String, List<CodeElement>> childrenMap, int depth) {
            sb.append("  ".repeat(depth));
            sb.append("[").append(el.getElementType()).append("] ");
            if (el.getVisibility() != null) sb.append(el.getVisibility()).append(" ");
            sb.append(el.getName() != null ? el.getName() : "(unnamed)");
            if (el.getSignature() != null) sb.append(el.getSignature());
            if (el.getLineStart() > 0) {
                sb.append("  (line ").append(el.getLineStart());
                if (el.getLineEnd() > el.getLineStart()) sb.append("-").append(el.getLineEnd());
                sb.append(")");
            }
            sb.append("\n");

            List<CodeElement> children = childrenMap.get(el.getId());
            if (children != null) {
                children.sort(Comparator.comparingInt(CodeElement::getLineStart));
                for (CodeElement child : children) {
                    appendOutlineNode(sb, child, childrenMap, depth + 1);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // get_elements_at_location
    // -------------------------------------------------------------------------

    public static class GetElementsAtLocation implements McpTool {

        private final GraphService graphService;

        public GetElementsAtLocation(GraphService graphService) {
            this.graphService = graphService;
        }

        @Override
        public String getName() { return "get_elements_at_location"; }

        @Override
        public String getDescription() {
            return "Find all code elements whose line range contains a specific line number in a file. "
                    + "Returns elements ordered from innermost (most specific) to outermost (e.g., a method "
                    + "before its class before its file). Useful for determining what code construct exists "
                    + "at a specific location, such as when reading a stack trace or error message with line numbers.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();

            Map<String, Object> filePath = new LinkedHashMap<>();
            filePath.put("type", "string");
            filePath.put("description", "The file path (relative to repo root).");
            props.put("file_path", filePath);

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("type", "integer");
            line.put("description", "The line number to look up (1-based).");
            props.put("line", line);

            Map<String, Object> repoId = new LinkedHashMap<>();
            repoId.put("type", "string");
            repoId.put("description", "The repository ID containing the file.");
            props.put("repo_id", repoId);

            schema.put("properties", props);
            schema.put("required", List.of("file_path", "line", "repo_id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String filePath = (String) args.get("file_path");
            int line = ((Number) args.get("line")).intValue();
            String repoId = (String) args.get("repo_id");

            List<CodeElement> allElements = graphService.getElementsByFile(repoId, filePath);
            if (allElements.isEmpty()) {
                return "No elements found in file: " + filePath + " (repo: " + repoId + ")";
            }

            // Filter to elements whose range contains the line
            List<CodeElement> matching = new ArrayList<>();
            for (CodeElement el : allElements) {
                if (el.getLineStart() > 0 && el.getLineStart() <= line
                        && (el.getLineEnd() <= 0 || el.getLineEnd() >= line)) {
                    matching.add(el);
                }
            }

            if (matching.isEmpty()) {
                return "No elements found at line " + line + " in " + filePath;
            }

            // Sort by specificity: smallest span first (innermost)
            matching.sort(Comparator.comparingInt(el -> {
                int span = el.getLineEnd() - el.getLineStart();
                return span < 0 ? Integer.MAX_VALUE : span;
            }));

            StringBuilder sb = new StringBuilder();
            sb.append("## Elements at ").append(filePath).append(":").append(line).append("\n\n");
            for (int i = 0; i < matching.size(); i++) {
                CodeElement el = matching.get(i);
                sb.append(i + 1).append(". ");
                SearchTools.appendElementSummary(sb, el);
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // Shared formatter
    // -------------------------------------------------------------------------

    public static String formatElementFull(CodeElement el) {
        StringBuilder sb = new StringBuilder();
        sb.append("## [").append(el.getElementType()).append("] ");
        sb.append(el.getQualifiedName() != null ? el.getQualifiedName() : el.getName()).append("\n\n");

        sb.append("**ID:** ").append(el.getId()).append("\n");
        sb.append("**Repository:** ").append(el.getRepoId()).append("\n");
        if (el.getLanguage() != null) sb.append("**Language:** ").append(el.getLanguage()).append("\n");
        if (el.getFilePath() != null) {
            sb.append("**File:** ").append(el.getFilePath());
            if (el.getLineStart() > 0) {
                sb.append(" (lines ").append(el.getLineStart());
                if (el.getLineEnd() > el.getLineStart()) sb.append("-").append(el.getLineEnd());
                sb.append(")");
            }
            sb.append("\n");
        }
        if (el.getVisibility() != null) sb.append("**Visibility:** ").append(el.getVisibility()).append("\n");
        if (el.getModifiers() != null && !el.getModifiers().isEmpty()) {
            sb.append("**Modifiers:** ").append(String.join(", ", el.getModifiers())).append("\n");
        }
        if (el.getSignature() != null) sb.append("**Signature:** ").append(el.getSignature()).append("\n");
        if (el.getReturnType() != null) sb.append("**Return type:** ").append(el.getReturnType()).append("\n");
        if (el.getParameterTypes() != null && !el.getParameterTypes().isEmpty()) {
            sb.append("**Parameter types:** ").append(String.join(", ", el.getParameterTypes())).append("\n");
        }
        if (el.getParentId() != null) sb.append("**Parent ID:** ").append(el.getParentId()).append("\n");

        if (el.getDocComment() != null && !el.getDocComment().isBlank()) {
            sb.append("\n**Documentation:**\n").append(el.getDocComment().strip()).append("\n");
        }

        if (el.getMetadata() != null && !el.getMetadata().isEmpty()) {
            sb.append("\n**Metadata:**\n");
            el.getMetadata().forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
        }

        if (el.getSnippet() != null && !el.getSnippet().isBlank()) {
            String lang = el.getLanguage() != null ? el.getLanguage().id : "";
            sb.append("\n**Source:**\n```").append(lang).append("\n");
            String[] lines = el.getSnippet().split("\n", -1);
            int startLine = el.getLineStart() > 0 ? el.getLineStart() : 1;
            for (int i = 0; i < lines.length; i++) {
                sb.append(String.format("%4d | %s%n", startLine + i, lines[i]));
            }
            sb.append("```\n");
        }

        return sb.toString();
    }
}
