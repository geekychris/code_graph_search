package com.codegraph.mcp;

import com.codegraph.core.model.*;
import com.codegraph.core.service.GraphService;
import com.codegraph.core.service.IndexManagementService;
import com.codegraph.mcp.tools.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP (Model Context Protocol) server over JSON-RPC 2.0.
 *
 * <p>Supports three transport modes:
 * <ul>
 *   <li><b>Stdio</b>: reads one JSON-RPC message per line from stdin, writes responses to stdout.</li>
 *   <li><b>Streamable HTTP</b>: single {@code /mcp} endpoint supporting POST (JSON or SSE response),
 *       GET (SSE stream for server-initiated messages), and DELETE (session termination).
 *       Implements the MCP 2025-03-26 Streamable HTTP transport specification.</li>
 *   <li><b>Legacy HTTP</b>: simple POST with JSON response via {@link #handleHttpRequest(String)}.</li>
 * </ul>
 */
public class McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);

    private static final String SERVER_NAME = "code-graph-search";
    private static final String SERVER_VERSION = "1.0.0";
    private static final String PROTOCOL_VERSION = "2025-03-26";

    private final GraphService graphService;
    private final ObjectMapper mapper;
    private final ToolRegistry registry;
    private IndexManagementService indexManagement;

    /** Active sessions keyed by session ID. */
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();

    public McpServer(GraphService graphService, ObjectMapper mapper) {
        this.graphService = graphService;
        this.mapper = mapper;
        this.registry = new ToolRegistry();
        registerAllTools();
    }

    /**
     * Sets the index management service for index management MCP tools.
     * Must be called before tools are used (but after construction is fine
     * since tools are registered lazily when this is set).
     */
    public void setIndexManagement(IndexManagementService indexManagement) {
        this.indexManagement = indexManagement;
        registerIndexManagementTools();
    }

    // -------------------------------------------------------------------------
    // Tool registration
    // -------------------------------------------------------------------------

    public void registerAllTools() {
        // Search
        registry.register(new SearchTools.SearchCode(graphService));
        registry.register(new SearchTools.SearchByName(graphService));

        // Element inspection
        registry.register(new ElementTools.GetElement(graphService));
        registry.register(new ElementTools.GetSnippet(graphService));
        registry.register(new ElementTools.GetFileOutline(graphService));
        registry.register(new ElementTools.GetElementsAtLocation(graphService));

        // Structure navigation
        registry.register(new StructureTools.GetParent(graphService));
        registry.register(new StructureTools.GetAncestors(graphService));
        registry.register(new StructureTools.GetChildren(graphService));
        registry.register(new StructureTools.GetSiblings(graphService));
        registry.register(new StructureTools.GetMethods(graphService));
        registry.register(new StructureTools.GetFields(graphService));
        registry.register(new StructureTools.GetConstructors(graphService));

        // Type hierarchy
        registry.register(new TypeHierarchyTools.GetSuperclass(graphService));
        registry.register(new TypeHierarchyTools.GetInterfaces(graphService));
        registry.register(new TypeHierarchyTools.GetSubclasses(graphService));
        registry.register(new TypeHierarchyTools.GetImplementors(graphService));
        registry.register(new TypeHierarchyTools.GetOverrides(graphService));
        registry.register(new TypeHierarchyTools.GetOverrideChain(graphService));
        registry.register(new TypeHierarchyTools.GetOverriders(graphService));

        // Call graph
        registry.register(new CallGraphTools.GetCallers(graphService));
        registry.register(new CallGraphTools.GetCallees(graphService));
        registry.register(new CallGraphTools.GetCallChain(graphService));
        registry.register(new CallGraphTools.GetCallHierarchy(graphService));

        // Connectivity / FOAF
        registry.register(new ConnectivityTools.FindShortestPath(graphService));
        registry.register(new ConnectivityTools.FindAllShortestPaths(graphService));
        registry.register(new ConnectivityTools.FindAllPaths(graphService));
        registry.register(new ConnectivityTools.GetSimilarity(graphService));

        // Documentation
        registry.register(new DocumentationTools.GetComments(graphService));
        registry.register(new DocumentationTools.GetAnnotations(graphService));
        registry.register(new DocumentationTools.GetParameters(graphService));
        registry.register(new DocumentationTools.GetReadme(graphService));
        registry.register(new DocumentationTools.GetDocs(graphService));

        // Imports / usages
        registry.register(new ImportTools.GetImports(graphService));
        registry.register(new ImportTools.GetImportedBy(graphService));
        registry.register(new ImportTools.GetUsages(graphService));
        registry.register(new ImportTools.GetDependencies(graphService));

        // Repo-level
        registry.register(new RepoTools.ListRepos(graphService));
        registry.register(new RepoTools.GetRepoStructure(graphService));
        registry.register(new RepoTools.ListConfigs(graphService));
        registry.register(new RepoTools.GetConfig(graphService));
        registry.register(new RepoTools.GetRelated(graphService));
        registry.register(new RepoTools.FindCrossLanguage(graphService));

        log.info("Registered {} MCP tools", registry.size());
    }

    private void registerIndexManagementTools() {
        if (indexManagement == null) return;
        registry.register(new IndexManagementTools.ReindexRepo(indexManagement));
        registry.register(new IndexManagementTools.AddRepo(indexManagement));
        registry.register(new IndexManagementTools.RemoveRepo(indexManagement));
        registry.register(new IndexManagementTools.GetWatchStatus(indexManagement));
        registry.register(new IndexManagementTools.ListRecentChanges(indexManagement));
        registry.register(new IndexManagementTools.StartWatching(indexManagement));
        registry.register(new IndexManagementTools.StopWatching(indexManagement));
        log.info("Registered index management MCP tools (total: {})", registry.size());
    }

    // -------------------------------------------------------------------------
    // Session management
    // -------------------------------------------------------------------------

    /**
     * Represents an active MCP session for the Streamable HTTP transport.
     */
    public static class McpSession {
        private final String id;
        private final long createdAt;
        private final AtomicLong eventCounter = new AtomicLong(0);
        private volatile boolean initialized = false;

        public McpSession(String id) {
            this.id = id;
            this.createdAt = System.currentTimeMillis();
        }

        public String getId() { return id; }
        public long getCreatedAt() { return createdAt; }
        public boolean isInitialized() { return initialized; }
        public void setInitialized(boolean initialized) { this.initialized = initialized; }
        public String nextEventId() { return id.substring(0, 8) + "-" + eventCounter.incrementAndGet(); }
    }

    /**
     * Creates a new session with a cryptographically random ID.
     */
    public McpSession createSession() {
        String sessionId = UUID.randomUUID().toString();
        McpSession session = new McpSession(sessionId);
        sessions.put(sessionId, session);
        log.info("Created MCP session: {}", sessionId);
        return session;
    }

    /**
     * Looks up an existing session by ID.
     */
    public Optional<McpSession> getSession(String sessionId) {
        if (sessionId == null) return Optional.empty();
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * Terminates a session.
     */
    public boolean deleteSession(String sessionId) {
        McpSession removed = sessions.remove(sessionId);
        if (removed != null) {
            log.info("Terminated MCP session: {}", sessionId);
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Streamable HTTP transport - message handling
    // -------------------------------------------------------------------------

    /**
     * Result of processing a POST to the MCP endpoint.
     * Contains zero or more JSON-RPC response strings plus metadata.
     */
    public static class StreamableResult {
        private final List<String> responses;
        private final String sessionId;
        private final boolean isInitialize;
        private final boolean isNotificationOnly;

        public StreamableResult(List<String> responses, String sessionId,
                                boolean isInitialize, boolean isNotificationOnly) {
            this.responses = responses;
            this.sessionId = sessionId;
            this.isInitialize = isInitialize;
            this.isNotificationOnly = isNotificationOnly;
        }

        public List<String> getResponses() { return responses; }
        public String getSessionId() { return sessionId; }
        public boolean isInitialize() { return isInitialize; }
        public boolean isNotificationOnly() { return isNotificationOnly; }
    }

    /**
     * Handles a POST body for the Streamable HTTP transport.
     * The body can be a single JSON-RPC message or an array (batch).
     *
     * @param body     the raw POST body
     * @param sessionId the Mcp-Session-Id header value (null for initialize)
     * @return a StreamableResult with responses and session info
     */
    public StreamableResult handleStreamablePost(String body, String sessionId) {
        body = body.strip();
        boolean isBatch = body.startsWith("[");

        List<Map<String, Object>> messages;
        try {
            if (isBatch) {
                messages = mapper.readValue(body, new TypeReference<>() {});
            } else {
                Map<String, Object> single = mapper.readValue(body, new TypeReference<>() {});
                messages = List.of(single);
            }
        } catch (Exception e) {
            log.warn("Failed to parse JSON-RPC message: {}", e.getMessage());
            String error = buildError(null, -32700, "Parse error: " + e.getMessage());
            return new StreamableResult(List.of(error), sessionId, false, false);
        }

        List<String> responses = new ArrayList<>();
        boolean hasRequests = false;
        boolean isInitializeRequest = false;
        String newSessionId = sessionId;

        for (Map<String, Object> msg : messages) {
            Object idObj = msg.get("id");
            String method = (String) msg.get("method");

            if (method == null && idObj != null) {
                // This is a response from the client (not applicable for our server role)
                continue;
            }

            if (idObj == null) {
                // Notification - handle but no response
                if (method != null) {
                    handleNotification(method, msg);
                }
                continue;
            }

            // It's a request (has both id and method)
            hasRequests = true;

            if ("initialize".equals(method)) {
                isInitializeRequest = true;
                McpSession session = createSession();
                newSessionId = session.getId();

                try {
                    String response = handleInitialize(idObj, msg);
                    responses.add(response);
                    session.setInitialized(true);
                } catch (Exception e) {
                    responses.add(buildError(idObj, -32603, "Internal error: " + e.getMessage()));
                }
            } else {
                // Validate session for non-initialize requests
                if (sessionId == null || !sessions.containsKey(sessionId)) {
                    responses.add(buildError(idObj, -32600, "Invalid or missing session"));
                    continue;
                }

                try {
                    String response = switch (method) {
                        case "tools/list" -> handleToolsList(idObj);
                        case "tools/call" -> handleToolsCall(idObj, msg);
                        case "ping" -> buildResult(idObj, Map.of());
                        default -> {
                            log.debug("Unknown method: {}", method);
                            yield buildError(idObj, -32601, "Method not found: " + method);
                        }
                    };
                    responses.add(response);
                } catch (Exception e) {
                    log.error("Error handling method {}: {}", method, e.getMessage(), e);
                    responses.add(buildError(idObj, -32603, "Internal error: " + e.getMessage()));
                }
            }
        }

        boolean notificationOnly = !hasRequests;
        return new StreamableResult(responses, newSessionId, isInitializeRequest, notificationOnly);
    }

    // -------------------------------------------------------------------------
    // Stdio transport
    // -------------------------------------------------------------------------

    public void runStdio() {
        PrintStream mcpOut = System.out;
        log.info("MCP server starting in stdio mode with {} tools", registry.size());

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;

                String response = handleMessage(line);
                if (response != null) {
                    mcpOut.println(response);
                    mcpOut.flush();
                }
            }
        } catch (Exception e) {
            log.error("Fatal error in stdio loop", e);
        }
        log.info("MCP server stdio loop ended");
    }

    // -------------------------------------------------------------------------
    // Legacy HTTP handler (kept for backward compat and tests)
    // -------------------------------------------------------------------------

    public String handleHttpRequest(String requestBody) {
        return handleMessage(requestBody);
    }

    public String toolsJson() {
        try {
            return mapper.writeValueAsString(registry.listTools());
        } catch (Exception e) {
            log.error("Failed to serialize tools", e);
            return "[]";
        }
    }

    // -------------------------------------------------------------------------
    // Core message dispatch (used by stdio and legacy HTTP)
    // -------------------------------------------------------------------------

    public String handleMessage(String rawMessage) {
        Map<String, Object> request;
        try {
            request = mapper.readValue(rawMessage, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse JSON-RPC message: {}", e.getMessage());
            return buildError(null, -32700, "Parse error: " + e.getMessage());
        }

        Object idObj = request.get("id");
        String method = (String) request.get("method");

        if (method == null) {
            return buildError(idObj, -32600, "Invalid request: missing method");
        }

        if (idObj == null) {
            handleNotification(method, request);
            return null;
        }

        try {
            return switch (method) {
                case "initialize" -> handleInitialize(idObj, request);
                case "tools/list" -> handleToolsList(idObj);
                case "tools/call" -> handleToolsCall(idObj, request);
                case "ping" -> buildResult(idObj, Map.of());
                default -> {
                    log.debug("Unknown method: {}", method);
                    yield buildError(idObj, -32601, "Method not found: " + method);
                }
            };
        } catch (Exception e) {
            log.error("Error handling method {}: {}", method, e.getMessage(), e);
            return buildError(idObj, -32603, "Internal error: " + e.getMessage());
        }
    }

    private void handleNotification(String method, Map<String, Object> request) {
        switch (method) {
            case "initialized" -> log.info("Client sent 'initialized' notification");
            case "notifications/cancelled" -> log.debug("Client cancelled a request");
            default -> log.debug("Received notification: {}", method);
        }
    }

    // -------------------------------------------------------------------------
    // Method handlers
    // -------------------------------------------------------------------------

    private String handleInitialize(Object id, Map<String, Object> request) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.getOrDefault("params", Map.of());
        String clientProtocol = (String) params.get("protocolVersion");
        log.info("Initialize from client, protocol version: {}", clientProtocol);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);

        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        result.put("serverInfo", serverInfo);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        Map<String, Object> toolsCap = new LinkedHashMap<>();
        toolsCap.put("listChanged", false);
        capabilities.put("tools", toolsCap);
        result.put("capabilities", capabilities);

        return buildResult(id, result);
    }

    private String handleToolsList(Object id) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", registry.listTools());
        return buildResult(id, result);
    }

    private String handleToolsCall(Object id, Map<String, Object> request) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.get("params");
        if (params == null) {
            return buildError(id, -32602, "Invalid params: missing params object");
        }

        String toolName = (String) params.get("name");
        if (toolName == null || toolName.isBlank()) {
            return buildError(id, -32602, "Invalid params: missing tool name");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", Map.of());

        Optional<McpTool> toolOpt = registry.getTool(toolName);
        if (toolOpt.isEmpty()) {
            return buildError(id, -32601, "Tool not found: " + toolName);
        }

        McpTool tool = toolOpt.get();
        log.debug("Calling tool: {} with args: {}", toolName, arguments);

        try {
            Object toolResult = tool.execute(arguments);
            String text = toolResult instanceof String s ? s : mapper.writeValueAsString(toolResult);

            Map<String, Object> content = new LinkedHashMap<>();
            content.put("type", "text");
            content.put("text", text);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(content));
            result.put("isError", false);

            return buildResult(id, result);
        } catch (Exception e) {
            log.warn("Tool {} threw exception: {}", toolName, e.getMessage(), e);

            Map<String, Object> content = new LinkedHashMap<>();
            content.put("type", "text");
            content.put("text", "Error executing tool '" + toolName + "': " + e.getMessage());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(content));
            result.put("isError", true);

            return buildResult(id, result);
        }
    }

    // -------------------------------------------------------------------------
    // Formatting helpers
    // -------------------------------------------------------------------------

    public String formatElementList(List<CodeElement> elements) {
        if (elements == null || elements.isEmpty()) return "(empty list)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            sb.append(i + 1).append(". ");
            SearchTools.appendElementSummary(sb, elements.get(i));
            sb.append("\n");
        }
        return sb.toString();
    }

    public String formatElement(CodeElement element) {
        return ElementTools.formatElementFull(element);
    }

    public String formatEdgeList(List<CodeEdge> edges) {
        if (edges == null || edges.isEmpty()) return "(no edges)";
        StringBuilder sb = new StringBuilder();
        for (CodeEdge e : edges) {
            sb.append("  [").append(e.getEdgeType()).append("] ")
              .append(e.getFromId()).append(" -> ").append(e.getToId()).append("\n");
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // JSON-RPC builders
    // -------------------------------------------------------------------------

    private String buildResult(Object id, Object result) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("result", result);
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("Failed to serialize result", e);
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Serialization error\"}}";
        }
    }

    private String buildError(Object id, int code, String message) {
        try {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", code);
            error.put("message", message);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("error", error);
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Serialization error\"}}";
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public ToolRegistry getRegistry() { return registry; }
    public GraphService getGraphService() { return graphService; }
    public ObjectMapper getMapper() { return mapper; }
}
