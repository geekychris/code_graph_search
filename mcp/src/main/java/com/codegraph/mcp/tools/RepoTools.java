package com.codegraph.mcp.tools;

import com.codegraph.core.model.*;
import com.codegraph.core.service.GraphService;
import com.codegraph.mcp.McpTool;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Tools for repository-level navigation: listing repos, structure, config files, and cross-element edges.
 */
public class RepoTools {

    // -------------------------------------------------------------------------
    // list_repos
    // -------------------------------------------------------------------------

    public static class ListRepos implements McpTool {
        private final GraphService graphService;
        public ListRepos(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "list_repos"; }

        @Override
        public String getDescription() {
            return "List all indexed repositories with their status, element counts, and language breakdown. "
                    + "Use this as the first tool to call when starting to explore an unfamiliar codebase — "
                    + "it tells you what repos are available and their indexing state.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", new LinkedHashMap<>());
            schema.put("required", List.of());
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            List<Repository> repos = graphService.listRepositories();
            if (repos.isEmpty()) return "No repositories are currently indexed.";

            StringBuilder sb = new StringBuilder("## Indexed Repositories (").append(repos.size()).append(")\n\n");
            for (Repository repo : repos) {
                sb.append("### ").append(repo.getName()).append("\n");
                sb.append("- **ID:** ").append(repo.getId()).append("\n");
                sb.append("- **Status:** ").append(repo.getStatus()).append("\n");
                if (repo.getRootPath() != null) sb.append("- **Path:** ").append(repo.getRootPath()).append("\n");
                sb.append("- **Elements:** ").append(repo.getElementCount()).append("\n");
                sb.append("- **Files:** ").append(repo.getFileCount()).append("\n");
                if (repo.getLanguages() != null && !repo.getLanguages().isEmpty()) {
                    sb.append("- **Languages:** ").append(
                            repo.getLanguages().stream().map(Enum::name).collect(Collectors.joining(", "))
                    ).append("\n");
                }
                if (repo.getLastIndexed() != null) sb.append("- **Last indexed:** ").append(repo.getLastIndexed()).append("\n");
                if (repo.getDescription() != null) sb.append("- **Description:** ").append(repo.getDescription()).append("\n");
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_repo_structure
    // -------------------------------------------------------------------------

    public static class GetRepoStructure implements McpTool {
        private final GraphService graphService;
        public GetRepoStructure(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_repo_structure"; }

        @Override
        public String getDescription() {
            return "Get the directory tree structure of a repository, showing directories and files with "
                    + "element counts. Optionally start from a sub-path and control the depth. Use this "
                    + "to understand the high-level layout of a repository before diving into specific files.";
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

            Map<String, Object> maxDepth = new LinkedHashMap<>();
            maxDepth.put("type", "integer");
            maxDepth.put("description", "Maximum directory depth to display (default 3).");
            maxDepth.put("default", 3);
            props.put("max_depth", maxDepth);

            Map<String, Object> path = new LinkedHashMap<>();
            path.put("type", "string");
            path.put("description", "Optional sub-path to start from (relative to repo root).");
            props.put("path", path);

            schema.put("properties", props);
            schema.put("required", List.of("repo_id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String repoId = (String) args.get("repo_id");
            int maxDepth = ((Number) args.getOrDefault("max_depth", 3)).intValue();
            String startPath = (String) args.get("path");

            SearchQuery sq = new SearchQuery();
            sq.setRepoIds(List.of(repoId));
            sq.setElementTypes(List.of(ElementType.DIRECTORY, ElementType.FILE));
            sq.setLimit(500);
            if (startPath != null && !startPath.isBlank()) {
                sq.setFilePathPattern(startPath.endsWith("/") ? startPath + "**" : startPath + "/**");
            }

            List<CodeElement> elements = graphService.search(sq);

            if (elements.isEmpty()) {
                return "No directory/file structure found for repo: " + repoId;
            }

            // Build tree by file path
            Optional<Repository> repoOpt = graphService.getRepository(repoId);
            StringBuilder sb = new StringBuilder("## Repository Structure\n");
            sb.append("Repo: ").append(repoId);
            repoOpt.ifPresent(r -> sb.append(" (").append(r.getName()).append(")"));
            sb.append("\n\n");

            // Sort by path
            elements.sort(Comparator.comparing(e -> e.getFilePath() != null ? e.getFilePath() : ""));

            // Group files under directories using path depth
            Set<String> printed = new HashSet<>();
            for (CodeElement el : elements) {
                String fp = el.getFilePath();
                if (fp == null) continue;

                int depth = fp.split("/").length - 1;
                if (depth > maxDepth) continue;

                sb.append("  ".repeat(depth));
                if (el.getElementType() == ElementType.DIRECTORY) {
                    sb.append("[DIR]  ").append(fp).append("/");
                } else {
                    String fileName = fp.contains("/") ? fp.substring(fp.lastIndexOf('/') + 1) : fp;
                    sb.append("[FILE] ").append(fileName);
                    if (el.getLanguage() != null) sb.append(" (").append(el.getLanguage().id).append(")");
                }
                sb.append("\n");
            }

            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // list_configs
    // -------------------------------------------------------------------------

    public static class ListConfigs implements McpTool {
        private final GraphService graphService;
        public ListConfigs(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "list_configs"; }

        @Override
        public String getDescription() {
            return "List all YAML and JSON configuration files in a repository or subdirectory. "
                    + "Returns CONFIG_FILE and YAML/JSON FILE elements. Use this to find configuration "
                    + "files before reading them with get_config.";
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

            Map<String, Object> path = new LinkedHashMap<>();
            path.put("type", "string");
            path.put("description", "Optional sub-path to restrict the search (relative to repo root).");
            props.put("path", path);

            schema.put("properties", props);
            schema.put("required", List.of("repo_id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String repoId = (String) args.get("repo_id");
            String path = (String) args.get("path");

            SearchQuery sq = new SearchQuery();
            sq.setRepoIds(List.of(repoId));
            sq.setElementTypes(List.of(ElementType.CONFIG_FILE));
            sq.setLimit(200);
            if (path != null && !path.isBlank()) {
                sq.setFilePathPattern(path.endsWith("/") ? path + "**" : path + "/**");
            }

            List<CodeElement> configs = graphService.search(sq);

            // Also search for YAML/JSON FILE elements
            SearchQuery sq2 = new SearchQuery();
            sq2.setRepoIds(List.of(repoId));
            sq2.setElementTypes(List.of(ElementType.FILE));
            sq2.setLanguages(List.of(Language.YAML, Language.JSON));
            sq2.setLimit(200);
            if (path != null && !path.isBlank()) {
                sq2.setFilePathPattern(path.endsWith("/") ? path + "**" : path + "/**");
            }
            List<CodeElement> configFiles = graphService.search(sq2);

            // Combine, deduplicating by ID
            Map<String, CodeElement> combined = new LinkedHashMap<>();
            for (CodeElement el : configs) combined.put(el.getId(), el);
            for (CodeElement el : configFiles) combined.put(el.getId(), el);

            if (combined.isEmpty()) {
                return "No config files found in repo: " + repoId
                        + (path != null ? " at path: " + path : "");
            }

            List<CodeElement> sorted = new ArrayList<>(combined.values());
            sorted.sort(Comparator.comparing(e -> e.getFilePath() != null ? e.getFilePath() : ""));

            StringBuilder sb = new StringBuilder("## Config Files (").append(sorted.size()).append(")\n\n");
            for (CodeElement el : sorted) {
                sb.append("- [").append(el.getElementType()).append("] ");
                sb.append(el.getFilePath() != null ? el.getFilePath() : el.getName());
                if (el.getLanguage() != null) sb.append(" (").append(el.getLanguage().id).append(")");
                sb.append("\n  ID: ").append(el.getId()).append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_config
    // -------------------------------------------------------------------------

    public static class GetConfig implements McpTool {
        private final GraphService graphService;
        public GetConfig(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_config"; }

        @Override
        public String getDescription() {
            return "Get the content of a config file element (YAML, JSON, or CONFIG_FILE type). "
                    + "Returns the file's snippet/content. Use list_configs first to find config file IDs.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            Map<String, Object> id = new LinkedHashMap<>();
            id.put("type", "string");
            id.put("description", "The config file element ID (from list_configs).");
            props.put("id", id);
            schema.put("properties", props);
            schema.put("required", List.of("id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            Optional<CodeElement> opt = graphService.getElementById(id);
            if (opt.isEmpty()) return "Config element not found: " + id;

            CodeElement el = opt.get();
            StringBuilder sb = new StringBuilder("## Config File: ");
            sb.append(el.getFilePath() != null ? el.getFilePath() : el.getName()).append("\n");
            if (el.getLanguage() != null) sb.append("Language: ").append(el.getLanguage()).append("\n");
            sb.append("\n");

            if (el.getSnippet() != null && !el.getSnippet().isBlank()) {
                String lang = el.getLanguage() != null ? el.getLanguage().id : "";
                sb.append("```").append(lang).append("\n");
                sb.append(el.getSnippet());
                if (!el.getSnippet().endsWith("\n")) sb.append("\n");
                sb.append("```\n");
            } else {
                sb.append("(No content available for this config file)\n");

                // Show CONFIG_KEY children if any
                List<CodeElement> keys = graphService.getChildren(id, ElementType.CONFIG_KEY);
                if (!keys.isEmpty()) {
                    sb.append("\n### Config Keys (").append(keys.size()).append(")\n");
                    for (CodeElement key : keys) {
                        sb.append("- ").append(key.getName());
                        if (key.getSnippet() != null) sb.append(": ").append(key.getSnippet().strip());
                        sb.append("\n");
                    }
                }
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // get_related
    // -------------------------------------------------------------------------

    public static class GetRelated implements McpTool {
        private final GraphService graphService;
        public GetRelated(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "get_related"; }

        @Override
        public String getDescription() {
            return "Get all elements related to a given element through any edge type in the graph. "
                    + "Optionally filter by specific edge types. Results are grouped by edge type. "
                    + "Use this for a broad exploration of what a code element connects to — useful when "
                    + "you're not sure which specific relationship to look for.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();

            Map<String, Object> id = new LinkedHashMap<>();
            id.put("type", "string");
            id.put("description", "The element ID to find related elements for.");
            props.put("id", id);

            Map<String, Object> edgeTypes = new LinkedHashMap<>();
            edgeTypes.put("type", "array");
            edgeTypes.put("items", Map.of("type", "string"));
            edgeTypes.put("description", "Optional list of edge types to filter by. Valid values: CONTAINS, EXTENDS, "
                    + "IMPLEMENTS, OVERRIDES, MIXES_IN, CALLS, INSTANTIATES, USES_TYPE, IMPORTS, DEPENDS_ON, "
                    + "DOCUMENTS, ANNOTATES, IMPLEMENTS_PROTO, CALLS_RPC, SHARES_TYPE, SECTION_OF, "
                    + "DOCUMENTS_DIR, CONFIGURES, REFERENCES_CLASS, PRECEDES, DEFINED_IN.");
            props.put("edge_types", edgeTypes);

            Map<String, Object> limit = new LinkedHashMap<>();
            limit.put("type", "integer");
            limit.put("description", "Maximum number of related elements to return (default 20).");
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

            @SuppressWarnings("unchecked")
            List<String> edgeTypeStrs = (List<String>) args.get("edge_types");

            // Gather outgoing and incoming edges
            List<CodeEdge> outEdges;
            List<CodeEdge> inEdges;

            if (edgeTypeStrs != null && !edgeTypeStrs.isEmpty()) {
                outEdges = new ArrayList<>();
                inEdges = new ArrayList<>();
                for (String etStr : edgeTypeStrs) {
                    try {
                        EdgeType et = EdgeType.valueOf(etStr.toUpperCase());
                        outEdges.addAll(graphService.getEdgesFrom(id, et));
                        inEdges.addAll(graphService.getEdgesTo(id, et));
                    } catch (IllegalArgumentException ignored) {}
                }
            } else {
                outEdges = graphService.getEdgesFrom(id);
                inEdges = graphService.getEdgesTo(id);
            }

            if (outEdges.isEmpty() && inEdges.isEmpty()) {
                return "No related elements found for: " + id;
            }

            Optional<CodeElement> self = graphService.getElementById(id);
            StringBuilder sb = new StringBuilder("## Related Elements\n");
            self.ifPresent(el -> sb.append("Element: [").append(el.getElementType()).append("] ")
                    .append(el.getQualifiedName() != null ? el.getQualifiedName() : el.getName()).append("\n"));
            sb.append("\n");

            // Group outgoing by edge type
            if (!outEdges.isEmpty()) {
                sb.append("### Outgoing Edges\n");
                Map<EdgeType, List<CodeEdge>> byType = new LinkedHashMap<>();
                for (CodeEdge e : outEdges) {
                    byType.computeIfAbsent(e.getEdgeType(), k -> new ArrayList<>()).add(e);
                }
                int shown = 0;
                for (Map.Entry<EdgeType, List<CodeEdge>> entry : byType.entrySet()) {
                    if (shown >= limit) { sb.append("...(limit reached)\n"); break; }
                    sb.append("**").append(entry.getKey()).append("** (").append(entry.getValue().size()).append(")\n");
                    for (CodeEdge e : entry.getValue()) {
                        if (shown >= limit) break;
                        Optional<CodeElement> target = graphService.getElementById(e.getToId());
                        sb.append("  -> ");
                        target.ifPresentOrElse(
                                t -> {
                                    sb.append("[").append(t.getElementType()).append("] ");
                                    sb.append(t.getQualifiedName() != null ? t.getQualifiedName() : t.getName());
                                    if (t.getFilePath() != null && t.getLineStart() > 0) {
                                        sb.append(" (").append(t.getFilePath()).append(":").append(t.getLineStart()).append(")");
                                    }
                                },
                                () -> sb.append(e.getToId())
                        );
                        sb.append("\n");
                        shown++;
                    }
                }
                sb.append("\n");
            }

            // Group incoming by edge type
            if (!inEdges.isEmpty()) {
                sb.append("### Incoming Edges\n");
                Map<EdgeType, List<CodeEdge>> byType = new LinkedHashMap<>();
                for (CodeEdge e : inEdges) {
                    byType.computeIfAbsent(e.getEdgeType(), k -> new ArrayList<>()).add(e);
                }
                int shown = 0;
                for (Map.Entry<EdgeType, List<CodeEdge>> entry : byType.entrySet()) {
                    if (shown >= limit) { sb.append("...(limit reached)\n"); break; }
                    sb.append("**").append(entry.getKey()).append("** (").append(entry.getValue().size()).append(")\n");
                    for (CodeEdge e : entry.getValue()) {
                        if (shown >= limit) break;
                        Optional<CodeElement> source = graphService.getElementById(e.getFromId());
                        sb.append("  <- ");
                        source.ifPresentOrElse(
                                s -> {
                                    sb.append("[").append(s.getElementType()).append("] ");
                                    sb.append(s.getQualifiedName() != null ? s.getQualifiedName() : s.getName());
                                    if (s.getFilePath() != null && s.getLineStart() > 0) {
                                        sb.append(" (").append(s.getFilePath()).append(":").append(s.getLineStart()).append(")");
                                    }
                                },
                                () -> sb.append(e.getFromId())
                        );
                        sb.append("\n");
                        shown++;
                    }
                }
            }

            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // find_cross_language
    // -------------------------------------------------------------------------

    public static class FindCrossLanguage implements McpTool {
        private final GraphService graphService;
        public FindCrossLanguage(GraphService graphService) { this.graphService = graphService; }

        @Override public String getName() { return "find_cross_language"; }

        @Override
        public String getDescription() {
            return "Find elements in other languages/repos that are related to a given element. Follows "
                    + "SHARES_TYPE, IMPLEMENTS_PROTO, CALLS_RPC, and REFERENCES_CLASS edges which typically "
                    + "represent cross-language or cross-repo relationships. Use this for polyglot codebases "
                    + "to find, for example, the protobuf definition corresponding to a generated Java class, "
                    + "or the TypeScript client corresponding to a Go server.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();

            Map<String, Object> id = new LinkedHashMap<>();
            id.put("type", "string");
            id.put("description", "The element ID to find cross-language relations for.");
            props.put("id", id);

            Map<String, Object> targetLanguage = new LinkedHashMap<>();
            targetLanguage.put("type", "string");
            targetLanguage.put("description", "Optional target language to filter results (e.g. TYPESCRIPT, GO, RUST).");
            props.put("target_language", targetLanguage);

            schema.put("properties", props);
            schema.put("required", List.of("id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            String targetLangStr = (String) args.get("target_language");
            Language targetLang = null;
            if (targetLangStr != null) {
                try { targetLang = Language.valueOf(targetLangStr.toUpperCase()); } catch (IllegalArgumentException ignored) {}
            }

            Optional<CodeElement> self = graphService.getElementById(id);
            if (self.isEmpty()) return "Element not found: " + id;
            Language selfLang = self.get().getLanguage();

            // Cross-language edge types
            List<EdgeType> crossEdges = List.of(
                    EdgeType.SHARES_TYPE, EdgeType.IMPLEMENTS_PROTO, EdgeType.CALLS_RPC, EdgeType.REFERENCES_CLASS
            );

            List<String> results = new ArrayList<>();
            for (EdgeType et : crossEdges) {
                List<CodeEdge> edges = graphService.getEdgesFrom(id, et);
                edges.addAll(graphService.getEdgesTo(id, et));
                for (CodeEdge e : edges) {
                    String otherId = e.getFromId().equals(id) ? e.getToId() : e.getFromId();
                    Optional<CodeElement> other = graphService.getElementById(otherId);
                    if (other.isEmpty()) continue;
                    // Filter by language if specified
                    if (targetLang != null && other.get().getLanguage() != targetLang) continue;
                    // Exclude same language (unless same-language cross-repo)
                    if (targetLang == null && selfLang != null && selfLang.equals(other.get().getLanguage())
                            && self.get().getRepoId() != null
                            && self.get().getRepoId().equals(other.get().getRepoId())) continue;

                    StringBuilder line = new StringBuilder();
                    line.append("**").append(et).append("** -> ");
                    line.append("[").append(other.get().getElementType()).append("] ");
                    line.append(other.get().getQualifiedName() != null
                            ? other.get().getQualifiedName() : other.get().getName());
                    if (other.get().getLanguage() != null) line.append(" (").append(other.get().getLanguage()).append(")");
                    if (other.get().getFilePath() != null && other.get().getLineStart() > 0) {
                        line.append(" at ").append(other.get().getFilePath()).append(":").append(other.get().getLineStart());
                    }
                    results.add(line.toString());
                }
            }

            if (results.isEmpty()) {
                return "No cross-language related elements found for: " + id
                        + (targetLang != null ? " (language: " + targetLang + ")" : "");
            }

            StringBuilder sb = new StringBuilder("## Cross-Language Relations\n");
            self.ifPresent(el -> sb.append("Element: [").append(el.getElementType()).append("] ")
                    .append(el.getQualifiedName() != null ? el.getQualifiedName() : el.getName())
                    .append(" (").append(el.getLanguage()).append(")\n"));
            sb.append("\n");
            for (String r : results) sb.append("- ").append(r).append("\n");
            return sb.toString();
        }
    }
}
