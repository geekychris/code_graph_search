package com.codegraph.app;

import com.codegraph.core.config.AppConfig;
import com.codegraph.core.config.RepoConfig;
import com.codegraph.core.model.Language;
import com.codegraph.core.model.Repository;
import com.codegraph.indexer.IndexerService;
import com.codegraph.mcp.McpServer;
import com.codegraph.parser.ParseResult;
import com.codegraph.parser.ParserRegistry;
import com.codegraph.rest.RestServer;
import com.codegraph.watcher.FileWatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
        if (!config.getRepos().isEmpty()) {
            log.info("Indexing {} configured repository/repositories...", config.getRepos().size());
            indexRepositories(config, graphService, parserRegistry);
        } else {
            log.info("No repositories configured — skipping initial indexing");
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
        McpServer mcpServer = new McpServer(graphService, mapper);
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
        RestServer restServer = new RestServer(graphService, mcpServer, config, mapper);
        restServer.setIndexManagement(indexManagement);
        restServer.start();

        // -------------------------------------------------------------------------
        // 10b. Start FileWatcher if autoWatch is enabled
        // -------------------------------------------------------------------------
        FileWatcher fileWatcher = null;
        if (config.getIndexer().isAutoWatch() && !config.getRepos().isEmpty()) {
            log.info("Starting file watcher (autoWatch=true)...");
            fileWatcher = new FileWatcher(config, graphService, parserRegistry);
            fileWatcher.startWatching();
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
        registerShutdownHook(graphService, restServer, finalFileWatcher);

        // Keep the main thread alive (Javalin uses its own server threads)
        Thread.currentThread().join();
    }

    // -------------------------------------------------------------------------
    // Repository indexing
    // -------------------------------------------------------------------------

    /**
     * For each repo in config: creates/saves a Repository record, then runs
     * full indexing in parallel across all repos.
     */
    private static void indexRepositories(AppConfig config,
                                          IndexerService graphService,
                                          ParserRegistry parserRegistry) {
        int threads = Math.max(1, config.getIndexer().getIndexingThreads());
        ExecutorService repoExecutor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setDaemon(true);
            return t;
        });

        List<Runnable> indexingTasks = new ArrayList<>();

        for (RepoConfig repoConfig : config.getRepos()) {
            if (repoConfig.getId() == null || repoConfig.getPath() == null) {
                log.warn("Skipping repo config with null id or path");
                continue;
            }

            // Create or update Repository record
            Repository repo = graphService.getRepository(repoConfig.getId())
                    .orElseGet(Repository::new);
            repo.setId(repoConfig.getId());
            repo.setName(repoConfig.getName() != null ? repoConfig.getName() : repoConfig.getId());
            repo.setRootPath(repoConfig.getPath());
            if (repoConfig.getLanguages() != null && !repoConfig.getLanguages().isEmpty()) {
                repo.setLanguages(repoConfig.getLanguages());
            }
            if (repoConfig.getDescription() != null) {
                repo.setDescription(repoConfig.getDescription());
            }

            // Decide whether to skip re-indexing (already READY status with data)
            boolean needsIndexing = repo.getStatus() != Repository.IndexingStatus.READY
                    || repo.getElementCount() == 0;

            if (!needsIndexing) {
                log.info("Repo '{}' is already indexed ({} elements), skipping",
                        repo.getId(), repo.getElementCount());
                graphService.saveRepository(repo);
                continue;
            }

            repo.setStatus(Repository.IndexingStatus.PENDING);
            graphService.saveRepository(repo);

            final Repository finalRepo = repo;
            indexingTasks.add(() -> indexRepo(repoConfig, finalRepo, graphService, parserRegistry));
        }

        if (indexingTasks.isEmpty()) return;

        CountDownLatch latch = new CountDownLatch(indexingTasks.size());
        for (Runnable task : indexingTasks) {
            repoExecutor.submit(() -> {
                try {
                    task.run();
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            // Wait for all repos to finish indexing
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for indexing to complete");
        }

        repoExecutor.shutdown();
    }

    /**
     * Indexes a single repository by walking the directory tree and parsing each file.
     */
    private static void indexRepo(RepoConfig repoConfig,
                                  Repository repo,
                                  IndexerService graphService,
                                  ParserRegistry parserRegistry) {
        String repoId = repoConfig.getId();
        Path root = Path.of(repoConfig.getPath()).toAbsolutePath().normalize();

        if (!Files.isDirectory(root)) {
            log.error("Repo '{}' path does not exist or is not a directory: {}", repoId, root);
            repo.setStatus(Repository.IndexingStatus.ERROR);
            graphService.saveRepository(repo);
            return;
        }

        log.info("Indexing repo '{}' at {}", repoId, root);
        repo.setStatus(Repository.IndexingStatus.INDEXING);
        graphService.saveRepository(repo);

        AtomicInteger fileCount    = new AtomicInteger(0);
        AtomicInteger elementCount = new AtomicInteger(0);
        AtomicInteger errorCount   = new AtomicInteger(0);

        try {
            // Use a thread pool per repo for parallel file parsing
            int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
            ExecutorService fileExecutor = Executors.newFixedThreadPool(threads, r -> {
                Thread t = Thread.ofVirtual().unstarted(r);
                t.setDaemon(true);
                return t;
            });

            List<Path> filesToParse = new ArrayList<>();

            // Walk the tree, collect files that aren't excluded
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (isExcluded(dir, root, repoConfig)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!isExcluded(file, root, repoConfig)) {
                        filesToParse.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Cannot visit {}: {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });

            log.info("Repo '{}': found {} files to parse", repoId, filesToParse.size());

            CountDownLatch fileLatch = new CountDownLatch(filesToParse.size());
            for (Path file : filesToParse) {
                fileExecutor.submit(() -> {
                    try {
                        ParseResult result = parserRegistry.parse(file, repoId, root);
                        fileCount.incrementAndGet();
                        if (result.isSuccess()) {
                            if (!result.getElements().isEmpty()) {
                                graphService.saveElements(result.getElements());
                                elementCount.addAndGet(result.getElements().size());
                            }
                            if (!result.getEdges().isEmpty()) {
                                graphService.saveEdges(result.getEdges());
                            }
                        } else {
                            errorCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        log.error("Error parsing {}: {}", file, e.getMessage(), e);
                        errorCount.incrementAndGet();
                    } finally {
                        fileLatch.countDown();
                    }
                });
            }

            fileLatch.await();
            fileExecutor.shutdown();

            // Update repo with final stats
            repo.setStatus(Repository.IndexingStatus.READY);
            repo.setLastIndexed(Instant.now());
            repo.setFileCount(fileCount.get());
            repo.setElementCount(elementCount.get());
            graphService.saveRepository(repo);

            log.info("Repo '{}' indexed: {} files, {} elements, {} parse errors",
                    repoId, fileCount.get(), elementCount.get(), errorCount.get());

        } catch (IOException e) {
            log.error("IO error walking repo '{}': {}", repoId, e.getMessage(), e);
            repo.setStatus(Repository.IndexingStatus.ERROR);
            graphService.saveRepository(repo);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while indexing repo '{}'", repoId);
            repo.setStatus(Repository.IndexingStatus.ERROR);
            graphService.saveRepository(repo);
        }
    }

    /**
     * Returns {@code true} if the path should be excluded based on the repo's
     * exclude patterns (simple glob matching).
     */
    private static boolean isExcluded(Path path, Path repoRoot, RepoConfig repoConfig) {
        if (repoConfig.getExcludePatterns() == null || repoConfig.getExcludePatterns().isEmpty()) {
            return false;
        }
        String relativePath = repoRoot.relativize(path).toString().replace('\\', '/');
        String absolutePath = path.toString().replace('\\', '/');

        for (String pattern : repoConfig.getExcludePatterns()) {
            if (matchesGlob(relativePath, pattern) || matchesGlob(absolutePath, pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Minimal glob matching: supports {@code *}, {@code **}, and {@code ?}.
     */
    private static boolean matchesGlob(String path, String pattern) {
        // Convert glob to regex
        StringBuilder regex = new StringBuilder("^");
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '*' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                regex.append(".*");
                i += 2;
                if (i < pattern.length() && (pattern.charAt(i) == '/' || pattern.charAt(i) == '\\')) i++;
            } else if (c == '*') {
                regex.append("[^/\\\\]*");
                i++;
            } else if (c == '?') {
                regex.append("[^/\\\\]");
                i++;
            } else {
                regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        regex.append("$");
        try {
            return path.matches(regex.toString());
        } catch (Exception e) {
            return false;
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
