package com.codegraph.tests;

import com.codegraph.core.config.AppConfig;
import com.codegraph.core.config.RepoConfig;
import com.codegraph.core.model.Repository;
import com.codegraph.core.model.Visibility;
import com.codegraph.core.service.ScopedGraphService;
import com.codegraph.indexer.IndexerService;
import com.codegraph.mcp.McpServer;
import com.codegraph.rest.ApiAuth;
import com.codegraph.rest.RestServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bearer-token auth on the REST and MCP-over-HTTP endpoints: what anonymous, public,
 * all-repos and admin callers can see and do.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthApiTest {

    private static final int PORT = 18091;
    private static final String READER = "reader-token";
    private static final String ADMIN = "admin-token";
    private static final String PUBLIC_ONLY = "public-token";

    private Path tmp;
    private IndexerService raw;
    private RestServer server;
    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    private final String base = "http://localhost:" + PORT;

    @BeforeAll
    void setUp() throws Exception {
        tmp = Files.createTempDirectory("codegraph-auth-");
        AppConfig.IndexerConfig ic = new AppConfig.IndexerConfig();
        ic.setDataDir(tmp.resolve("data").toString());
        raw = new IndexerService(ic);
        index("open-lib", Visibility.PUBLIC, "package o; public class Shared {}");
        index("secret-app", Visibility.PRIVATE, "package s; public class Hidden {}");

        AppConfig config = new AppConfig();
        config.getServer().setPort(PORT);
        config.getAuth().setEnabled(true);
        config.getAuth().setTokens(List.of(
                token("reader", READER, "all", false),
                token("admin", ADMIN, "all", true),
                token("public", PUBLIC_ONLY, "public", false)));

        ScopedGraphService scoped = new ScopedGraphService(raw);
        McpServer mcp = new McpServer(scoped, mapper);
        server = new RestServer(scoped, mcp, config, mapper);
        server.start();
    }

    @AfterAll
    void tearDown() {
        if (server != null) server.stop();
        raw.close();
        http.dispatcher().executorService().shutdown();
    }

    @Test
    void anonymousSeesPublicReposOnly() throws IOException {
        assertThat(repoIds(null)).containsExactly("open-lib");
    }

    @Test
    void publicTokenSeesPublicReposOnly() throws IOException {
        assertThat(repoIds(PUBLIC_ONLY)).containsExactly("open-lib");
    }

    @Test
    void allScopeTokenSeesPrivateRepos() throws IOException {
        assertThat(repoIds(READER)).containsExactlyInAnyOrder("open-lib", "secret-app");
    }

    @Test
    void unknownTokenIsRejected() throws IOException {
        assertThat(status("GET", "/api/repos", "nope", null)).isEqualTo(401);
    }

    @Test
    void healthNeedsNoToken() throws IOException {
        assertThat(status("GET", "/api/health", null, null)).isEqualTo(200);
    }

    @Test
    void writesNeedAnAdminToken() throws IOException {
        String body = "{\"id\":\"x\",\"path\":\"/tmp\"}";
        assertThat(status("POST", "/api/repos", null, body)).isEqualTo(403);
        assertThat(status("POST", "/api/repos", READER, body)).isEqualTo(403);
        assertThat(status("DELETE", "/api/repos/open-lib", READER, null)).isEqualTo(403);
        assertThat(status("GET", "/api/browse", READER, null)).isEqualTo(403);
        assertThat(status("GET", "/api/browse", ADMIN, null)).isEqualTo(200);
    }

    @Test
    void whoAmIReportsTheCaller() throws IOException {
        JsonNode me = getJson("/api/whoami", ADMIN);
        assertThat(me.path("name").asText()).isEqualTo("admin");
        assertThat(me.path("seesPrivate").asBoolean()).isTrue();
        assertThat(me.path("admin").asBoolean()).isTrue();
        assertThat(getJson("/api/whoami", null).path("seesPrivate").asBoolean()).isFalse();
    }

    @Test
    void mcpToolsAreScopedToo() throws IOException {
        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";
        String session;
        try (Response r = call("POST", "/mcp", null, init)) {
            assertThat(r.code()).isEqualTo(200);
            session = r.header("Mcp-Session-Id");
        }
        String list = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"list_repos\",\"arguments\":{}}}";
        Request req = new Request.Builder().url(base + "/mcp")
                .header("Mcp-Session-Id", session)
                .post(RequestBody.create(list, MediaType.parse("application/json"))).build();
        try (Response r = http.newCall(req).execute()) {
            String text = r.body().string();
            assertThat(text).contains("open-lib").doesNotContain("secret-app");
        }
    }

    @Test
    void tokensCanBeGivenAsDigests() {
        assertThat(ApiAuth.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    // ---- helpers ----

    private static AppConfig.TokenConfig token(String name, String token, String scope, boolean admin) {
        AppConfig.TokenConfig t = new AppConfig.TokenConfig();
        t.setName(name);
        t.setSha256(ApiAuth.sha256(token));
        t.setScope(scope);
        t.setAdmin(admin);
        return t;
    }

    private void index(String id, Visibility v, String source) throws IOException {
        Path dir = tmp.resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("A.java"), source);
        RepoConfig rc = new RepoConfig();
        rc.setId(id);
        rc.setPath(dir.toString());
        raw.indexRepository(rc);
        Repository repo = raw.getRepository(id).orElseThrow();
        repo.setVisibility(v);
        raw.saveRepository(repo);
    }

    private List<String> repoIds(String token) throws IOException {
        JsonNode repos = getJson("/api/repos", token);
        return mapper.convertValue(repos.findValues("id"), mapper.getTypeFactory()
                .constructCollectionType(List.class, String.class));
    }

    private JsonNode getJson(String path, String token) throws IOException {
        try (Response r = call("GET", path, token, null)) {
            assertThat(r.code()).as("GET %s", path).isEqualTo(200);
            return mapper.readTree(r.body().string());
        }
    }

    private int status(String method, String path, String token, String body) throws IOException {
        try (Response r = call(method, path, token, body)) {
            return r.code();
        }
    }

    private Response call(String method, String path, String token, String body) throws IOException {
        Request.Builder b = new Request.Builder().url(base + path);
        if (token != null) b.header("Authorization", "Bearer " + token);
        RequestBody rb = body == null ? (method.equals("GET") || method.equals("DELETE") ? null
                : RequestBody.create(new byte[0])) : RequestBody.create(body, MediaType.parse("application/json"));
        b.method(method, rb);
        return http.newCall(b.build()).execute();
    }
}
