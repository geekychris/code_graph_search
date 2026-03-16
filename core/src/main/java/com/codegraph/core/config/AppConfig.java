package com.codegraph.core.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {

    private List<RepoConfig> repos = new ArrayList<>();
    private IndexerConfig indexer = new IndexerConfig();
    private ServerConfig server = new ServerConfig();
    private TreeSitterConfig treeSitter = new TreeSitterConfig();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IndexerConfig {
        private String dataDir = "./data";
        private int watchDebounceMs = 500;
        private boolean autoWatch = true;
        private int indexingThreads = 4;
        private int maxSnippetLines = 200;

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
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ServerConfig {
        private int port = 8080;
        private boolean mcpStdioEnabled = false;
        private boolean mcpHttpEnabled = true;
        private String mcpHttpPath = "/mcp";
        private boolean corsEnabled = true;

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

    public static AppConfig load(String path) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.findAndRegisterModules();
        return mapper.readValue(new File(path), AppConfig.class);
    }

    public static AppConfig loadOrDefault(String path) {
        try {
            File f = new File(path);
            if (f.exists()) return load(path);
        } catch (Exception ignored) {}
        return new AppConfig();
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
}
