package com.codegraph.mcp.tools;

import com.codegraph.core.model.*;
import com.codegraph.core.service.GraphService;
import com.codegraph.mcp.McpTool;

import java.util.*;

/**
 * Tools for searching code elements across the indexed graph.
 */
public class SearchTools {

    // -------------------------------------------------------------------------
    // search_code
    // -------------------------------------------------------------------------

    public static class SearchCode implements McpTool {

        private final GraphService graphService;

        public SearchCode(GraphService graphService) {
            this.graphService = graphService;
        }

        @Override
        public String getName() {
            return "search_code";
        }

        @Override
        public String getDescription() {
            return "Full-text search across all indexed code elements. Returns matching elements with their "
                    + "type, qualified name, file location, and a code snippet. Use this as your primary "
                    + "tool for finding code when you know keywords, class names, method names, or concepts. "
                    + "Supports filtering by repository, element type (CLASS, METHOD, FIELD, etc.), language, "
                    + "and file path pattern. Results are sorted by relevance by default.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");

            Map<String, Object> props = new LinkedHashMap<>();

            Map<String, Object> query = new LinkedHashMap<>();
            query.put("type", "string");
            query.put("description", "Search keywords or phrase. Searches name, qualified name, and code snippet.");
            props.put("query", query);

            Map<String, Object> repos = new LinkedHashMap<>();
            repos.put("type", "array");
            repos.put("items", Map.of("type", "string"));
            repos.put("description", "Optional list of repository IDs to restrict the search to.");
            props.put("repos", repos);

            Map<String, Object> elementTypes = new LinkedHashMap<>();
            elementTypes.put("type", "array");
            elementTypes.put("items", Map.of("type", "string"));
            elementTypes.put("description", "Filter by element types. Valid values: CLASS, INTERFACE, ENUM, STRUCT, "
                    + "TRAIT, METHOD, FUNCTION, CONSTRUCTOR, FIELD, PROPERTY, ENUM_CONSTANT, FILE, PACKAGE, "
                    + "NAMESPACE, MODULE, PARAMETER, ANNOTATION, DECORATOR, ATTRIBUTE, IMPORT, COMMENT_DOC, "
                    + "MARKDOWN_DOCUMENT, CONFIG_FILE, etc.");
            props.put("element_types", elementTypes);

            Map<String, Object> languages = new LinkedHashMap<>();
            languages.put("type", "array");
            languages.put("items", Map.of("type", "string"));
            languages.put("description", "Filter by language. Valid values: JAVA, GO, RUST, TYPESCRIPT, JAVASCRIPT, "
                    + "C, CPP, MARKDOWN, YAML, JSON.");
            props.put("languages", languages);

            Map<String, Object> filePattern = new LinkedHashMap<>();
            filePattern.put("type", "string");
            filePattern.put("description", "Glob pattern to filter by file path, e.g. \"**/service/**\" or \"**Test.java\".");
            props.put("file_pattern", filePattern);

            Map<String, Object> sortBy = new LinkedHashMap<>();
            sortBy.put("type", "string");
            sortBy.put("enum", List.of("relevance", "name", "file", "line", "qualified_name", "element_type", "language"));
            sortBy.put("description", "Sort order for results. Default: relevance.");
            props.put("sort_by", sortBy);

            Map<String, Object> limit = new LinkedHashMap<>();
            limit.put("type", "integer");
            limit.put("description", "Maximum number of results to return (default 20, max 500).");
            limit.put("default", 20);
            props.put("limit", limit);

            Map<String, Object> offset = new LinkedHashMap<>();
            offset.put("type", "integer");
            offset.put("description", "Offset for pagination (default 0).");
            offset.put("default", 0);
            props.put("offset", offset);

            schema.put("properties", props);
            schema.put("required", List.of("query"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String queryStr = (String) args.get("query");
            SearchQuery sq = new SearchQuery(queryStr);

            @SuppressWarnings("unchecked")
            List<String> repos = (List<String>) args.get("repos");
            if (repos != null && !repos.isEmpty()) sq.setRepoIds(repos);

            @SuppressWarnings("unchecked")
            List<String> typeStrs = (List<String>) args.get("element_types");
            if (typeStrs != null && !typeStrs.isEmpty()) {
                List<ElementType> types = new ArrayList<>();
                for (String t : typeStrs) {
                    try { types.add(ElementType.valueOf(t.toUpperCase())); } catch (IllegalArgumentException ignored) {}
                }
                if (!types.isEmpty()) sq.setElementTypes(types);
            }

            @SuppressWarnings("unchecked")
            List<String> langStrs = (List<String>) args.get("languages");
            if (langStrs != null && !langStrs.isEmpty()) {
                List<Language> langs = new ArrayList<>();
                for (String l : langStrs) {
                    try { langs.add(Language.valueOf(l.toUpperCase())); } catch (IllegalArgumentException ignored) {}
                }
                if (!langs.isEmpty()) sq.setLanguages(langs);
            }

            String filePattern = (String) args.get("file_pattern");
            if (filePattern != null) sq.setFilePathPattern(filePattern);

            String sortByStr = (String) args.getOrDefault("sort_by", "relevance");
            sq.setSortBy(parseSortField(sortByStr));

            int limit = ((Number) args.getOrDefault("limit", 20)).intValue();
            int offset = ((Number) args.getOrDefault("offset", 0)).intValue();
            sq.setLimit(limit);
            sq.setOffset(offset);

            List<CodeElement> results = graphService.search(sq);
            long total = graphService.count(sq);

            if (results.isEmpty()) {
                return "No results found for query: \"" + queryStr + "\"";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## Search Results for \"").append(queryStr).append("\"\n");
            sb.append("Found ").append(total).append(" total match(es), showing ")
              .append(offset + 1).append("-").append(offset + results.size()).append(".\n\n");

            for (int i = 0; i < results.size(); i++) {
                CodeElement el = results.get(i);
                sb.append(i + offset + 1).append(". ");
                appendElementSummary(sb, el);
                sb.append("\n");
            }
            return sb.toString();
        }

        private SearchQuery.SortField parseSortField(String s) {
            return switch (s == null ? "relevance" : s.toLowerCase()) {
                case "name" -> SearchQuery.SortField.NAME;
                case "file" -> SearchQuery.SortField.FILE_PATH;
                case "line" -> SearchQuery.SortField.LINE;
                case "qualified_name" -> SearchQuery.SortField.QUALIFIED_NAME;
                case "element_type" -> SearchQuery.SortField.ELEMENT_TYPE;
                case "language" -> SearchQuery.SortField.LANGUAGE;
                default -> SearchQuery.SortField.RELEVANCE;
            };
        }
    }

    // -------------------------------------------------------------------------
    // search_by_name
    // -------------------------------------------------------------------------

    public static class SearchByName implements McpTool {

        private final GraphService graphService;

        public SearchByName(GraphService graphService) {
            this.graphService = graphService;
        }

        @Override
        public String getName() {
            return "search_by_name";
        }

        @Override
        public String getDescription() {
            return "Search for code elements by their name or qualified name. Use this when you know the exact "
                    + "or approximate name of a class, method, function, or field. Supports exact matching "
                    + "(qualified name prefix) or fuzzy text search by name keyword. Prefer this over "
                    + "search_code when you have a specific symbol name to look up.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");

            Map<String, Object> props = new LinkedHashMap<>();

            Map<String, Object> name = new LinkedHashMap<>();
            name.put("type", "string");
            name.put("description", "The name or qualified name to search for (e.g. \"HashMap\", \"com.example.MyClass\", \"doSomething\").");
            props.put("name", name);

            Map<String, Object> exact = new LinkedHashMap<>();
            exact.put("type", "boolean");
            exact.put("description", "If true, uses qualified name prefix matching (exact path match). "
                    + "If false (default), performs a fuzzy text search on the name.");
            exact.put("default", false);
            props.put("exact", exact);

            Map<String, Object> repos = new LinkedHashMap<>();
            repos.put("type", "array");
            repos.put("items", Map.of("type", "string"));
            repos.put("description", "Optional list of repository IDs to restrict the search to.");
            props.put("repos", repos);

            Map<String, Object> elementTypes = new LinkedHashMap<>();
            elementTypes.put("type", "array");
            elementTypes.put("items", Map.of("type", "string"));
            elementTypes.put("description", "Filter by element type (e.g. CLASS, METHOD, FIELD, INTERFACE).");
            props.put("element_types", elementTypes);

            schema.put("properties", props);
            schema.put("required", List.of("name"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String name = (String) args.get("name");
            boolean exact = Boolean.TRUE.equals(args.get("exact"));

            SearchQuery sq = new SearchQuery();

            if (exact) {
                sq.setQualifiedNamePrefix(name);
            } else {
                sq.setQuery(name);
            }

            @SuppressWarnings("unchecked")
            List<String> repos = (List<String>) args.get("repos");
            if (repos != null && !repos.isEmpty()) sq.setRepoIds(repos);

            @SuppressWarnings("unchecked")
            List<String> typeStrs = (List<String>) args.get("element_types");
            if (typeStrs != null && !typeStrs.isEmpty()) {
                List<ElementType> types = new ArrayList<>();
                for (String t : typeStrs) {
                    try { types.add(ElementType.valueOf(t.toUpperCase())); } catch (IllegalArgumentException ignored) {}
                }
                if (!types.isEmpty()) sq.setElementTypes(types);
            }

            sq.setLimit(50);
            sq.setSortBy(exact ? SearchQuery.SortField.QUALIFIED_NAME : SearchQuery.SortField.RELEVANCE);

            List<CodeElement> results = graphService.search(sq);
            long total = graphService.count(sq);

            if (results.isEmpty()) {
                return "No elements found matching name: \"" + name + "\"";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## Elements matching \"").append(name).append("\"");
            if (exact) sb.append(" (exact prefix)");
            sb.append("\n");
            sb.append("Found ").append(total).append(" result(s).\n\n");

            for (int i = 0; i < results.size(); i++) {
                CodeElement el = results.get(i);
                sb.append(i + 1).append(". ");
                appendElementSummary(sb, el);
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // Shared formatting helper
    // -------------------------------------------------------------------------

    public static void appendElementSummary(StringBuilder sb, CodeElement el) {
        sb.append("[").append(el.getElementType()).append("] ");
        if (el.getQualifiedName() != null) {
            sb.append(el.getQualifiedName());
        } else if (el.getName() != null) {
            sb.append(el.getName());
        } else {
            sb.append("(unnamed)");
        }
        if (el.getFilePath() != null) {
            sb.append(" (").append(el.getFilePath());
            if (el.getLineStart() > 0) {
                sb.append(":").append(el.getLineStart());
                if (el.getLineEnd() > el.getLineStart()) {
                    sb.append("-").append(el.getLineEnd());
                }
            }
            sb.append(")");
        }
        if (el.getSignature() != null) {
            sb.append("\n   Signature: ").append(el.getSignature());
        }
        if (el.getSnippet() != null) {
            String preview = el.getSnippet().strip();
            if (preview.length() > 120) preview = preview.substring(0, 120) + "...";
            sb.append("\n   Preview: ").append(preview);
        }
        if (el.getDocComment() != null) {
            String doc = el.getDocComment().strip();
            if (doc.length() > 100) doc = doc.substring(0, 100) + "...";
            sb.append("\n   Doc: ").append(doc);
        }
    }
}
