package com.codegraph.core.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    private List<RepoConfig> repos = new ArrayList<>();
    private IndexerConfig indexer = new IndexerConfig();
    private ServerConfig server = new ServerConfig();
    private TreeSitterConfig treeSitter = new TreeSitterConfig();
    private AuthConfig auth = new AuthConfig();
    private PortfolioConfig portfolio = new PortfolioConfig();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IndexerConfig {
        private String dataDir = "./data";
        private int watchDebounceMs = 500;
        private boolean autoWatch = true;
        private int indexingThreads = 4;
        private int maxSnippetLines = 200;
        private long maxFileBytes = 1_000_000;   // larger files are skipped (generated code, data)

        public String getDataDir() { return dataDir; }
        public void setDataDir(String dataDir) { this.dataDir = dataDir; }
        public int getWatchDebounceMs() { return watchDebounceMs; }
        public void setWatchDebounceMs(int watchDebounceMs) { this.watchDebounceMs = watchDebounceMs; }
        public boolean isAutoWatch() { return autoWatch; }
        public void setAutoWatch(boolean autoWatch) { this.autoWatch = autoWatch; }
        public int getIndexingThreads() { return indexingThreads; }
        public void setIndexingThreads(int indexingThreads) { this.indexingThreads = indexingThreads; }
        public int getMaxSnippetLines() { return maxSnippetLines; }
        public void setMaxSnippetLines(int maxSnippetLines) { this.maxSnippetLines = maxSnippetLines; }
        public long getMaxFileBytes() { return maxFileBytes; }
        public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ServerConfig {
        private int port = 8080;
        private boolean mcpStdioEnabled = false;
        private boolean mcpHttpEnabled = true;
        private String mcpHttpPath = "/mcp";
        private boolean corsEnabled = false;
        private List<String> corsAllowedOrigins = new ArrayList<>(); // empty + corsEnabled = any origin

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public boolean isMcpStdioEnabled() { return mcpStdioEnabled; }
        public void setMcpStdioEnabled(boolean mcpStdioEnabled) { this.mcpStdioEnabled = mcpStdioEnabled; }
        public boolean isMcpHttpEnabled() { return mcpHttpEnabled; }
        public void setMcpHttpEnabled(boolean mcpHttpEnabled) { this.mcpHttpEnabled = mcpHttpEnabled; }
        public String getMcpHttpPath() { return mcpHttpPath; }
        public void setMcpHttpPath(String mcpHttpPath) { this.mcpHttpPath = mcpHttpPath; }
        public boolean isCorsEnabled() { return corsEnabled; }
        public void setCorsEnabled(boolean corsEnabled) { this.corsEnabled = corsEnabled; }
        public List<String> getCorsAllowedOrigins() { return corsAllowedOrigins; }
        public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) { this.corsAllowedOrigins = corsAllowedOrigins; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TreeSitterConfig {
        private String binaryPath = "tree-sitter";  // must be on PATH or set here
        private boolean enabled = true;
        private int timeoutSeconds = 30;

        public String getBinaryPath() { return binaryPath; }
        public void setBinaryPath(String binaryPath) { this.binaryPath = binaryPath; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    /**
     * Who may call the REST and MCP-over-HTTP APIs, and what they see.
     *
     * <p>With {@code enabled: false} every caller is treated as a trusted local user
     * (the original laptop behaviour). That is refused when private GitHub repos are
     * synced, unless {@code allowInsecure} is set.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthConfig {
        private boolean enabled = false;
        private boolean allowInsecure = false;
        private String anonymous = "public";          // "public" (read public repos) or "deny"
        private List<TokenConfig> tokens = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isAllowInsecure() { return allowInsecure; }
        public void setAllowInsecure(boolean allowInsecure) { this.allowInsecure = allowInsecure; }
        public String getAnonymous() { return anonymous; }
        public void setAnonymous(String anonymous) { this.anonymous = anonymous; }
        public List<TokenConfig> getTokens() { return tokens; }
        public void setTokens(List<TokenConfig> tokens) { this.tokens = tokens; }
    }

    /**
     * A bearer token. Give either {@code sha256} (hex digest of the token, safe to commit)
     * or {@code tokenEnv} (name of an environment variable holding the token itself).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenConfig {
        private String name;
        private String sha256;
        private String tokenEnv;
        private String scope = "public";              // "public" or "all" (public + private)
        private boolean admin = false;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSha256() { return sha256; }
        public void setSha256(String sha256) { this.sha256 = sha256; }
        public String getTokenEnv() { return tokenEnv; }
        public void setTokenEnv(String tokenEnv) { this.tokenEnv = tokenEnv; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
        public boolean isAdmin() { return admin; }
        public void setAdmin(boolean admin) { this.admin = admin; }
    }

    /** Sync and index every repository owned by the given GitHub accounts. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PortfolioConfig {
        private boolean enabled = false;
        private List<String> owners = new ArrayList<>();
        private boolean includeForks = false;
        private boolean includeArchived = true;
        private List<String> include = new ArrayList<>();   // if non-empty, only these repo names
        private List<String> exclude = new ArrayList<>();
        private String mirrorDir = "./data/mirrors";
        private int syncIntervalMinutes = 30;
        private int concurrency = 2;
        private String githubApiUrl = "https://api.github.com";
        private String githubTokenEnv = "GITHUB_TOKEN";
        private String webhookSecretEnv = "CODEGRAPH_WEBHOOK_SECRET";
        private int gitTimeoutSeconds = 600;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getOwners() { return owners; }
        public void setOwners(List<String> owners) { this.owners = owners; }
        public boolean isIncludeForks() { return includeForks; }
        public void setIncludeForks(boolean includeForks) { this.includeForks = includeForks; }
        public boolean isIncludeArchived() { return includeArchived; }
        public void setIncludeArchived(boolean includeArchived) { this.includeArchived = includeArchived; }
        public List<String> getInclude() { return include; }
        public void setInclude(List<String> include) { this.include = include; }
        public List<String> getExclude() { return exclude; }
        public void setExclude(List<String> exclude) { this.exclude = exclude; }
        public String getMirrorDir() { return mirrorDir; }
        public void setMirrorDir(String mirrorDir) { this.mirrorDir = mirrorDir; }
        public int getSyncIntervalMinutes() { return syncIntervalMinutes; }
        public void setSyncIntervalMinutes(int syncIntervalMinutes) { this.syncIntervalMinutes = syncIntervalMinutes; }
        public int getConcurrency() { return concurrency; }
        public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
        public String getGithubApiUrl() { return githubApiUrl; }
        public void setGithubApiUrl(String githubApiUrl) { this.githubApiUrl = githubApiUrl; }
        public String getGithubTokenEnv() { return githubTokenEnv; }
        public void setGithubTokenEnv(String githubTokenEnv) { this.githubTokenEnv = githubTokenEnv; }
        public String getWebhookSecretEnv() { return webhookSecretEnv; }
        public void setWebhookSecretEnv(String webhookSecretEnv) { this.webhookSecretEnv = webhookSecretEnv; }
        public int getGitTimeoutSeconds() { return gitTimeoutSeconds; }
        public void setGitTimeoutSeconds(int gitTimeoutSeconds) { this.gitTimeoutSeconds = gitTimeoutSeconds; }
    }

    public static AppConfig load(String path) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.findAndRegisterModules();
        return mapper.readValue(new File(path), AppConfig.class);
    }

    public static AppConfig loadOrDefault(String path) {
        File f = new File(path);
        if (!f.exists()) {
            log.info("Config file not found at {}; using defaults (port 8080, no repos)", path);
            return new AppConfig();
        }
        try {
            return load(path);
        } catch (Exception e) {
            // Previously this was silently swallowed, which produced very
            // confusing behavior: the app would start with an empty config
            // (port 8080, no repos indexed) and users had no clue why.
            // Log loudly so parse failures actually surface.
            log.error("Failed to parse config at {}: {}", path, e.getMessage());
            log.error("Falling back to defaults (port 8080, no repos). Fix the config and restart.", e);
            return new AppConfig();
        }
    }

    // Getters and setters
    public List<RepoConfig> getRepos() { return repos; }
    public void setRepos(List<RepoConfig> repos) { this.repos = repos; }
    public IndexerConfig getIndexer() { return indexer; }
    public void setIndexer(IndexerConfig indexer) { this.indexer = indexer; }
    public ServerConfig getServer() { return server; }
    public void setServer(ServerConfig server) { this.server = server; }
    public TreeSitterConfig getTreeSitter() { return treeSitter; }
    public void setTreeSitter(TreeSitterConfig treeSitter) { this.treeSitter = treeSitter; }
    public AuthConfig getAuth() { return auth; }
    public void setAuth(AuthConfig auth) { this.auth = auth; }
    public PortfolioConfig getPortfolio() { return portfolio; }
    public void setPortfolio(PortfolioConfig portfolio) { this.portfolio = portfolio; }
}
