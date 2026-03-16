package com.codegraph.mcp.tools;

import com.codegraph.core.model.*;
import com.codegraph.core.service.GraphService;
import com.codegraph.mcp.McpTool;

import java.util.*;

/**
 * Tools for retrieving documentation, annotations, parameters, and doc comments.
 */
public class DocumentationTools {

    // -------------------------------------------------------------------------
    // get_comments
    // -------------------------------------------------------------------------

    public static class GetComments implements McpTool {
        private final GraphService graphService;
        public GetComments(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_comments"; }

        @Override
        public String getDescription() {
            return "Get all comments and doc strings associated with a code element. For methods, also "
                    + "retrieves comments from the enclosing class. Returns COMMENT_LINE, COMMENT_BLOCK, "
                    + "and COMMENT_DOC elements. Use this when you need the human-written explanations "
                    + "for a piece of code.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return buildIdSchema("The element ID to get comments for.");
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            List<CodeElement> comments = graphService.getComments(id);

            // Also get comments for parent if element is a method
            Optional<CodeElement> self = graphService.getElementById(id);
            List<CodeElement> parentComments = new ArrayList<>();
            if (self.isPresent() && self.get().getParentId() != null) {
                ElementType et = self.get().getElementType();
                if (et == ElementType.METHOD || et == ElementType.FUNCTION || et == ElementType.CONSTRUCTOR) {
                    parentComments = graphService.getComments(self.get().getParentId());
                }
            }

            if (comments.isEmpty() && parentComments.isEmpty()) {
                return "No comments found for element: " + id;
            }

            StringBuilder sb = new StringBuilder("## Comments\n\n");

            if (!comments.isEmpty()) {
                sb.append("### Element Comments\n");
                for (CodeElement c : comments) {
                    sb.append("**[").append(c.getElementType()).append("]**");
                    if (c.getFilePath() != null && c.getLineStart() > 0) {
                        sb.append(" (").append(c.getFilePath()).append(":").append(c.getLineStart()).append(")");
                    }
                    sb.append("\n");
                    if (c.getSnippet() != null) sb.append(c.getSnippet()).append("\n");
                    else if (c.getDocComment() != null) sb.append(c.getDocComment()).append("\n");
                    sb.append("\n");
                }
            }

            if (!parentComments.isEmpty()) {
                sb.append("### Class-level Comments\n");
                for (CodeElement c : parentComments) {
                    sb.append("**[").append(c.getElementType()).append("]**");
                    if (c.getFilePath() != null && c.getLineStart() > 0) {
                        sb.append(" (").append(c.getFilePath()).append(":").append(c.getLineStart()).append(")");
                    }
                    sb.append("\n");
                    if (c.getSnippet() != null) sb.append(c.getSnippet()).append("\n");
                    else if (c.getDocComment() != null) sb.append(c.getDocComment()).append("\n");
                    sb.append("\n");
                }
            }

            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_annotations
    // -------------------------------------------------------------------------

    public static class GetAnnotations implements McpTool {
        private final GraphService graphService;
        public GetAnnotations(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_annotations"; }

        @Override
        public String getDescription() {
            return "Get all annotations, decorators, and attributes on a code element. Returns ANNOTATION, "
                    + "DECORATOR, and ATTRIBUTE elements. Use this to understand framework-level metadata "
                    + "on classes and methods (e.g., Spring @Controller, JUnit @Test, Rust #[derive]).";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return buildIdSchema("The element ID to get annotations for.");
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            List<CodeElement> annotations = graphService.getAnnotations(id);
            if (annotations.isEmpty()) return "No annotations found for element: " + id;

            StringBuilder sb = new StringBuilder("## Annotations (").append(annotations.size()).append(")\n\n");
            for (CodeElement a : annotations) {
                sb.append("**[").append(a.getElementType()).append("]** ");
                sb.append(a.getName() != null ? a.getName() : a.getQualifiedName());
                if (a.getFilePath() != null && a.getLineStart() > 0) {
                    sb.append(" (").append(a.getFilePath()).append(":").append(a.getLineStart()).append(")");
                }
                sb.append("\n");
                if (a.getSnippet() != null && !a.getSnippet().isBlank()) {
                    sb.append("  `").append(a.getSnippet().strip()).append("`\n");
                }
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_parameters
    // -------------------------------------------------------------------------

    public static class GetParameters implements McpTool {
        private final GraphService graphService;
        public GetParameters(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_parameters"; }

        @Override
        public String getDescription() {
            return "Get all parameters of a method, function, or constructor. Returns PARAMETER elements "
                    + "with their names, types, and positions. Use this to understand a method's signature "
                    + "in detail, especially when the signature string is not sufficient.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return buildIdSchema("The method/function/constructor element ID to get parameters for.");
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            List<CodeElement> params = graphService.getChildren(id, ElementType.PARAMETER);

            if (params.isEmpty()) {
                // Fall back to parameterTypes on the element itself
                Optional<CodeElement> self = graphService.getElementById(id);
                if (self.isEmpty()) return "Element not found: " + id;
                CodeElement el = self.get();
                if (el.getParameterTypes() != null && !el.getParameterTypes().isEmpty()) {
                    return "## Parameters (from type list)\n\n"
                            + String.join(", ", el.getParameterTypes()) + "\n";
                }
                return "No parameters found for element: " + id;
            }

            params = new ArrayList<>(params);
            params.sort(Comparator.comparingInt(CodeElement::getLineStart));

            Optional<CodeElement> self = graphService.getElementById(id);
            StringBuilder sb = new StringBuilder("## Parameters of ");
            self.ifPresent(el -> sb.append(el.getQualifiedName() != null ? el.getQualifiedName() : el.getName()));
            sb.append("\n\n");

            for (int i = 0; i < params.size(); i++) {
                CodeElement p = params.get(i);
                sb.append(i + 1).append(". **").append(p.getName() != null ? p.getName() : "(unnamed)").append("**");
                if (p.getReturnType() != null) sb.append(": ").append(p.getReturnType());
                if (p.getModifiers() != null && !p.getModifiers().isEmpty()) {
                    sb.append(" [").append(String.join(", ", p.getModifiers())).append("]");
                }
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_readme
    // -------------------------------------------------------------------------

    public static class GetReadme implements McpTool {
        private final GraphService graphService;
        public GetReadme(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_readme"; }

        @Override
        public String getDescription() {
            return "Retrieve the README or markdown documentation for a repository or one of its subdirectories. "
                    + "Returns the content of README.md or similar markdown files, formatted for display. "
                    + "Use this to understand the purpose and usage of a repository or module.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();

            Map<String, Object> repoId = new LinkedHashMap<>();
            repoId.put("type", "string");
            repoId.put("description", "The repository ID.");
            props.put("repo_id", repoId);

            Map<String, Object> directory = new LinkedHashMap<>();
            directory.put("type", "string");
            directory.put("description", "Optional relative path within the repo to look for README. "
                    + "Defaults to repo root. Example: \"src/main\" or \"docs\".");
            props.put("directory", directory);

            schema.put("properties", props);
            schema.put("required", List.of("repo_id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String repoId = (String) args.get("repo_id");
            String directory = (String) args.getOrDefault("directory", "");

            // Search for markdown documents in the given directory
            SearchQuery sq = new SearchQuery();
            sq.setRepoIds(List.of(repoId));
            sq.setElementTypes(List.of(ElementType.MARKDOWN_DOCUMENT));
            sq.setLimit(20);

            if (directory != null && !directory.isBlank()) {
                sq.setFilePathPattern(directory.endsWith("/") ? directory + "**" : directory + "/**");
            }

            List<CodeElement> docs = graphService.search(sq);

            // Prefer README files
            CodeElement readme = null;
            for (CodeElement doc : docs) {
                String fp = doc.getFilePath();
                if (fp == null) continue;
                String lower = fp.toLowerCase();
                if (lower.endsWith("readme.md") || lower.endsWith("readme.markdown")) {
                    readme = doc;
                    break;
                }
            }

            if (readme == null && !docs.isEmpty()) {
                readme = docs.get(0);
            }

            if (readme == null) {
                return "No README or markdown document found in repo: " + repoId
                        + (directory != null && !directory.isBlank() ? " at path: " + directory : " (root)");
            }

            StringBuilder sb = new StringBuilder("## README: ").append(readme.getFilePath()).append("\n\n");
            if (readme.getSnippet() != null && !readme.getSnippet().isBlank()) {
                sb.append(readme.getSnippet());
            } else if (readme.getDocComment() != null) {
                sb.append(readme.getDocComment());
            } else {
                sb.append("(Content not available in snippet — use get_snippet with ID: ")
                  .append(readme.getId()).append(")");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_docs
    // -------------------------------------------------------------------------

    public static class GetDocs implements McpTool {
        private final GraphService graphService;
        public GetDocs(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_docs"; }

        @Override
        public String getDescription() {
            return "Get comprehensive documentation context for a code element: its own doc comment, "
                    + "the enclosing class's doc comment, any adjacent doc comments (COMMENT_DOC elements), "
                    + "and all annotations. Provides everything needed to understand a method or class "
                    + "without reading the full source. Use this as the primary way to read documentation.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return buildIdSchema("The element ID to get documentation for.");
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            Optional<CodeElement> selfOpt = graphService.getElementById(id);
            if (selfOpt.isEmpty()) return "Element not found: " + id;

            CodeElement el = selfOpt.get();
            StringBuilder sb = new StringBuilder("## Documentation: ");
            sb.append("[").append(el.getElementType()).append("] ");
            sb.append(el.getQualifiedName() != null ? el.getQualifiedName() : el.getName()).append("\n\n");

            // Own doc comment
            if (el.getDocComment() != null && !el.getDocComment().isBlank()) {
                sb.append("### Doc Comment\n").append(el.getDocComment().strip()).append("\n\n");
            }

            // COMMENT_DOC children (adjacent doc comments stored as elements)
            List<CodeElement> comments = graphService.getComments(id);
            List<CodeElement> docComments = new ArrayList<>();
            for (CodeElement c : comments) {
                if (c.getElementType() == ElementType.COMMENT_DOC) {
                    docComments.add(c);
                }
            }
            if (!docComments.isEmpty()) {
                sb.append("### Adjacent Doc Comments\n");
                for (CodeElement c : docComments) {
                    if (c.getSnippet() != null) sb.append(c.getSnippet()).append("\n");
                }
                sb.append("\n");
            }

            // Annotations
            List<CodeElement> annotations = graphService.getAnnotations(id);
            if (!annotations.isEmpty()) {
                sb.append("### Annotations\n");
                for (CodeElement a : annotations) {
                    sb.append("- ");
                    sb.append(a.getSnippet() != null ? a.getSnippet().strip() :
                            (a.getName() != null ? a.getName() : a.getQualifiedName()));
                    sb.append("\n");
                }
                sb.append("\n");
            }

            // Class-level doc comment for methods
            if (el.getParentId() != null) {
                ElementType et = el.getElementType();
                if (et == ElementType.METHOD || et == ElementType.FUNCTION
                        || et == ElementType.CONSTRUCTOR || et == ElementType.FIELD
                        || et == ElementType.PROPERTY) {
                    Optional<CodeElement> parent = graphService.getElementById(el.getParentId());
                    if (parent.isPresent() && parent.get().getDocComment() != null
                            && !parent.get().getDocComment().isBlank()) {
                        sb.append("### Enclosing Class Doc Comment\n");
                        sb.append("[").append(parent.get().getElementType()).append("] ");
                        sb.append(parent.get().getQualifiedName() != null
                                ? parent.get().getQualifiedName() : parent.get().getName()).append(":\n");
                        sb.append(parent.get().getDocComment().strip()).append("\n\n");
                    }
                }
            }

            if (sb.toString().endsWith("## Documentation: [" + el.getElementType() + "] "
                    + (el.getQualifiedName() != null ? el.getQualifiedName() : el.getName()) + "\n\n")) {
                sb.append("(No documentation available for this element)\n");
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
