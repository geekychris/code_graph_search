package com.codegraph.app;

import com.codegraph.core.config.AppConfig;
import com.codegraph.core.config.RepoConfig;
import com.codegraph.core.model.Language;
import com.codegraph.core.model.Repository;
import com.codegraph.core.model.Visibility;
import com.codegraph.core.security.Principal;
import com.codegraph.core.security.RequestContext;
import com.codegraph.core.service.ScopedGraphService;
import com.codegraph.app.portfolio.GitMirror;
import com.codegraph.app.portfolio.PortfolioService;
import com.codegraph.indexer.IndexerService;
import com.codegraph.mcp.McpServer;
import com.codegraph.parser.ParserRegistry;
import com.codegraph.rest.RestServer;
import com.codegraph.watcher.FileWatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Application entry point.
 *
 * <p>Startup flow:
 * <ol>
 *   <li>Parse CLI args: {@code --config <path>} (default: {@code ./config.yaml}),
 *       {@code --mcp-stdio} (run as MCP stdio server, no HTTP)</li>
 *   <li>Load {@link AppConfig}</li>
 *   <li>Create data directories</li>
 *   <li>Create {@link IndexerService} (the {@link com.codegraph.core.service.GraphService} impl)</li>
 *   <li>Create and populate {@link ParserRegistry}</li>
 *   <li>For each configured repo: create/save Repository, index if needed</li>
 *   <li>Create {@link McpServer}, register all tools</li>
 *   <li>If {@code --mcp-stdio}: run MCP stdio mode (blocking), no HTTP server</li>
 *   <li>Otherwise: start {@link RestServer}; optionally start {@link FileWatcher}</li>
 * </ol>
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        // -------------------------------------------------------------------------
        // 1. Parse CLI args
        // -------------------------------------------------------------------------
        String configPath = "./config.yaml";
        boolean mcpStdioMode = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> {
                    if (i + 1 < args.length) configPath = args[++i];
                }
                case "--mcp-stdio" -> mcpStdioMode = true;
                default -> log.warn("Unknown argument: {}", args[i]);
            }
        }

        // In MCP stdio mode stdout is the MCP channel — redirect all logging to stderr
        // (logback.xml handles this via the mcpStdioEnabled check via system property)
        if (mcpStdioMode) {
            System.setProperty("codegraph.mcp.stdio", "true");
        }

        log.info("Starting Code Graph Search...");
        log.info("Config path: {}", configPath);

        // -------------------------------------------------------------------------
        // 2. Load AppConfig
        // -------------------------------------------------------------------------
        AppConfig config = AppConfig.loadOrDefault(configPath);

        // Private GitHub repos must never be served without authentication
        if (config.getPortfolio().isEnabled() && !mcpStdioMode
                && !config.getAuth().isEnabled() && !config.getAuth().isAllowInsecure()) {
            log.error("portfolio.enabled syncs private repositories but auth.enabled is false. "
                    + "Enable auth (see config-example.yaml) or set auth.allowInsecure: true for a "
                    + "machine nobody else can reach.");
            System.exit(1);
        }
        // Stdio MCP is a local, trusted process; with auth off the HTTP API is too
        RequestContext.setDefault(mcpStdioMode || !config.getAuth().isEnabled()
                ? Principal.LOCAL : Principal.ANONYMOUS);

        // -------------------------------------------------------------------------
        // 3. Create data directories
        // -------------------------------------------------------------------------
        String dataDir = config.getIndexer().getDataDir();
        try {
            Files.createDirectories(Path.of(dataDir));
            log.debug("Data directory: {}", Path.of(dataDir).toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to create data directory {}: {}", dataDir, e.getMessage(), e);
            System.exit(1);
        }

        // -------------------------------------------------------------------------
        // 4. Create GraphService (IndexerService)
        // -------------------------------------------------------------------------
        log.info("Initializing IndexerService...");
        IndexerService graphService;
        try {
            graphService = new IndexerService(config.getIndexer());
        } catch (Exception e) {
            log.error("Failed to initialize IndexerService: {}", e.getMessage(), e);
            System.exit(1);
            return;
        }

        // -------------------------------------------------------------------------
        // 5. Create and populate ParserRegistry
        // -------------------------------------------------------------------------
        log.info("Initializing parsers...");
        ParserRegistry parserRegistry = ParserRegistry.withDefaults();
        log.info("Registered {} parser(s)", parserRegistry.size());

        // -------------------------------------------------------------------------
        // 6. Index configured repositories
        // -------------------------------------------------------------------------
        List<RepoConfig> localRepos = new ArrayList<>(config.getRepos());
        localRepos.addAll(persistedLocalRepos(config, graphService));
        if (!localRepos.isEmpty()) {
            log.info("Checking {} local repository/repositories...", localRepos.size());
            indexRepositories(config, localRepos, graphService, parserRegistry);
        } else {
            log.info("No local repositories configured — skipping initial indexing");
        }

        // -------------------------------------------------------------------------
        // 7. Create ObjectMapper
        // -------------------------------------------------------------------------
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // -------------------------------------------------------------------------
        // 7b. Create IndexManagementService
        // -------------------------------------------------------------------------
        IndexManagementServiceImpl indexManagement = new IndexManagementServiceImpl(graphService, parserRegistry, config);

        // -------------------------------------------------------------------------
        // 8. Create McpServer and register all tools
        // -------------------------------------------------------------------------
        log.info("Initializing MCP server...");
        // Everything callers can reach reads through the visibility filter
        ScopedGraphService scopedGraph = new ScopedGraphService(graphService);
        McpServer mcpServer = new McpServer(scopedGraph, mapper);
        mcpServer.registerAllTools();
        mcpServer.setIndexManagement(indexManagement);

        // -------------------------------------------------------------------------
        // 9. MCP stdio mode: run blocking, no HTTP
        // -------------------------------------------------------------------------
        if (mcpStdioMode) {
            log.info("Running in MCP stdio mode");
            registerShutdownHook(graphService, null, null);
            mcpServer.runStdio(); // blocks until stdin closes
            graphService.close();
            return;
        }

        // -------------------------------------------------------------------------
        // 10a. Start REST server
        // -------------------------------------------------------------------------
        RestServer restServer = new RestServer(scopedGraph, mcpServer, config, mapper);
        restServer.setIndexManagement(indexManagement);

        PortfolioService portfolio = null;
        if (config.getPortfolio().isEnabled()) {
            portfolio = PortfolioService.fromConfig(config.getPortfolio(), graphService, parserRegistry);
            restServer.setPortfolio(portfolio);
        }
        restServer.start();
        if (portfolio != null) portfolio.start();

        // -------------------------------------------------------------------------
        // 10b. Start FileWatcher if autoWatch is enabled
        // -------------------------------------------------------------------------
        FileWatcher fileWatcher = null;
        List<RepoConfig> runtimeRepos = persistedLocalRepos(config, graphService);
        if (config.getIndexer().isAutoWatch() && (!config.getRepos().isEmpty() || !runtimeRepos.isEmpty())) {
            log.info("Starting file watcher (autoWatch=true)...");
            fileWatcher = new FileWatcher(config, graphService, parserRegistry);
            fileWatcher.startWatching();
            runtimeRepos.forEach(fileWatcher::addRepo);
            indexManagement.setFileWatcher(fileWatcher);
        }

        // -------------------------------------------------------------------------
        // 10c. Log startup complete
        // -------------------------------------------------------------------------
        log.info("=======================================================");
        log.info("Code Graph Search is running!");
        log.info("  REST API: http://localhost:{}/api", config.getServer().getPort());
        log.info("  UI:       http://localhost:{}/", config.getServer().getPort());
        if (config.getServer().isMcpHttpEnabled()) {
            log.info("  MCP HTTP: http://localhost:{}{}", config.getServer().getPort(),
                    config.getServer().getMcpHttpPath());
        }
        log.info("=======================================================");

        // -------------------------------------------------------------------------
        // 10d. Shutdown hook
        // -------------------------------------------------------------------------
        final FileWatcher finalFileWatcher = fileWatcher;
        final PortfolioService finalPortfolio = portfolio;
        if (finalPortfolio != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(finalPortfolio::stop, "portfolio-stop"));
        }
        registerShutdownHook(graphService, restServer, finalFileWatcher);

        // Keep the main thread alive (Javalin uses its own server threads)
        Thread.currentThread().join();
    }

    // -------------------------------------------------------------------------
    // Repository indexing
    // -------------------------------------------------------------------------

    /**
     * Repos added at runtime through the API or MCP ({@code source = local}) and not in
     * the config file. They are kept in the registry so they survive restarts.
     */
    static List<RepoConfig> persistedLocalRepos(AppConfig config, IndexerService graphService) {
        Set<String> configured = new HashSet<>();
        config.getRepos().forEach(r -> configured.add(r.getId()));
        List<RepoConfig> result = new ArrayList<>();
        for (Repository r : graphService.listRepositories()) {
            if (!"local".equals(r.getSource()) || configured.contains(r.getId()) || r.getRootPath() == null) continue;
            RepoConfig rc = new RepoConfig();
            rc.setId(r.getId());
            rc.setName(r.getName());
            rc.setPath(r.getRootPath());
            rc.setVisibility(r.getVisibility());
            rc.setDescription(r.getDescription());
            if (r.getLanguages() != null) rc.setLanguages(r.getLanguages());
            result.add(rc);
        }
        return result;
    }

    /**
     * Brings each local repo's index up to date, in parallel.
     *
     * <p>A repo that has never been indexed (or failed) gets a full index. A repo that is a
     * git checkout is caught up with the commits and uncommitted edits made while the
     * server was down. Other repos already indexed are left as they are; the file watcher
     * keeps them current while running.
     */
    private static void indexRepositories(AppConfig config, List<RepoConfig> repos,
                                          IndexerService graphService,
                                          ParserRegistry parserRegistry) {
        int threads = Math.max(1, config.getIndexer().getIndexingThreads());
        ExecutorService repoExecutor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setDaemon(true);
            return t;
        });
        GitMirror git = new GitMirror(Path.of(config.getIndexer().getDataDir()), null, 120);

        CountDownLatch latch = new CountDownLatch(repos.size());
        for (RepoConfig repoConfig : repos) {
            repoExecutor.submit(() -> {
                try {
                    if (repoConfig.getId() == null || repoConfig.getPath() == null) {
                        log.warn("Skipping repo config with null id or path");
                        return;
                    }
                    catchUpRepo(repoConfig, graphService, parserRegistry, git);
                } catch (Exception e) {
                    log.error("Indexing repo '{}' failed: {}", repoConfig.getId(), e.getMessage(), e);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for indexing to complete");
        }
        repoExecutor.shutdown();
    }

    private static void catchUpRepo(RepoConfig repoConfig, IndexerService graphService,
                                    ParserRegistry parserRegistry, GitMirror git) {
        Repository repo = graphService.getRepository(repoConfig.getId()).orElseGet(Repository::new);
        repo.setId(repoConfig.getId());
        repo.setName(repoConfig.getName() != null ? repoConfig.getName() : repoConfig.getId());
        repo.setRootPath(repoConfig.getPath());
        if (repoConfig.getLanguages() != null && !repoConfig.getLanguages().isEmpty()) {
            repo.setLanguages(repoConfig.getLanguages());
        }
        if (repoConfig.getDescription() != null) repo.setDescription(repoConfig.getDescription());
        if (repoConfig.getVisibility() != null) repo.setVisibility(repoConfig.getVisibility());
        else if (repo.getVisibility() == null) repo.setVisibility(Visibility.PRIVATE);
        if (repo.getSource() == null) repo.setSource("config");
        graphService.saveRepository(repo);

        Path root = Path.of(repoConfig.getPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            log.error("Repo '{}' path does not exist or is not a directory: {}", repo.getId(), root);
            repo.setStatus(Repository.IndexingStatus.ERROR);
            graphService.saveRepository(repo);
            return;
        }

        boolean isGit = git.isGitRepo(root);
        String head = isGit ? git.head(root) : null;
        boolean fresh = repo.getStatus() != Repository.IndexingStatus.READY
                && repo.getStatus() != Repository.IndexingStatus.WATCHING
                || repo.getElementCount() == 0;

        if (fresh) {
            graphService.indexRepository(repoConfig, parserRegistry);
        } else if (isGit) {
            GitMirror.ChangeSet committed = (repo.getIndexedCommit() != null && head != null
                    && !head.equals(repo.getIndexedCommit()))
                    ? git.diff(root, repo.getIndexedCommit(), head)
                    : new GitMirror.ChangeSet(List.of(), List.of());
            if (committed == null) {
                log.info("Repo '{}': indexed commit not found, re-indexing fully", repo.getId());
                graphService.indexRepository(repoConfig, parserRegistry);
            } else {
                GitMirror.ChangeSet local = git.workingTreeChanges(root);
                Set<String> changed = new java.util.LinkedHashSet<>(committed.changed());
                changed.addAll(local.changed());
                Set<String> deleted = new java.util.LinkedHashSet<>(committed.deleted());
                deleted.addAll(local.deleted());
                deleted.removeAll(changed);
                if (changed.isEmpty() && deleted.isEmpty()) {
                    log.info("Repo '{}' is up to date ({} elements)", repo.getId(), repo.getElementCount());
                } else {
                    graphService.indexRepositoryChanges(repoConfig, changed, deleted, parserRegistry);
                }
            }
        } else {
            log.info("Repo '{}' is already indexed ({} elements); not a git checkout, so offline "
                    + "changes are not detected. Reindex it to refresh.", repo.getId(), repo.getElementCount());
        }

        if (head != null) {
            graphService.getRepository(repo.getId()).ifPresent(latest -> {
                latest.setIndexedCommit(head);
                graphService.saveRepository(latest);
            });
        }
    }

    // -------------------------------------------------------------------------
    // Shutdown hook
    // -------------------------------------------------------------------------

    private static void registerShutdownHook(IndexerService graphService,
                                             RestServer restServer,
                                             FileWatcher fileWatcher) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received, stopping services...");
            if (fileWatcher != null) {
                try { fileWatcher.stop(); } catch (Exception e) { log.warn("Error stopping FileWatcher: {}", e.getMessage()); }
            }
            if (restServer != null) {
                try { restServer.stop(); } catch (Exception e) { log.warn("Error stopping RestServer: {}", e.getMessage()); }
            }
            try { graphService.close(); } catch (Exception e) { log.warn("Error closing GraphService: {}", e.getMessage()); }
            log.info("Shutdown complete");
        }, "shutdown-hook"));
    }
}
