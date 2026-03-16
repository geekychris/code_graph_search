package com.codegraph.tests;

import com.codegraph.mcp.McpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests the MCP server protocol and all tool implementations.
 * Uses the McpServer in HTTP mode (POST /mcp) to test via the in-process handler.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpServerTest extends BaseIntegrationTest {

    private McpServer mcpServer;

    @BeforeAll
    @Override
    void setUpIndexer() throws Exception {
        super.setUpIndexer();
        mcpServer = new McpServer(graphService, mapper);
        mcpServer.registerAllTools();
    }

    // ---- Helper ----

    /** Simulate sending a tools/call JSON-RPC request and get the result text. */
    private JsonNode callTool(String toolName, ObjectNode args) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", args);
        request.set("params", params);

        String response = mcpServer.handleHttpRequest(mapper.writeValueAsString(request));
        assertThat(response).isNotNull();
        JsonNode responseNode = mapper.readTree(response);
        assertThat(responseNode.has("result")).as("Response should have result, got: " + response).isTrue();
        return responseNode.path("result");
    }

    private JsonNode callTool(String toolName) throws Exception {
        return callTool(toolName, mapper.createObjectNode());
    }

    private String getFirstClassId(String name) throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", name);
        args.put("limit", 5);
        var searchResult = callTool("search_code", args);
        // Result should contain the element details
        String text = searchResult.path("content").get(0).path("text").asText();
        assertThat(text).isNotBlank();
        // The text should mention the element - we need to extract an ID from it
        // Find element via graphService directly
        var q = new com.codegraph.core.model.SearchQuery(name);
        q.setElementTypes(java.util.List.of(com.codegraph.core.model.ElementType.CLASS));
        var results = graphService.search(q);
        assertThat(results).isNotEmpty();
        return results.stream()
            .filter(e -> e.getName().equals(name))
            .findFirst()
            .orElse(results.get(0))
            .getId();
    }

    // ---- Protocol tests ----

    @Test
    @Order(1)
    void initializeHandshakeWorks() throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "initialize");
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "test-client");
        params.set("clientInfo", clientInfo);
        request.set("params", params);

        String response = mcpServer.handleHttpRequest(mapper.writeValueAsString(request));
        JsonNode responseNode = mapper.readTree(response);
        assertThat(responseNode.path("result").has("serverInfo")).isTrue();
        assertThat(responseNode.path("result").has("capabilities")).isTrue();
    }

    @Test
    @Order(2)
    void toolsListReturnsAllTools() throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 2);
        request.put("method", "tools/list");

        String response = mcpServer.handleHttpRequest(mapper.writeValueAsString(request));
        JsonNode responseNode = mapper.readTree(response);
        var tools = responseNode.path("result").path("tools");
        assertThat(tools.isArray()).isTrue();
        assertThat(tools.size()).isGreaterThanOrEqualTo(20);

        // Verify key tools are present
        boolean hasSearch = false, hasGetElement = false, hasGetCallers = false;
        for (var tool : tools) {
            String name = tool.path("name").asText();
            if (name.equals("search_code")) hasSearch = true;
            if (name.equals("get_element")) hasGetElement = true;
            if (name.equals("get_callers")) hasGetCallers = true;
        }
        assertThat(hasSearch).as("search_code tool must exist").isTrue();
        assertThat(hasGetElement).as("get_element tool must exist").isTrue();
        assertThat(hasGetCallers).as("get_callers tool must exist").isTrue();
    }

    @Test
    @Order(3)
    void unknownMethodReturnsError() throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 3);
        request.put("method", "unknown/method");

        String response = mcpServer.handleHttpRequest(mapper.writeValueAsString(request));
        JsonNode responseNode = mapper.readTree(response);
        assertThat(responseNode.has("error")).isTrue();
    }

    // ---- Search tools ----

    @Test
    @Order(10)
    void searchCodeToolFindsJavaClasses() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", "CodeElement");
        var result = callTool("search_code", args);
        String text = result.path("content").get(0).path("text").asText();
        assertThat(text).containsIgnoringCase("CodeElement");
        assertThat(text).contains("CLASS");
    }

    @Test
    @Order(11)
    void searchCodeToolWithTypeFilter() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", "get");
        var types = mapper.createArrayNode();
        types.add("METHOD");
        args.set("element_types", types);
        var result = callTool("search_code", args);
        String text = result.path("content").get(0).path("text").asText();
        assertThat(text).isNotBlank();
        assertThat(text).contains("METHOD");
    }

    @Test
    @Order(12)
    void searchByNameToolWorks() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("name", "GraphService");
        var result = callTool("search_by_name", args);
        String text = result.path("content").get(0).path("text").asText();
        assertThat(text).containsIgnoringCase("GraphService");
    }

    // ---- Element tools ----

    @Test
    @Order(20)
    void getElementToolWorks() throws Exception {
        String classId = getFirstClassId("CodeElement");
        ObjectNode args = mapper.createObjectNode();
        args.put("id", classId);
        var result = callTool("get_element", args);
        String text = result.path("content").get(0).path("text").asText();
        assertThat(text).containsIgnoringCase("CodeElement");
        assertThat(text).containsIgnoringCase("CLASS");
    }

    @Test
    @Order(21)
    void getSnippetToolWorks() throws Exception {
        String classId = getFirstClassId("CodeElement");
        ObjectNode args = mapper.createObjectNode();
        args.put("id", classId);
        args.put("context_lines", 3);
        var result = callTool("get_snippet", args);
        String text = result.path("content").get(0).path("text").asText();
        assertThat(text).isNotBlank();
        assertThat(text).containsAnyOf("CodeElement", "class", "```");
    }

    @Test
    @Order(22)
    void getFileOutlineToolWorks() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("file_path", "core/src/main/java/com/codegraph/core/model/CodeElement.java");
        args.put("repo_id", "code-graph-search");
        var result = callTool("get_file_outline", args);
        String text = result.path("content").get(0).path("text").asText();
        assertThat(text).isNotBlank();
    }

    // ---- Structure tools ----

    @Test
    @Order(30)
    void getChildrenToolWorks() throws Exception {
        String classId = getFirstClassId("CodeElement");
        ObjectNode args = mapper.createObjectNode();
        args.put("id", classId);
        var result = callTool("get_children", args);
        String text = result.path("content").get(0).path("text").asText();
        assertThat(text).isNotBlank();
    }

    @Test
    @Order(31)
    void getMethodsToolWorks() throws Exception {
        String classId = getFirstClassId("CodeElement");
        ObjectNode args = mapper.createObjectNode();
        args.put("id", classId);
        var result = callTool("get_methods", args);
        String text = result.path("content").get(0).path("text").asText();
        assertThat(text).containsAnyOf("METHOD", "CONSTRUCTOR", "method", "constructor");
    }

    @Test
    @Order(32)
    void getFieldsToolWorks() throws Exception {
        String classId = getFirstClassId("CodeElement");
        ObjectNode args = mapper.createObjectNode();
        args.put("id", classId);
        var result = callTool("get_fields", args);
        String text = result.path("content").get(0).path("text").asText();
        assertThat(text).isNotBlank();
    }

    @Test
    @Order(33)
    void getSiblingsToolWorks() throws Exception {
        String classId = getFirstClassId("SearchQuery");
        var children = graphService.getChildren(classId, com.codegraph.core.model.ElementType.METHOD);
        if (!children.isEmpty()) {
            ObjectNode args = mapper.createObjectNode();
            args.put("id", children.get(0).getId());
            var result = callTool("get_siblings", args);
            String text = result.path("content").get(0).path("text").asText();
            assertThat(text).isNotBlank();
        }
    }

    @Test
    @Order(34)
    void getParentToolWorks() throws Exception {
        String classId = getFirstClassId("CodeElement");
        var methods = graphService.getChildren(classId, com.codegraph.core.model.ElementType.METHOD);
        if (!methods.isEmpty()) {
            ObjectNode args = mapper.createObjectNode();
            args.put("id", methods.get(0).getId());
            var result = callTool("get_parent", args);
            String text = result.path("content").get(0).path("text").asText();
            assertThat(text).containsAnyOf("CLASS", "CodeElement", "class");
        }
    }

    @Test
    @Order(35)
    void getAncestorsToolWorks() throws Exception {
        String classId = getFirstClassId("CodeElement");
        var methods = graphService.getChildren(classId, com.codegraph.core.model.ElementType.METHOD);
        if (!methods.isEmpty()) {
            ObjectNode args = mapper.createObjectNode();
            args.put("id", methods.get(0).getId());
            var result = callTool("get_ancestors", args);
            assertThat(result.path("content").get(0).path("text").asText()).isNotBlank();
        }
    }

    // ---- Documentation tools ----

    @Test
    @Order(40)
    void getCommentsToolWorks() throws Exception {
        String classId = getFirstClassId("CodeElement");
        ObjectNode args = mapper.createObjectNode();
        args.put("id", classId);
        var result = callTool("get_comments", args);
        assertThat(result.path("content").get(0).path("text").asText()).isNotNull();
    }

    @Test
    @Order(41)
    void getAnnotationsToolWorks() throws Exception {
        String classId = getFirstClassId("CodeElement");
        ObjectNode args = mapper.createObjectNode();
        args.put("id", classId);
        var result = callTool("get_annotations", args);
        assertThat(result.path("content").get(0).path("text").asText()).isNotNull();
    }

    @Test
    @Order(42)
    void getReadmeToolWorks() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("repo_id", "code-graph-search");
        var result = callTool("get_readme", args);
        // May return "not found" if no README, but should not error
        assertThat(result.path("content").get(0).path("text").asText()).isNotNull();
    }

    // ---- Repo tools ----

    @Test
    @Order(50)
    void listReposToolWorks() throws Exception {
        var result = callTool("list_repos");
        String text = result.path("content").get(0).path("text").asText();
        assertThat(text).containsIgnoringCase("code-graph-search");
    }

    @Test
    @Order(51)
    void getRepoStructureToolWorks() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("repo_id", "code-graph-search");
        args.put("max_depth", 2);
        var result = callTool("get_repo_structure", args);
        String text = result.path("content").get(0).path("text").asText();
        assertThat(text).isNotBlank();
    }

    @Test
    @Order(52)
    void listConfigsToolWorks() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("repo_id", "code-graph-search");
        var result = callTool("list_configs", args);
        assertThat(result.path("content").get(0).path("text").asText()).isNotNull();
    }

    @Test
    @Order(53)
    void getRelatedToolWorks() throws Exception {
        String classId = getFirstClassId("CodeElement");
        ObjectNode args = mapper.createObjectNode();
        args.put("id", classId);
        var result = callTool("get_related", args);
        String text = result.path("content").get(0).path("text").asText();
        assertThat(text).isNotBlank();
    }

    // ---- Call graph tools ----

    @Test
    @Order(60)
    void getCalleesToolWorks() throws Exception {
        // Find a method that calls other methods
        var q = new com.codegraph.core.model.SearchQuery("generateId");
        q.setElementTypes(java.util.List.of(com.codegraph.core.model.ElementType.METHOD));
        var methods = graphService.search(q);
        if (!methods.isEmpty()) {
            ObjectNode args = mapper.createObjectNode();
            args.put("id", methods.get(0).getId());
            var result = callTool("get_callees", args);
            assertThat(result.path("content").get(0).path("text").asText()).isNotNull();
        }
    }

    @Test
    @Order(61)
    void getCallersToolWorks() throws Exception {
        var q = new com.codegraph.core.model.SearchQuery("getId");
        q.setElementTypes(java.util.List.of(com.codegraph.core.model.ElementType.METHOD));
        var methods = graphService.search(q);
        if (!methods.isEmpty()) {
            ObjectNode args = mapper.createObjectNode();
            args.put("id", methods.get(0).getId());
            var result = callTool("get_callers", args);
            assertThat(result.path("content").get(0).path("text").asText()).isNotNull();
        }
    }

    // ---- Error handling ----

    @Test
    @Order(70)
    void getElementWithInvalidIdReturnsGracefulError() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("id", "nonexistent-element-id-xyz");
        var result = callTool("get_element", args);
        String text = result.path("content").get(0).path("text").asText();
        assertThat(text).containsAnyOf("not found", "error", "No element");
    }

    @Test
    @Order(71)
    void searchWithNoResultsReturnsEmptyGracefully() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", "xyznonexistentquerythatshouldmatchnothing12345");
        var result = callTool("search_code", args);
        String text = result.path("content").get(0).path("text").asText();
        assertThat(text).isNotNull();
        assertThat(text).containsAnyOf("No results", "0 results", "found 0", "empty");
    }
}
