package com.codegraph.mcp.tools;

import com.codegraph.core.model.*;
import com.codegraph.core.service.GraphService;
import com.codegraph.mcp.McpTool;

import java.util.*;

/**
 * Tools for navigating import relationships and element usages.
 */
public class ImportTools {

    // -------------------------------------------------------------------------
    // get_imports
    // -------------------------------------------------------------------------

    public static class GetImports implements McpTool {
        private final GraphService graphService;
        public GetImports(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_imports"; }

        @Override
        public String getDescription() {
            return "Get all imports declared in a file element. Returns the IMPORT and USE_DECLARATION "
                    + "elements for the file. Use this to understand a file's external dependencies and "
                    + "which packages/modules it relies on. Requires a FILE-type element ID.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return buildIdSchema("The FILE element ID to get imports for. Use get_file_outline or search to find the file element ID.");
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            List<CodeElement> imports = graphService.getImports(id);
            if (imports.isEmpty()) return "No imports found for file element: " + id;

            Optional<CodeElement> self = graphService.getElementById(id);
            StringBuilder sb = new StringBuilder("## Imports (").append(imports.size()).append(")\n");
            self.ifPresent(el -> sb.append("File: ").append(el.getFilePath()).append("\n"));
            sb.append("\n");

            for (CodeElement imp : imports) {
                sb.append("- ");
                if (imp.getSnippet() != null && !imp.getSnippet().isBlank()) {
                    sb.append(imp.getSnippet().strip());
                } else {
                    sb.append(imp.getQualifiedName() != null ? imp.getQualifiedName() : imp.getName());
                }
                if (imp.getLineStart() > 0) sb.append("  (line ").append(imp.getLineStart()).append(")");
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_imported_by
    // -------------------------------------------------------------------------

    public static class GetImportedBy implements McpTool {
        private final GraphService graphService;
        public GetImportedBy(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_imported_by"; }

        @Override
        public String getDescription() {
            return "Get all files that import a given file or package. Finds the inverse of the IMPORTS "
                    + "relationship. Use this to understand which other parts of the codebase depend on "
                    + "a specific file or module — useful for impact analysis.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return buildIdSchema("The FILE element ID to find importers of.");
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            List<CodeElement> importedBy = graphService.getImportedBy(id);
            if (importedBy.isEmpty()) return "No files found that import element: " + id;

            Optional<CodeElement> self = graphService.getElementById(id);
            StringBuilder sb = new StringBuilder("## Imported By (").append(importedBy.size()).append(" files)\n");
            self.ifPresent(el -> sb.append("File: ").append(el.getFilePath()).append("\n"));
            sb.append("\n");

            for (CodeElement f : importedBy) {
                sb.append("- [").append(f.getElementType()).append("] ");
                sb.append(f.getFilePath() != null ? f.getFilePath() :
                        (f.getQualifiedName() != null ? f.getQualifiedName() : f.getName()));
                sb.append("\n  ID: ").append(f.getId()).append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_usages
    // -------------------------------------------------------------------------

    public static class GetUsages implements McpTool {
        private final GraphService graphService;
        public GetUsages(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_usages"; }

        @Override
        public String getDescription() {
            return "Get all places in the codebase where a code element is used — including calls, type "
                    + "references, instantiations, and field accesses. Use this to understand the full "
                    + "impact of a change to a method, class, or field. Works for any element type.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();

            Map<String, Object> id = new LinkedHashMap<>();
            id.put("type", "string");
            id.put("description", "The element ID to find usages of.");
            props.put("id", id);

            Map<String, Object> limit = new LinkedHashMap<>();
            limit.put("type", "integer");
            limit.put("description", "Maximum number of usages to return (default 50).");
            limit.put("default", 50);
            props.put("limit", limit);

            schema.put("properties", props);
            schema.put("required", List.of("id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            int limit = ((Number) args.getOrDefault("limit", 50)).intValue();

            List<CodeElement> usages = graphService.getUsages(id);
            if (usages.isEmpty()) return "No usages found for element: " + id;

            List<CodeElement> shown = usages.size() > limit ? usages.subList(0, limit) : usages;

            Optional<CodeElement> self = graphService.getElementById(id);
            StringBuilder sb = new StringBuilder("## Usages of ");
            self.ifPresent(el -> sb.append("[").append(el.getElementType()).append("] ")
                    .append(el.getQualifiedName() != null ? el.getQualifiedName() : el.getName()));
            sb.append("\nFound ").append(usages.size()).append(" usage(s)");
            if (shown.size() < usages.size()) sb.append(", showing first ").append(limit);
            sb.append(".\n\n");

            for (int i = 0; i < shown.size(); i++) {
                CodeElement usage = shown.get(i);
                sb.append(i + 1).append(". ");
                SearchTools.appendElementSummary(sb, usage);
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_dependencies
    // -------------------------------------------------------------------------

    public static class GetDependencies implements McpTool {
        private final GraphService graphService;
        public GetDependencies(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_dependencies"; }

        @Override
        public String getDescription() {
            return "Get package/module-level dependencies for a repository or a specific element. "
                    + "If element_id is given, shows the dependencies of that element's containing package. "
                    + "Otherwise shows top-level repository dependencies. Returns outbound DEPENDS_ON and "
                    + "IMPORTS edges grouped by target package.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();

            Map<String, Object> repoId = new LinkedHashMap<>();
            repoId.put("type", "string");
            repoId.put("description", "The repository ID to analyze dependencies for.");
            props.put("repo_id", repoId);

            Map<String, Object> elementId = new LinkedHashMap<>();
            elementId.put("type", "string");
            elementId.put("description", "Optional element ID — if given, shows dependencies of this element's package.");
            props.put("element_id", elementId);

            schema.put("properties", props);
            schema.put("required", List.of("repo_id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String repoId = (String) args.get("repo_id");
            String elementId = (String) args.get("element_id");

            if (elementId != null && !elementId.isBlank()) {
                // Deps of this element's package
                Optional<CodeElement> el = graphService.getElementById(elementId);
                if (el.isEmpty()) return "Element not found: " + elementId;

                List<CodeEdge> edges = graphService.getEdgesFrom(elementId, EdgeType.DEPENDS_ON);
                List<CodeEdge> importEdges = graphService.getEdgesFrom(elementId, EdgeType.IMPORTS);

                if (edges.isEmpty() && importEdges.isEmpty()) {
                    return "No dependencies found for element: " + elementId;
                }

                StringBuilder sb = new StringBuilder("## Dependencies of ");
                sb.append("[").append(el.get().getElementType()).append("] ");
                sb.append(el.get().getQualifiedName() != null ? el.get().getQualifiedName() : el.get().getName()).append("\n\n");

                appendEdgeDeps(sb, edges, "DEPENDS_ON");
                appendEdgeDeps(sb, importEdges, "IMPORTS");
                return sb.toString();
            } else {
                // Top-level repo: find PACKAGE elements and their DEPENDS_ON edges
                SearchQuery sq = new SearchQuery();
                sq.setRepoIds(List.of(repoId));
                sq.setElementTypes(List.of(ElementType.PACKAGE, ElementType.MODULE, ElementType.NAMESPACE));
                sq.setLimit(100);
                List<CodeElement> pkgs = graphService.search(sq);

                if (pkgs.isEmpty()) return "No packages/modules found for repo: " + repoId;

                StringBuilder sb = new StringBuilder("## Repository Dependencies\n");
                sb.append("Repo: ").append(repoId).append("\n");
                sb.append("Packages analyzed: ").append(pkgs.size()).append("\n\n");

                for (CodeElement pkg : pkgs) {
                    List<CodeEdge> edges = graphService.getEdgesFrom(pkg.getId(), EdgeType.DEPENDS_ON);
                    if (!edges.isEmpty()) {
                        sb.append("**[").append(pkg.getElementType()).append("]** ");
                        sb.append(pkg.getQualifiedName() != null ? pkg.getQualifiedName() : pkg.getName()).append("\n");
                        for (CodeEdge e : edges) {
                            Optional<CodeElement> target = graphService.getElementById(e.getToId());
                            sb.append("  -> ");
                            target.ifPresentOrElse(
                                    t -> sb.append("[").append(t.getElementType()).append("] ")
                                            .append(t.getQualifiedName() != null ? t.getQualifiedName() : t.getName()),
                                    () -> sb.append(e.getToId())
                            );
                            sb.append("\n");
                        }
                        sb.append("\n");
                    }
                }
                return sb.toString();
            }
        }

        private void appendEdgeDeps(StringBuilder sb, List<CodeEdge> edges, String label) {
            if (edges.isEmpty()) return;
            sb.append("### ").append(label).append(" (").append(edges.size()).append(")\n");
            for (CodeEdge e : edges) {
                Optional<CodeElement> target = graphService.getElementById(e.getToId());
                sb.append("- ");
                target.ifPresentOrElse(
                        t -> sb.append("[").append(t.getElementType()).append("] ")
                                .append(t.getQualifiedName() != null ? t.getQualifiedName() : t.getName())
                                .append("\n  File: ").append(t.getFilePath()),
                        () -> sb.append(e.getToId())
                );
                sb.append("\n");
            }
            sb.append("\n");
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
