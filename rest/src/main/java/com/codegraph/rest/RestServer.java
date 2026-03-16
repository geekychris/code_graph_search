package com.codegraph.rest;

import com.codegraph.core.config.AppConfig;
import com.codegraph.core.config.RepoConfig;
import com.codegraph.core.model.*;
import com.codegraph.core.service.GraphService;
import com.codegraph.core.service.IndexManagementService;
import com.codegraph.indexer.IndexerService;
import com.codegraph.mcp.McpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Javalin-based REST server exposing the full graph service API over HTTP.
 *
 * <p>All endpoints live under {@code /api}. Static files for the React UI are
 * served from the classpath at {@code /static}, with an SPA fallback to
 * {@code index.html} for all unmatched routes.
 */
public class RestServer {

    private static final Logger log = LoggerFactory.getLogger(RestServer.class);

    private final GraphService graphService;
    private final McpServer mcpServer;
    private final AppConfig config;
    private final ObjectMapper mapper;
    private IndexManagementService indexManagement;

    private Javalin app;

    public RestServer(GraphService graphService, McpServer mcpServer, AppConfig config, ObjectMapper mapper) {
        this.graphService = graphService;
        this.mcpServer = mcpServer;
        this.config = config;
        this.mapper = mapper;
    }

    public void setIndexManagement(IndexManagementService indexManagement) {
        this.indexManagement = indexManagement;
    }

    // -------------------------------------------------------------------------
    // Start / Stop
    // -------------------------------------------------------------------------

    public void start() {
        app = Javalin.create(cfg -> {
            // Static files: serve React UI from classpath /static
            cfg.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/static";
                staticFiles.location = io.javalin.http.staticfiles.Location.CLASSPATH;
            });

            // SPA fallback: all unmatched GET requests serve index.html
            cfg.spaRoot.addFile("/", "/static/index.html", io.javalin.http.staticfiles.Location.CLASSPATH);

            // CORS
            if (config.getServer().isCorsEnabled()) {
                cfg.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
                    rule.anyHost();
                    rule.allowCredentials = false;
                }));
            }

            cfg.useVirtualThreads = true;
        });

        registerRoutes();

        app.start(config.getServer().getPort());
        log.info("REST server started on port {}", config.getServer().getPort());
    }

    public void stop() {
        if (app != null) {
            app.stop();
            log.info("REST server stopped");
        }
    }

    // -------------------------------------------------------------------------
    // Route registration
    // -------------------------------------------------------------------------

    private void registerRoutes() {
        // ---- Repositories ----
        app.get("/api/repos",               this::handleListRepos);
        app.post("/api/repos",              this::handleAddRepo);
        app.delete("/api/repos/{id}",       this::handleDeleteRepo);
        app.post("/api/repos/{id}/reindex", this::handleReindex);
        app.get("/api/repos/{id}/status",   this::handleRepoStatus);
        app.get("/api/repos/{id}/files",    this::handleRepoFiles);

        // ---- Search ----
        app.get("/api/search", this::handleSearch);

        // ---- Elements ----
        app.get("/api/elements/{id}",             this::handleGetElement);
        app.get("/api/elements/{id}/snippet",     this::handleGetSnippet);
        app.get("/api/elements/{id}/parent",      this::handleGetParent);
        app.get("/api/elements/{id}/ancestors",   this::handleGetAncestors);
        app.get("/api/elements/{id}/children",    this::handleGetChildren);
        app.get("/api/elements/{id}/siblings",    this::handleGetSiblings);
        app.get("/api/elements/{id}/callers",     this::handleGetCallers);
        app.get("/api/elements/{id}/callees",     this::handleGetCallees);
        app.get("/api/elements/{id}/call-chain",  this::handleGetCallChain);
        app.get("/api/elements/{id}/hierarchy",   this::handleGetHierarchy);
        app.get("/api/elements/{id}/comments",    this::handleGetComments);
        app.get("/api/elements/{id}/annotations", this::handleGetAnnotations);
        app.get("/api/elements/{id}/usages",      this::handleGetUsages);
        app.get("/api/elements/{id}/imports",     this::handleGetImports);
        app.get("/api/elements/{id}/imported-by", this::handleGetImportedBy);
        app.get("/api/elements/{id}/superclass",  this::handleGetSuperclass);
        app.get("/api/elements/{id}/interfaces",  this::handleGetInterfaces);
        app.get("/api/elements/{id}/subclasses",  this::handleGetSubclasses);
        app.get("/api/elements/{id}/implementors",this::handleGetImplementors);
        app.get("/api/elements/{id}/overrides",   this::handleGetOverrides);
        app.get("/api/elements/{id}/overriders",  this::handleGetOverriders);
        app.get("/api/elements/{id}/parameters",  this::handleGetParameters);
        app.get("/api/elements/{id}/fields",      this::handleGetFields);
        app.get("/api/elements/{id}/methods",     this::handleGetMethods);
        app.get("/api/elements/{id}/related",     this::handleGetRelated);
        app.get("/api/elements/{id}/graph",       this::handleGetElementGraph);

        // ---- Files ----
        app.get("/api/files", this::handleGetFileElements);

        // ---- Graph ----
        app.get("/api/graph/subgraph", this::handleGetSubgraph);

        // ---- FOAF / Connectivity ----
        app.get("/api/graph/shortest-path",       this::handleShortestPath);
        app.get("/api/graph/all-shortest-paths",  this::handleAllShortestPaths);
        app.get("/api/graph/all-paths",           this::handleAllPaths);
        app.get("/api/graph/similarity",          this::handleSimilarity);

        // ---- Config files ----
        app.get("/api/configs", this::handleGetConfigs);

        // ---- File Watcher ----
        app.get("/api/watcher/status",         this::handleWatcherStatus);
        app.get("/api/watcher/changes",        this::handleRecentChanges);
        app.post("/api/repos/{id}/watch",      this::handleStartWatching);
        app.delete("/api/repos/{id}/watch",    this::handleStopWatching);

        // ---- System ----
        app.get("/api/status", this::handleStatus);
        app.get("/api/health", ctx -> jsonOk(ctx, Map.of("status", "ok")));

        // ---- Directory browsing (for the repo path picker) ----
        app.get("/api/browse", this::handleBrowseDirectory);

        // ---- MCP Streamable HTTP transport ----
        String mcpPath = config.getServer().getMcpHttpPath(); // default "/mcp"
        if (config.getServer().isMcpHttpEnabled()) {
            app.post(mcpPath, this::handleMcpPost);
            app.get(mcpPath, this::handleMcpGet);
            app.delete(mcpPath, this::handleMcpDelete);
        }

        // Global error handler
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled exception on {}: {}", ctx.path(), e.getMessage(), e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            jsonError(ctx, "Internal server error: " + e.getMessage());
        });
    }

    // -------------------------------------------------------------------------
    // Repository handlers
    // -------------------------------------------------------------------------

    private void handleListRepos(Context ctx) {
        jsonOk(ctx, graphService.listRepositories());
    }

    private void handleAddRepo(Context ctx) {
        try {
            Map<?, ?> body = mapper.readValue(ctx.body(), Map.class);
            String id   = getString(body, "id");
            String name = getString(body, "name");
            String path = getString(body, "path");

            if (id == null || path == null) {
                ctx.status(HttpStatus.BAD_REQUEST);
                jsonError(ctx, "'id' and 'path' are required");
                return;
            }

            Repository repo = new Repository();
            repo.setId(id);
            repo.setName(name != null ? name : id);
            repo.setRootPath(path);
            repo.setStatus(Repository.IndexingStatus.PENDING);

            @SuppressWarnings("unchecked")
            List<String> langStrings = (List<String>) body.get("languages");
            if (langStrings != null) {
                List<Language> langs = langStrings.stream()
                        .map(l -> { try { return Language.valueOf(l.toUpperCase()); } catch (Exception e) { return null; } })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                repo.setLanguages(langs);
            }
            String desc = getString(body, "description");
            if (desc != null) repo.setDescription(desc);

            graphService.saveRepository(repo);

            // Trigger async indexing
            final Repository savedRepo = repo;
            Thread.ofVirtual().start(() -> triggerIndexing(savedRepo));

            ctx.status(HttpStatus.CREATED);
            jsonOk(ctx, savedRepo);
        } catch (Exception e) {
            log.error("Failed to add repo: {}", e.getMessage(), e);
            ctx.status(HttpStatus.BAD_REQUEST);
            jsonError(ctx, "Invalid request: " + e.getMessage());
        }
    }

    private void handleDeleteRepo(Context ctx) {
        String id = ctx.pathParam("id");
        Optional<Repository> existing = graphService.getRepository(id);
        if (existing.isEmpty()) {
            ctx.status(HttpStatus.NOT_FOUND);
            jsonError(ctx, "Repository not found: " + id);
            return;
        }
        graphService.deleteRepo(id);
        jsonOk(ctx, Map.of("deleted", id));
    }

    private void handleReindex(Context ctx) {
        String id = ctx.pathParam("id");
        Optional<Repository> existing = graphService.getRepository(id);
        if (existing.isEmpty()) {
            ctx.status(HttpStatus.NOT_FOUND);
            jsonError(ctx, "Repository not found: " + id);
            return;
        }
        Repository repo = existing.get();
        repo.setStatus(Repository.IndexingStatus.PENDING);
        graphService.saveRepository(repo);
        Thread.ofVirtual().start(() -> triggerIndexing(repo));
        jsonOk(ctx, Map.of("status", "indexing", "repoId", id));
    }

    private void handleRepoStatus(Context ctx) {
        String id = ctx.pathParam("id");
        Optional<Repository> repo = graphService.getRepository(id);
        if (repo.isEmpty()) {
            ctx.status(HttpStatus.NOT_FOUND);
            jsonError(ctx, "Repository not found: " + id);
            return;
        }
        jsonOk(ctx, repo.get());
    }

    private void handleRepoFiles(Context ctx) {
        String id = ctx.pathParam("id");
        Optional<Repository> repo = graphService.getRepository(id);
        if (repo.isEmpty()) {
            ctx.status(HttpStatus.NOT_FOUND);
            jsonError(ctx, "Repository not found: " + id);
            return;
        }
        List<String> files = graphService.listFilePaths(id);
        jsonOk(ctx, files);
    }

    // -------------------------------------------------------------------------
    // Search handler
    // -------------------------------------------------------------------------

    private void handleSearch(Context ctx) {
        SearchQuery sq = new SearchQuery();

        String q = ctx.queryParam("q");
        if (q != null && !q.isBlank()) sq.setQuery(q);

        String typeParam = ctx.queryParam("type");
        if (typeParam != null && !typeParam.isBlank()) {
            List<ElementType> types = Arrays.stream(typeParam.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> { try { return ElementType.valueOf(s.toUpperCase()); } catch (Exception e) { return null; } })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (!types.isEmpty()) sq.setElementTypes(types);
        }

        String repoParam = ctx.queryParam("repo");
        if (repoParam != null && !repoParam.isBlank()) {
            List<String> repoIds = Arrays.stream(repoParam.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            if (!repoIds.isEmpty()) sq.setRepoIds(repoIds);
        }

        String langParam = ctx.queryParam("lang");
        if (langParam != null && !langParam.isBlank()) {
            List<Language> langs = Arrays.stream(langParam.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> { try { return Language.valueOf(s.toUpperCase()); } catch (Exception e) { return null; } })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (!langs.isEmpty()) sq.setLanguages(langs);
        }

        String fileParam = ctx.queryParam("file");
        if (fileParam != null && !fileParam.isBlank()) sq.setFilePathPattern(fileParam);

        String sortParam = ctx.queryParam("sort");
        if (sortParam != null) {
            try {
                sq.setSortBy(SearchQuery.SortField.valueOf(sortParam.toUpperCase()));
            } catch (Exception ignored) {}
        }

        String ascParam = ctx.queryParam("asc");
        if (ascParam != null) sq.setSortAscending(Boolean.parseBoolean(ascParam));

        String limitParam = ctx.queryParam("limit");
        if (limitParam != null) {
            try { sq.setLimit(Integer.parseInt(limitParam)); } catch (NumberFormatException ignored) {}
        }

        String offsetParam = ctx.queryParam("offset");
        if (offsetParam != null) {
            try { sq.setOffset(Integer.parseInt(offsetParam)); } catch (NumberFormatException ignored) {}
        }

        List<CodeElement> items = graphService.search(sq);
        long total = graphService.count(sq);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("items", items);
        result.put("query", sq);
        jsonOk(ctx, result);
    }

    // -------------------------------------------------------------------------
    // Element handlers
    // -------------------------------------------------------------------------

    private void handleGetElement(Context ctx) {
        String id = ctx.pathParam("id");
        graphService.getElementById(id)
                .ifPresentOrElse(
                        el -> jsonOk(ctx, el),
                        () -> { ctx.status(HttpStatus.NOT_FOUND); jsonError(ctx, "Element not found: " + id); });
    }

    private void handleGetSnippet(Context ctx) {
        String id = ctx.pathParam("id");
        int context = queryParamInt(ctx, "context", 5);

        Optional<CodeElement> opt = graphService.getElementById(id);
        if (opt.isEmpty()) {
            ctx.status(HttpStatus.NOT_FOUND);
            jsonError(ctx, "Element not found: " + id);
            return;
        }
        CodeElement el = opt.get();

        // Resolve the file on disk: try each repo's root path
        String snippet = extractSnippet(el, context);
        int lineStart = Math.max(1, el.getLineStart() - context);
        int lineEnd   = el.getLineEnd() + context;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("element", el);
        result.put("snippet", snippet);
        result.put("filePath", el.getFilePath());
        result.put("lineStart", lineStart);
        result.put("lineEnd", lineEnd);
        jsonOk(ctx, result);
    }

    private void handleGetParent(Context ctx) {
        String id = ctx.pathParam("id");
        jsonOk(ctx, graphService.getParent(id).orElse(null));
    }

    private void handleGetAncestors(Context ctx) {
        jsonOk(ctx, graphService.getAncestors(ctx.pathParam("id")));
    }

    private void handleGetChildren(Context ctx) {
        String id = ctx.pathParam("id");
        String typeStr = ctx.queryParam("type");
        if (typeStr != null && !typeStr.isBlank()) {
            try {
                ElementType type = ElementType.valueOf(typeStr.toUpperCase());
                jsonOk(ctx, graphService.getChildren(id, type));
                return;
            } catch (IllegalArgumentException ignored) {}
        }
        jsonOk(ctx, graphService.getChildren(id));
    }

    private void handleGetSiblings(Context ctx) {
        jsonOk(ctx, graphService.getSiblings(ctx.pathParam("id")));
    }

    private void handleGetCallers(Context ctx) {
        jsonOk(ctx, graphService.getCallers(ctx.pathParam("id")));
    }

    private void handleGetCallees(Context ctx) {
        jsonOk(ctx, graphService.getCallees(ctx.pathParam("id")));
    }

    private void handleGetCallChain(Context ctx) {
        String fromId = ctx.pathParam("id");
        String toId   = ctx.queryParam("to");
        int depth     = queryParamInt(ctx, "depth", 5);

        if (toId == null || toId.isBlank()) {
            ctx.status(HttpStatus.BAD_REQUEST);
            jsonError(ctx, "Query param 'to' is required");
            return;
        }
        jsonOk(ctx, graphService.getCallChain(fromId, toId, depth));
    }

    private void handleGetHierarchy(Context ctx) {
        String id        = ctx.pathParam("id");
        String direction = ctx.queryParamAsClass("direction", String.class).getOrDefault("callees");
        int depth        = queryParamInt(ctx, "depth", 3);

        GraphService.SubGraph subGraph;
        if ("callers".equalsIgnoreCase(direction)) {
            subGraph = graphService.getSubGraph(id, depth, List.of(EdgeType.CALLS));
        } else {
            subGraph = graphService.getSubGraph(id, depth, List.of(EdgeType.CALLS));
        }
        jsonOk(ctx, subGraph);
    }

    private void handleGetComments(Context ctx) {
        jsonOk(ctx, graphService.getComments(ctx.pathParam("id")));
    }

    private void handleGetAnnotations(Context ctx) {
        jsonOk(ctx, graphService.getAnnotations(ctx.pathParam("id")));
    }

    private void handleGetUsages(Context ctx) {
        jsonOk(ctx, graphService.getUsages(ctx.pathParam("id")));
    }

    private void handleGetImports(Context ctx) {
        jsonOk(ctx, graphService.getImports(ctx.pathParam("id")));
    }

    private void handleGetImportedBy(Context ctx) {
        jsonOk(ctx, graphService.getImportedBy(ctx.pathParam("id")));
    }

    private void handleGetSuperclass(Context ctx) {
        jsonOk(ctx, graphService.getSuperclass(ctx.pathParam("id")).orElse(null));
    }

    private void handleGetInterfaces(Context ctx) {
        jsonOk(ctx, graphService.getInterfaces(ctx.pathParam("id")));
    }

    private void handleGetSubclasses(Context ctx) {
        jsonOk(ctx, graphService.getSubclasses(ctx.pathParam("id")));
    }

    private void handleGetImplementors(Context ctx) {
        jsonOk(ctx, graphService.getImplementors(ctx.pathParam("id")));
    }

    private void handleGetOverrides(Context ctx) {
        jsonOk(ctx, graphService.getOverriddenMethod(ctx.pathParam("id")).orElse(null));
    }

    private void handleGetOverriders(Context ctx) {
        jsonOk(ctx, graphService.getOverriders(ctx.pathParam("id")));
    }

    private void handleGetParameters(Context ctx) {
        jsonOk(ctx, graphService.getChildren(ctx.pathParam("id"), ElementType.PARAMETER));
    }

    private void handleGetFields(Context ctx) {
        jsonOk(ctx, graphService.getChildren(ctx.pathParam("id"), ElementType.FIELD));
    }

    private void handleGetMethods(Context ctx) {
        jsonOk(ctx, graphService.getChildren(ctx.pathParam("id"), ElementType.METHOD));
    }

    private void handleGetRelated(Context ctx) {
        String id          = ctx.pathParam("id");
        String edgeTypeStr = ctx.queryParam("edge_type");

        if (edgeTypeStr != null && !edgeTypeStr.isBlank()) {
            try {
                EdgeType et = EdgeType.valueOf(edgeTypeStr.toUpperCase());
                List<CodeEdge> outEdges = graphService.getEdgesFrom(id, et);
                List<CodeEdge> inEdges  = graphService.getEdgesTo(id, et);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("outgoing", outEdges);
                result.put("incoming", inEdges);
                jsonOk(ctx, result);
                return;
            } catch (IllegalArgumentException ignored) {}
        }

        List<CodeEdge> outEdges = graphService.getEdgesFrom(id);
        List<CodeEdge> inEdges  = graphService.getEdgesTo(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outgoing", outEdges);
        result.put("incoming", inEdges);
        jsonOk(ctx, result);
    }

    private void handleGetElementGraph(Context ctx) {
        String id   = ctx.pathParam("id");
        int depth   = queryParamInt(ctx, "depth", 2);
        String edgeTypesParam = ctx.queryParam("edge_types");

        List<EdgeType> edgeTypes = parseEdgeTypes(edgeTypesParam);
        GraphService.SubGraph subGraph = graphService.getSubGraph(id, depth, edgeTypes);
        jsonOk(ctx, subGraph);
    }

    // -------------------------------------------------------------------------
    // File / graph handlers
    // -------------------------------------------------------------------------

    private void handleGetFileElements(Context ctx) {
        String repoId   = ctx.queryParam("repo");
        String filePath = ctx.queryParam("path");

        if (repoId == null || filePath == null) {
            ctx.status(HttpStatus.BAD_REQUEST);
            jsonError(ctx, "Query params 'repo' and 'path' are required");
            return;
        }
        jsonOk(ctx, graphService.getElementsByFile(repoId, filePath));
    }

    private void handleGetSubgraph(Context ctx) {
        String rootId         = ctx.queryParam("root");
        int depth             = queryParamInt(ctx, "depth", 2);
        String edgeTypesParam = ctx.queryParam("edges");

        if (rootId == null || rootId.isBlank()) {
            ctx.status(HttpStatus.BAD_REQUEST);
            jsonError(ctx, "Query param 'root' is required");
            return;
        }

        List<EdgeType> edgeTypes = parseEdgeTypes(edgeTypesParam);
        jsonOk(ctx, graphService.getSubGraph(rootId, depth, edgeTypes));
    }

    // -------------------------------------------------------------------------
    // FOAF / Connectivity handlers
    // -------------------------------------------------------------------------

    private PathQuery parsePathQuery(Context ctx) {
        PathQuery pq = new PathQuery();
        pq.setFromId(ctx.queryParam("from"));
        pq.setToId(ctx.queryParam("to"));

        String depthStr = ctx.queryParam("depth");
        if (depthStr != null) {
            try { pq.setMaxDepth(Integer.parseInt(depthStr)); } catch (NumberFormatException ignored) {}
        }

        String dirStr = ctx.queryParam("direction");
        if (dirStr != null) {
            try { pq.setDirection(EdgeDirection.valueOf(dirStr.toUpperCase())); } catch (Exception ignored) {}
        }

        String edgesStr = ctx.queryParam("edges");
        if (edgesStr != null && !edgesStr.isBlank()) {
            List<EdgeType> types = Arrays.stream(edgesStr.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(s -> { try { return EdgeType.valueOf(s.toUpperCase()); } catch (Exception e) { return null; } })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (!types.isEmpty()) pq.setEdgeTypes(types);
        }

        String maxPathsStr = ctx.queryParam("maxPaths");
        if (maxPathsStr != null) {
            try { pq.setMaxPaths(Integer.parseInt(maxPathsStr)); } catch (NumberFormatException ignored) {}
        }

        return pq;
    }

    private void handleShortestPath(Context ctx) {
        PathQuery pq = parsePathQuery(ctx);
        if (pq.getFromId() == null || pq.getToId() == null) {
            ctx.status(HttpStatus.BAD_REQUEST);
            jsonError(ctx, "Query params 'from' and 'to' are required");
            return;
        }
        jsonOk(ctx, graphService.findShortestPath(pq));
    }

    private void handleAllShortestPaths(Context ctx) {
        PathQuery pq = parsePathQuery(ctx);
        if (pq.getFromId() == null || pq.getToId() == null) {
            ctx.status(HttpStatus.BAD_REQUEST);
            jsonError(ctx, "Query params 'from' and 'to' are required");
            return;
        }
        jsonOk(ctx, graphService.findAllShortestPaths(pq));
    }

    private void handleAllPaths(Context ctx) {
        PathQuery pq = parsePathQuery(ctx);
        if (pq.getFromId() == null || pq.getToId() == null) {
            ctx.status(HttpStatus.BAD_REQUEST);
            jsonError(ctx, "Query params 'from' and 'to' are required");
            return;
        }
        jsonOk(ctx, graphService.findAllPaths(pq));
    }

    private void handleSimilarity(Context ctx) {
        String a = ctx.queryParam("a");
        String b = ctx.queryParam("b");
        if (a == null || b == null) {
            ctx.status(HttpStatus.BAD_REQUEST);
            jsonError(ctx, "Query params 'a' and 'b' are required");
            return;
        }

        EdgeDirection direction = EdgeDirection.BOTH;
        String dirStr = ctx.queryParam("direction");
        if (dirStr != null) {
            try { direction = EdgeDirection.valueOf(dirStr.toUpperCase()); } catch (Exception ignored) {}
        }

        List<EdgeType> edgeTypes = null;
        String edgesStr = ctx.queryParam("edges");
        if (edgesStr != null && !edgesStr.isBlank()) {
            edgeTypes = Arrays.stream(edgesStr.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(s -> { try { return EdgeType.valueOf(s.toUpperCase()); } catch (Exception e) { return null; } })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (edgeTypes.isEmpty()) edgeTypes = null;
        }

        jsonOk(ctx, graphService.computeSimilarity(a, b, direction, edgeTypes));
    }

    private void handleGetConfigs(Context ctx) {
        String repoId = ctx.queryParam("repo");
        SearchQuery sq = new SearchQuery();
        sq.setElementTypes(List.of(ElementType.CONFIG_FILE));
        sq.setLimit(200);
        if (repoId != null && !repoId.isBlank()) sq.setRepoIds(List.of(repoId));
        jsonOk(ctx, graphService.search(sq));
    }

    // -------------------------------------------------------------------------
    // Status handler
    // -------------------------------------------------------------------------

    private void handleStatus(Context ctx) {
        List<Repository> repos = graphService.listRepositories();
        long totalElements = 0;
        for (Repository repo : repos) {
            totalElements += repo.getElementCount();
        }

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("repositories", repos);
        status.put("totalRepositories", repos.size());
        status.put("totalElements", totalElements);
        status.put("serverPort", config.getServer().getPort());
        jsonOk(ctx, status);
    }

    // -------------------------------------------------------------------------
    // Directory browsing handler
    // -------------------------------------------------------------------------

    /**
     * GET /api/browse?path=/some/dir — lists directories at the given path.
     * Used by the UI directory picker. Only returns directories, not files.
     * Defaults to the user's home directory if no path is given.
     */
    private void handleBrowseDirectory(Context ctx) {
        String pathParam = ctx.queryParam("path");
        Path dir;
        if (pathParam == null || pathParam.isBlank()) {
            dir = Path.of(System.getProperty("user.home"));
        } else {
            dir = Path.of(pathParam).toAbsolutePath().normalize();
        }

        if (!Files.isDirectory(dir)) {
            ctx.status(HttpStatus.BAD_REQUEST);
            jsonError(ctx, "Not a directory: " + dir);
            return;
        }

        List<Map<String, Object>> entries = new ArrayList<>();

        // Add parent entry
        Path parent = dir.getParent();
        if (parent != null) {
            Map<String, Object> parentEntry = new LinkedHashMap<>();
            parentEntry.put("name", "..");
            parentEntry.put("path", parent.toString());
            parentEntry.put("isDirectory", true);
            entries.add(parentEntry);
        }

        // List subdirectories
        try (var stream = Files.list(dir)) {
            stream
                .filter(Files::isDirectory)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return !name.startsWith(".");
                })
                .sorted()
                .forEach(p -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", p.getFileName().toString());
                    entry.put("path", p.toString());
                    entry.put("isDirectory", true);
                    entries.add(entry);
                });
        } catch (IOException e) {
            log.warn("Error listing directory {}: {}", dir, e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentPath", dir.toString());
        result.put("entries", entries);
        // Also flag if this looks like a git repo
        result.put("isGitRepo", Files.isDirectory(dir.resolve(".git")));
        jsonOk(ctx, result);
    }

    // -------------------------------------------------------------------------
    // MCP Streamable HTTP transport handlers
    // -------------------------------------------------------------------------

    private static final String MCP_SESSION_HEADER = "Mcp-Session-Id";

    /**
     * POST /mcp — receives JSON-RPC messages from the client.
     *
     * <p>Per the MCP 2025-03-26 Streamable HTTP spec:
     * <ul>
     *   <li>If the body is an initialize request, a new session is created and
     *       the {@code Mcp-Session-Id} header is returned.</li>
     *   <li>If the body contains only notifications/responses: returns 202.</li>
     *   <li>If the client accepts {@code text/event-stream}, the response is
     *       sent as SSE events. Otherwise, JSON is returned directly.</li>
     * </ul>
     */
    private void handleMcpPost(Context ctx) {
        String sessionId = ctx.header(MCP_SESSION_HEADER);
        String body = ctx.body();

        if (body == null || body.isBlank()) {
            ctx.status(HttpStatus.BAD_REQUEST);
            jsonError(ctx, "Empty request body");
            return;
        }

        // Quick check: if body doesn't contain "initialize" and there's no valid session, reject early
        boolean looksLikeInit = body.contains("\"initialize\"");
        if (!looksLikeInit && (sessionId == null || mcpServer.getSession(sessionId).isEmpty())) {
            ctx.status(HttpStatus.BAD_REQUEST);
            jsonError(ctx, "Missing or invalid " + MCP_SESSION_HEADER + " header");
            return;
        }

        McpServer.StreamableResult result = mcpServer.handleStreamablePost(body, sessionId);

        // If this was an initialize request, set the session header
        if (result.isInitialize() && result.getSessionId() != null) {
            ctx.header(MCP_SESSION_HEADER, result.getSessionId());
        } else if (sessionId != null) {
            ctx.header(MCP_SESSION_HEADER, sessionId);
        }

        // Notification-only messages get 202 Accepted with no body
        if (result.isNotificationOnly()) {
            ctx.status(HttpStatus.ACCEPTED);
            return;
        }

        List<String> responses = result.getResponses();
        if (responses.isEmpty()) {
            ctx.status(HttpStatus.ACCEPTED);
            return;
        }

        // Check if client accepts SSE
        String accept = ctx.header("Accept");
        boolean wantsSse = accept != null && accept.contains("text/event-stream");

        if (wantsSse && !result.isInitialize()) {
            // Send responses as SSE events
            ctx.contentType("text/event-stream");
            ctx.header("Cache-Control", "no-cache");
            ctx.header("Connection", "keep-alive");

            McpServer.McpSession session = sessionId != null
                    ? mcpServer.getSession(sessionId).orElse(null)
                    : (result.getSessionId() != null
                        ? mcpServer.getSession(result.getSessionId()).orElse(null)
                        : null);

            StringBuilder sse = new StringBuilder();
            for (String response : responses) {
                String eventId = session != null ? session.nextEventId() : UUID.randomUUID().toString();
                sse.append("event: message\n");
                sse.append("id: ").append(eventId).append("\n");
                sse.append("data: ").append(response).append("\n\n");
            }
            ctx.result(sse.toString());
        } else {
            // Return as JSON
            ctx.contentType("application/json");
            if (responses.size() == 1) {
                ctx.result(responses.get(0));
            } else {
                // Batch response — wrap in JSON array
                ctx.result("[" + String.join(",", responses) + "]");
            }
        }
    }

    /**
     * GET /mcp — opens an SSE stream for server-initiated messages.
     *
     * <p>The client must include a valid {@code Mcp-Session-Id} header.
     * This endpoint is used for server-to-client notifications and for
     * resumability (via the {@code Last-Event-ID} header).
     *
     * <p>For this server, we keep the connection alive but don't currently
     * push unsolicited server notifications. The stream stays open so the
     * client has a channel for future server-initiated messages.
     */
    private void handleMcpGet(Context ctx) {
        String sessionId = ctx.header(MCP_SESSION_HEADER);
        if (sessionId == null || mcpServer.getSession(sessionId).isEmpty()) {
            ctx.status(HttpStatus.BAD_REQUEST);
            jsonError(ctx, "Missing or invalid " + MCP_SESSION_HEADER + " header");
            return;
        }

        // SSE stream: keep connection alive for potential server-initiated messages
        // For now we just send an initial ping and keep the stream open
        ctx.contentType("text/event-stream");
        ctx.header("Cache-Control", "no-cache");
        ctx.header("Connection", "keep-alive");
        ctx.header(MCP_SESSION_HEADER, sessionId);

        // Send an empty comment to keep the connection alive
        ctx.result(": mcp stream connected\n\n");
    }

    /**
     * DELETE /mcp — terminates a session.
     */
    private void handleMcpDelete(Context ctx) {
        String sessionId = ctx.header(MCP_SESSION_HEADER);
        if (sessionId == null) {
            ctx.status(HttpStatus.BAD_REQUEST);
            jsonError(ctx, "Missing " + MCP_SESSION_HEADER + " header");
            return;
        }

        if (mcpServer.deleteSession(sessionId)) {
            ctx.status(HttpStatus.NO_CONTENT);
        } else {
            ctx.status(HttpStatus.NOT_FOUND);
            jsonError(ctx, "Session not found: " + sessionId);
        }
    }

    // -------------------------------------------------------------------------
    // Indexing trigger (called async from handleAddRepo / handleReindex)
    // -------------------------------------------------------------------------

    /**
     * Updates the repo status to INDEXING, walks the directory tree, parses
     * each file, and saves results. This is intentionally kept simple here —
     * the full-featured indexing lives in the indexer module; this provides a
     * lightweight path for repos added dynamically via the REST API.
     */
    private void triggerIndexing(Repository repo) {
        log.info("Starting indexing for repo: {}", repo.getId());

        try {
            if (!(graphService instanceof IndexerService indexerService)) {
                log.error("GraphService is not an IndexerService — cannot index repo {}", repo.getId());
                repo.setStatus(Repository.IndexingStatus.ERROR);
                graphService.saveRepository(repo);
                return;
            }

            RepoConfig repoConfig = new RepoConfig();
            repoConfig.setId(repo.getId());
            repoConfig.setName(repo.getName());
            repoConfig.setPath(repo.getRootPath());
            if (repo.getLanguages() != null) {
                repoConfig.setLanguages(repo.getLanguages());
            }
            if (repo.getDescription() != null) {
                repoConfig.setDescription(repo.getDescription());
            }

            indexerService.indexRepository(repoConfig);
            log.info("Indexing complete for repo: {}", repo.getId());

            // Auto-start watching if index management is available
            if (indexManagement != null) {
                indexManagement.startWatching(repo.getId());
            }
        } catch (Exception e) {
            log.error("Indexing failed for repo {}: {}", repo.getId(), e.getMessage(), e);
            repo.setStatus(Repository.IndexingStatus.ERROR);
            graphService.saveRepository(repo);
        }
    }

    // -------------------------------------------------------------------------
    // File watcher handlers
    // -------------------------------------------------------------------------

    private void handleWatcherStatus(Context ctx) {
        if (indexManagement == null) {
            jsonOk(ctx, Map.of("watching", false, "message", "Index management not configured"));
            return;
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("watching", indexManagement.isWatching());
        status.put("watchedRepos", indexManagement.getWatchedRepoIds());
        status.put("watchedDirectories", indexManagement.getWatchedDirectoryCount());
        jsonOk(ctx, status);
    }

    private void handleRecentChanges(Context ctx) {
        if (indexManagement == null) {
            jsonOk(ctx, List.of());
            return;
        }
        String repoId = ctx.queryParam("repo");
        int limit = queryParamInt(ctx, "limit", 50);
        List<IndexManagementService.FileChange> changes = repoId != null
                ? indexManagement.getRecentChanges(repoId, limit)
                : indexManagement.getRecentChanges(limit);
        jsonOk(ctx, changes);
    }

    private void handleStartWatching(Context ctx) {
        String id = ctx.pathParam("id");
        if (indexManagement == null) {
            ctx.status(HttpStatus.SERVICE_UNAVAILABLE);
            jsonError(ctx, "Index management not configured");
            return;
        }
        boolean started = indexManagement.startWatching(id);
        if (started) {
            jsonOk(ctx, Map.of("watching", true, "repoId", id));
        } else {
            ctx.status(HttpStatus.NOT_FOUND);
            jsonError(ctx, "Repository not found: " + id);
        }
    }

    private void handleStopWatching(Context ctx) {
        String id = ctx.pathParam("id");
        if (indexManagement == null) {
            ctx.status(HttpStatus.SERVICE_UNAVAILABLE);
            jsonError(ctx, "Index management not configured");
            return;
        }
        indexManagement.stopWatching(id);
        jsonOk(ctx, Map.of("watching", false, "repoId", id));
    }

    // -------------------------------------------------------------------------
    // Snippet extraction
    // -------------------------------------------------------------------------

    /**
     * Reads the source file from disk and extracts lines around the element,
     * adding {@code context} lines above and below.
     */
    private String extractSnippet(CodeElement el, int context) {
        if (el.getFilePath() == null) {
            return el.getSnippet() != null ? el.getSnippet() : "";
        }

        // Resolve the absolute path: try repo root paths
        Path resolvedFile = resolveFilePath(el);
        if (resolvedFile == null || !Files.exists(resolvedFile)) {
            return el.getSnippet() != null ? el.getSnippet() : "";
        }

        try {
            List<String> lines = Files.readAllLines(resolvedFile);
            int totalLines = lines.size();
            int start = Math.max(0, el.getLineStart() - 1 - context);
            int end   = Math.min(totalLines, el.getLineEnd() + context);
            return String.join("\n", lines.subList(start, end));
        } catch (IOException e) {
            log.warn("Could not read file for snippet {}: {}", resolvedFile, e.getMessage());
            return el.getSnippet() != null ? el.getSnippet() : "";
        }
    }

    private Path resolveFilePath(CodeElement el) {
        // Try each repo's root path
        for (Repository repo : graphService.listRepositories()) {
            if (repo.getId().equals(el.getRepoId()) && repo.getRootPath() != null) {
                Path candidate = Path.of(repo.getRootPath()).resolve(el.getFilePath()).normalize();
                if (Files.exists(candidate)) return candidate;
            }
        }
        // Try treating it as an absolute path
        try {
            Path abs = Path.of(el.getFilePath());
            if (abs.isAbsolute() && Files.exists(abs)) return abs;
        } catch (Exception ignored) {}
        return null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void jsonOk(Context ctx, Object value) {
        ctx.contentType("application/json");
        try {
            ctx.result(mapper.writeValueAsString(value));
        } catch (Exception e) {
            log.error("Failed to serialize response: {}", e.getMessage(), e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).result("{\"error\":\"Serialization error\"}");
        }
    }

    private void jsonError(Context ctx, String message) {
        ctx.contentType("application/json");
        try {
            ctx.result(mapper.writeValueAsString(Map.of("error", message)));
        } catch (Exception e) {
            ctx.result("{\"error\":\"" + message.replace("\"", "'") + "\"}");
        }
    }

    private int queryParamInt(Context ctx, String name, int defaultValue) {
        String val = ctx.queryParam(name);
        if (val == null) return defaultValue;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return defaultValue; }
    }

    private String getString(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val instanceof String s ? s : null;
    }

    private List<EdgeType> parseEdgeTypes(String param) {
        if (param == null || param.isBlank()) return List.of();
        return Arrays.stream(param.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> { try { return EdgeType.valueOf(s.toUpperCase()); } catch (Exception e) { return null; } })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
