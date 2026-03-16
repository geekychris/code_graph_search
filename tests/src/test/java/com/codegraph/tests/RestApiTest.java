package com.codegraph.tests;

import com.codegraph.core.config.AppConfig;
import com.codegraph.core.model.CodeElement;
import com.codegraph.core.model.ElementType;
import com.codegraph.core.model.Language;
import com.codegraph.core.model.SearchQuery;
import com.codegraph.mcp.McpServer;
import com.codegraph.rest.RestServer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.*;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for the REST API. Starts a real server and fires HTTP requests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RestApiTest extends BaseIntegrationTest {

    private static final int PORT = 18080;
    private RestServer restServer;
    private OkHttpClient http;
    private String baseUrl;

    @BeforeAll
    @Override
    void setUpIndexer() throws Exception {
        super.setUpIndexer();

        // Start REST server on a test port
        AppConfig config = new AppConfig();
        AppConfig.ServerConfig serverConfig = new AppConfig.ServerConfig();
        serverConfig.setPort(PORT);
        serverConfig.setCorsEnabled(true);
        serverConfig.setMcpHttpEnabled(true);
        config.setServer(serverConfig);

        McpServer mcpServer = new McpServer(graphService, mapper);
        mcpServer.registerAllTools();

        restServer = new RestServer(graphService, mcpServer, config, mapper);
        restServer.start();

        http = new OkHttpClient();
        baseUrl = "http://localhost:" + PORT;
    }

    @AfterAll
    @Override
    void tearDown() throws IOException {
        if (restServer != null) restServer.stop();
        super.tearDown();
        if (http != null) http.dispatcher().executorService().shutdown();
    }

    // ---- Helper methods ----

    private JsonNode get(String path) throws IOException {
        var request = new Request.Builder().url(baseUrl + path).get().build();
        try (var response = http.newCall(request).execute()) {
            assertThat(response.code()).as("GET %s status", path).isBetween(200, 299);
            return mapper.readTree(response.body().string());
        }
    }

    private JsonNode post(String path, Object body) throws IOException {
        var json = mapper.writeValueAsString(body);
        var requestBody = RequestBody.create(json, MediaType.parse("application/json"));
        var request = new Request.Builder().url(baseUrl + path).post(requestBody).build();
        try (var response = http.newCall(request).execute()) {
            assertThat(response.code()).as("POST %s status", path).isBetween(200, 299);
            var bodyStr = response.body().string();
            return bodyStr.isEmpty() ? mapper.createObjectNode() : mapper.readTree(bodyStr);
        }
    }

    private int getStatusCode(String path) throws IOException {
        var request = new Request.Builder().url(baseUrl + path).get().build();
        try (var response = http.newCall(request).execute()) {
            return response.code();
        }
    }

    // ---- Health & status ----

    @Test
    @Order(1)
    void healthEndpointReturns200() throws IOException {
        var result = get("/api/health");
        assertThat(result.path("status").asText()).isEqualTo("ok");
    }

    @Test
    @Order(2)
    void statusEndpointReturnsData() throws IOException {
        var result = get("/api/status");
        assertThat(result.isObject()).isTrue();
    }

    // ---- Repos ----

    @Test
    @Order(3)
    void listReposReturnsIndexedRepos() throws IOException {
        var result = get("/api/repos");
        assertThat(result.isArray()).isTrue();
        assertThat(result.size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(4)
    void repoStatusEndpointWorks() throws IOException {
        var repos = get("/api/repos");
        assertThat(repos.size()).isGreaterThan(0);
        String repoId = repos.get(0).path("id").asText();
        var status = get("/api/repos/" + repoId + "/status");
        assertThat(status.has("status")).isTrue();
    }

    // ---- Search ----

    @Test
    @Order(5)
    void searchReturnsResults() throws IOException {
        var result = get("/api/search?q=CodeElement");
        assertThat(result.has("total")).isTrue();
        assertThat(result.path("total").asLong()).isGreaterThan(0);
        assertThat(result.has("items")).isTrue();
        assertThat(result.path("items").isArray()).isTrue();
        assertThat(result.path("items").size()).isGreaterThan(0);
    }

    @Test
    @Order(6)
    void searchWithTypeFilterWorks() throws IOException {
        var result = get("/api/search?q=get&type=METHOD&lang=JAVA");
        assertThat(result.path("total").asLong()).isGreaterThan(0);
        var items = result.path("items");
        for (var item : items) {
            assertThat(item.path("elementType").asText()).isEqualTo("METHOD");
        }
    }

    @Test
    @Order(7)
    void searchPaginationWorks() throws IOException {
        var page1 = get("/api/search?q=&lang=JAVA&limit=10&offset=0");
        var page2 = get("/api/search?q=&lang=JAVA&limit=10&offset=10");
        assertThat(page1.path("items").size()).isEqualTo(10);
        // IDs in page1 and page2 should be different
        String firstId1 = page1.path("items").get(0).path("id").asText();
        String firstId2 = page2.path("items").get(0).path("id").asText();
        assertThat(firstId1).isNotEqualTo(firstId2);
    }

    @Test
    @Order(8)
    void searchSortByNameWorks() throws IOException {
        var result = get("/api/search?q=&type=CLASS&lang=JAVA&sort=name&asc=true&limit=20");
        var items = result.path("items");
        assertThat(items.size()).isGreaterThan(1);
        // Verify items are sorted by name ascending
        String prev = "";
        for (var item : items) {
            String name = item.path("name").asText().toLowerCase();
            assertThat(name).isGreaterThanOrEqualTo(prev);
            prev = name;
        }
    }

    // ---- Element endpoints ----

    private String getFirstElementId(String query) throws IOException {
        var result = get("/api/search?q=" + query + "&type=CLASS&lang=JAVA");
        assertThat(result.path("items").size()).isGreaterThan(0);
        return result.path("items").get(0).path("id").asText();
    }

    @Test
    @Order(10)
    void getElementByIdWorks() throws IOException {
        var searchResult = get("/api/search?q=CodeElement&type=CLASS&lang=JAVA");
        String id = searchResult.path("items").get(0).path("id").asText();

        var element = get("/api/elements/" + id);
        assertThat(element.path("id").asText()).isEqualTo(id);
        assertThat(element.path("elementType").asText()).isEqualTo("CLASS");
        assertThat(element.path("name").asText()).isEqualTo("CodeElement");
    }

    @Test
    @Order(11)
    void getElementSnippetWorks() throws IOException {
        String id = getFirstElementId("CodeElement");
        var snippet = get("/api/elements/" + id + "/snippet?context=3");
        assertThat(snippet.has("snippet")).isTrue();
        assertThat(snippet.path("snippet").asText()).isNotBlank();
        assertThat(snippet.has("lineStart")).isTrue();
    }

    @Test
    @Order(12)
    void getChildrenWorks() throws IOException {
        String id = getFirstElementId("CodeElement");
        var children = get("/api/elements/" + id + "/children");
        assertThat(children.isArray()).isTrue();
        assertThat(children.size()).isGreaterThan(0);
    }

    @Test
    @Order(13)
    void getMethodsWorks() throws IOException {
        String id = getFirstElementId("CodeElement");
        var methods = get("/api/elements/" + id + "/methods");
        assertThat(methods.isArray()).isTrue();
        assertThat(methods.size()).isGreaterThan(0);
        for (var m : methods) {
            assertThat(m.path("elementType").asText()).isIn("METHOD", "CONSTRUCTOR");
        }
    }

    @Test
    @Order(14)
    void getParentWorks() throws IOException {
        // Get a method, then get its parent
        var methodSearch = get("/api/search?q=getQualifiedName&type=METHOD&lang=JAVA&limit=1");
        if (methodSearch.path("items").size() > 0) {
            String methodId = methodSearch.path("items").get(0).path("id").asText();
            var parent = get("/api/elements/" + methodId + "/parent");
            assertThat(parent.path("elementType").asText()).isIn("CLASS", "INTERFACE");
        }
    }

    @Test
    @Order(15)
    void getSiblingsWorks() throws IOException {
        var classSearch = get("/api/search?q=CodeElement&type=CLASS&lang=JAVA");
        String classId = classSearch.path("items").get(0).path("id").asText();
        var children = get("/api/elements/" + classId + "/children?type=METHOD");
        if (children.size() > 1) {
            String methodId = children.get(0).path("id").asText();
            var siblings = get("/api/elements/" + methodId + "/siblings");
            assertThat(siblings.isArray()).isTrue();
            // Siblings should not include self
            for (var s : siblings) {
                assertThat(s.path("id").asText()).isNotEqualTo(methodId);
            }
        }
    }

    @Test
    @Order(16)
    void getAncestorsWorks() throws IOException {
        var methodSearch = get("/api/search?q=generateId&type=METHOD&lang=JAVA&limit=1");
        if (methodSearch.path("items").size() > 0) {
            String methodId = methodSearch.path("items").get(0).path("id").asText();
            var ancestors = get("/api/elements/" + methodId + "/ancestors");
            assertThat(ancestors.isArray()).isTrue();
            assertThat(ancestors.size()).isGreaterThan(0);
        }
    }

    @Test
    @Order(17)
    void getSubGraphWorks() throws IOException {
        String classId = getFirstElementId("CodeElement");
        var graph = get("/api/elements/" + classId + "/graph?depth=1");
        assertThat(graph.has("nodes")).isTrue();
        assertThat(graph.has("edges")).isTrue();
        assertThat(graph.path("nodes").size()).isGreaterThan(0);
    }

    @Test
    @Order(18)
    void getFieldsWorks() throws IOException {
        String id = getFirstElementId("CodeElement");
        var fields = get("/api/elements/" + id + "/fields");
        assertThat(fields.isArray()).isTrue();
        assertThat(fields.size()).isGreaterThan(0);
    }

    @Test
    @Order(19)
    void missingElementReturns404() throws IOException {
        int status = getStatusCode("/api/elements/nonexistent-id-xyz");
        assertThat(status).isEqualTo(404);
    }

    // ---- Graph endpoint ----

    @Test
    @Order(20)
    void subgraphEndpointWorks() throws IOException {
        String classId = getFirstElementId("GraphService");
        var graph = get("/api/graph/subgraph?root=" + classId + "&depth=2");
        assertThat(graph.has("nodes")).isTrue();
        assertThat(graph.path("nodes").size()).isGreaterThan(0);
    }

    // ---- Configs ----

    @Test
    @Order(21)
    void listConfigsWorks() throws IOException {
        var repos = get("/api/repos");
        String repoId = repos.get(0).path("id").asText();
        var configs = get("/api/configs?repo=" + repoId);
        assertThat(configs.isArray()).isTrue();
    }

    // ---- Files endpoint ----

    @Test
    @Order(22)
    void getElementsByFileWorks() throws IOException {
        var repos = get("/api/repos");
        String repoId = repos.get(0).path("id").asText();
        var result = get("/api/files?repo=" + repoId + "&path=core/src/main/java/com/codegraph/core/model/CodeElement.java");
        assertThat(result.isArray()).isTrue();
        assertThat(result.size()).isGreaterThan(0);
    }
}
