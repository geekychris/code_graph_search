package com.codegraph.watcher;

import com.codegraph.core.config.AppConfig;
import com.codegraph.core.config.RepoConfig;
import com.codegraph.core.service.GraphService;
import com.codegraph.parser.ParseResult;
import com.codegraph.parser.ParserRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Watches repository root paths for file system changes and re-indexes
 * modified files using the registered parsers.
 *
 * <p>Changes are debounced: multiple events for the same file within
 * {@code config.indexer.watchDebounceMs} milliseconds are collapsed into
 * a single re-index operation.
 *
 * <p>On macOS, uses {@code SensitivityWatchEventModifier.HIGH} to reduce
 * the polling interval from ~10s to ~2s for faster change detection.
 *
 * <p>Supports dynamic addition and removal of repositories at runtime
 * (e.g. repos added via the REST API or MCP tools).
 */
public class FileWatcher {

    private static final Logger log = LoggerFactory.getLogger(FileWatcher.class);

    /** Maximum number of recent changes to keep in the ring buffer. */
    private static final int MAX_RECENT_CHANGES = 200;

    private final AppConfig config;
    private final GraphService graphService;
    private final ParserRegistry parserRegistry;

    private WatchService watchService;
    private final Map<WatchKey, Path> keyToDir = new ConcurrentHashMap<>();
    /** Maps each repo root path to its RepoConfig for pattern matching. */
    private final Map<Path, RepoConfig> repoByRoot = new ConcurrentHashMap<>();

    /** Pending debounced events: absolute file path -> scheduled future. */
    private final Map<Path, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    /** Tracks which files were deleted (so we know not to re-parse them on flush). */
    private final Set<Path> deletedFiles = ConcurrentHashMap.newKeySet();

    /** Recent file change events for observability (MCP/REST). */
    private final Deque<FileChangeEvent> recentChanges = new ConcurrentLinkedDeque<>();

    /** macOS high-sensitivity modifier for faster polling (~2s instead of ~10s). */
    private static final WatchEvent.Modifier HIGH_SENSITIVITY = getHighSensitivityModifier();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = Thread.ofVirtual().unstarted(r);
                t.setName("filewatcher-debounce");
                t.setDaemon(true);
                return t;
            });

    private final AtomicBoolean watching = new AtomicBoolean(false);
    private Thread watchThread;

    public FileWatcher(AppConfig config, GraphService graphService, ParserRegistry parserRegistry) {
        this.config = config;
        this.graphService = graphService;
        this.parserRegistry = parserRegistry;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Registers all configured repository root paths (and their subdirectories)
     * with the OS-level WatchService, then starts a background thread to
     * process incoming events.
     */
    public void startWatching() {
        if (watching.getAndSet(true)) {
            log.warn("FileWatcher is already running");
            return;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            log.error("Failed to create WatchService: {}", e.getMessage(), e);
            watching.set(false);
            return;
        }

        // Build repoByRoot mapping and register all directories
        for (RepoConfig repoConfig : config.getRepos()) {
            if (repoConfig.getPath() == null) continue;
            Path root = Path.of(repoConfig.getPath()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                log.warn("Repo root does not exist or is not a directory, skipping watch: {}", root);
                continue;
            }
            repoByRoot.put(root, repoConfig);
            registerRecursive(root);
        }

        watchThread = Thread.ofVirtual().start(this::watchLoop);
        watchThread.setName("filewatcher-main");
        log.info("FileWatcher started, watching {} repo(s)", repoByRoot.size());
    }

    /**
     * Dynamically adds a repository to the watcher at runtime.
     * If the watcher is running, the repo's directory tree is immediately registered.
     *
     * @param repoConfig the repository configuration
     */
    public void addRepo(RepoConfig repoConfig) {
        if (repoConfig.getPath() == null) return;
        Path root = Path.of(repoConfig.getPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            log.warn("Cannot watch repo '{}': path is not a directory: {}", repoConfig.getId(), root);
            return;
        }

        repoByRoot.put(root, repoConfig);

        if (watching.get() && watchService != null) {
            registerRecursive(root);
            log.info("Added repo '{}' to file watcher: {}", repoConfig.getId(), root);
        }
    }

    /**
     * Removes a repository from the watcher at runtime.
     * WatchKeys for directories under this repo root are cancelled.
     *
     * @param repoId the repository ID to stop watching
     */
    public void removeRepo(String repoId) {
        Path rootToRemove = null;
        for (Map.Entry<Path, RepoConfig> entry : repoByRoot.entrySet()) {
            if (entry.getValue().getId().equals(repoId)) {
                rootToRemove = entry.getKey();
                break;
            }
        }
        if (rootToRemove == null) return;

        repoByRoot.remove(rootToRemove);

        // Cancel watch keys under this root
        Path finalRoot = rootToRemove;
        keyToDir.entrySet().removeIf(entry -> {
            if (entry.getValue().startsWith(finalRoot)) {
                entry.getKey().cancel();
                return true;
            }
            return false;
        });

        log.info("Removed repo '{}' from file watcher", repoId);
    }

    /**
     * Gracefully shuts down the watcher, cancelling pending debounce timers
     * and closing the underlying WatchService.
     */
    public void stop() {
        if (!watching.getAndSet(false)) return;

        // Cancel all pending debounce futures
        pending.values().forEach(f -> f.cancel(false));
        pending.clear();

        scheduler.shutdownNow();

        if (watchThread != null) {
            watchThread.interrupt();
        }

        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.warn("Error closing WatchService: {}", e.getMessage());
            }
        }
        log.info("FileWatcher stopped");
    }

    /** Returns {@code true} if the watcher is currently running. */
    public boolean isWatching() {
        return watching.get();
    }

    /** Returns the set of repository IDs currently being watched. */
    public Set<String> getWatchedRepoIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (RepoConfig rc : repoByRoot.values()) {
            ids.add(rc.getId());
        }
        return ids;
    }

    /** Returns the number of directories currently being watched. */
    public int getWatchedDirectoryCount() {
        return keyToDir.size();
    }

    /**
     * Returns the most recent file change events, newest first.
     *
     * @param limit max number of events to return
     */
    public List<FileChangeEvent> getRecentChanges(int limit) {
        List<FileChangeEvent> result = new ArrayList<>();
        for (FileChangeEvent event : recentChanges) {
            if (result.size() >= limit) break;
            result.add(event);
        }
        return result;
    }

    /**
     * Returns recent changes filtered by repository ID.
     */
    public List<FileChangeEvent> getRecentChanges(String repoId, int limit) {
        List<FileChangeEvent> result = new ArrayList<>();
        for (FileChangeEvent event : recentChanges) {
            if (result.size() >= limit) break;
            if (event.repoId().equals(repoId)) {
                result.add(event);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Watch loop
    // -------------------------------------------------------------------------

    private void watchLoop() {
        log.debug("Watch loop started");
        while (watching.get() && !Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = watchService.take(); // blocks until an event arrives
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }

            Path dir = keyToDir.get(key);
            if (dir == null) {
                key.reset();
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                if (kind == OVERFLOW) {
                    log.warn("WatchService OVERFLOW in {}, some events may have been missed", dir);
                    continue;
                }

                @SuppressWarnings("unchecked")
                WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                Path filename = pathEvent.context();
                Path absolutePath = dir.resolve(filename).toAbsolutePath().normalize();

                if (kind == ENTRY_CREATE && Files.isDirectory(absolutePath)) {
                    // New directory created: register it (and its children)
                    registerRecursive(absolutePath);
                    // Also re-index any files that may have been copied into it
                    scheduleDirectoryIndex(absolutePath);
                } else if (kind == ENTRY_CREATE || kind == ENTRY_MODIFY) {
                    if (Files.isRegularFile(absolutePath)) {
                        scheduleFileChange(absolutePath, false);
                    }
                } else if (kind == ENTRY_DELETE) {
                    scheduleFileChange(absolutePath, true);
                }
            }

            key.reset();
        }
        log.debug("Watch loop exited");
    }

    // -------------------------------------------------------------------------
    // Debouncing
    // -------------------------------------------------------------------------

    private void scheduleFileChange(Path absolutePath, boolean deleted) {
        RepoConfig repo = findRepo(absolutePath);
        if (repo == null) return; // not under any known repo
        if (isExcluded(absolutePath, repo)) return;

        long debounceMs = config.getIndexer().getWatchDebounceMs();

        if (deleted) {
            deletedFiles.add(absolutePath);
        } else {
            deletedFiles.remove(absolutePath);
        }

        ScheduledFuture<?> existing = pending.get(absolutePath);
        if (existing != null) {
            existing.cancel(false);
        }

        ScheduledFuture<?> future = scheduler.schedule(
                () -> flushFile(absolutePath, repo),
                debounceMs,
                TimeUnit.MILLISECONDS);
        pending.put(absolutePath, future);
    }

    private void scheduleDirectoryIndex(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    RepoConfig repo = findRepo(file);
                    if (repo != null && !isExcluded(file, repo)) {
                        scheduleFileChange(file, false);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Error walking new directory {}: {}", dir, e.getMessage());
        }
    }

    private void flushFile(Path absolutePath, RepoConfig repo) {
        pending.remove(absolutePath);
        boolean isDeleted = deletedFiles.remove(absolutePath);

        Path repoRoot = Path.of(repo.getPath()).toAbsolutePath().normalize();
        String relativePath = repoRoot.relativize(absolutePath).toString();

        if (isDeleted || !Files.exists(absolutePath)) {
            log.debug("File deleted, removing from index: {}", relativePath);
            recordChange(repo.getId(), relativePath, FileChangeType.DELETED);
            Thread.ofVirtual().start(() -> {
                try {
                    graphService.deleteElementsForFile(repo.getId(), relativePath);
                } catch (Exception e) {
                    log.error("Error deleting elements for file {}: {}", relativePath, e.getMessage(), e);
                }
            });
        } else {
            log.debug("File changed, re-indexing: {}", relativePath);
            recordChange(repo.getId(), relativePath, FileChangeType.MODIFIED);
            Thread.ofVirtual().start(() -> reindexFile(absolutePath, repo, repoRoot, relativePath));
        }
    }

    private void reindexFile(Path absolutePath, RepoConfig repo, Path repoRoot, String relativePath) {
        try {
            ParseResult result = parserRegistry.parse(absolutePath, repo.getId(), repoRoot);
            // Delete old elements first
            graphService.deleteElementsForFile(repo.getId(), relativePath);
            if (result.isSuccess()) {
                if (!result.getElements().isEmpty()) {
                    graphService.saveElements(result.getElements());
                }
                if (!result.getEdges().isEmpty()) {
                    graphService.saveEdges(result.getEdges());
                }
                log.debug("Re-indexed {} elements from {}", result.getElements().size(), relativePath);
            } else {
                log.debug("Parser reported no success for {}: {}", relativePath, result.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("Error re-indexing file {}: {}", relativePath, e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Recent changes tracking
    // -------------------------------------------------------------------------

    private void recordChange(String repoId, String filePath, FileChangeType changeType) {
        FileChangeEvent event = new FileChangeEvent(repoId, filePath, changeType, Instant.now());
        recentChanges.addFirst(event);
        // Trim to max size
        while (recentChanges.size() > MAX_RECENT_CHANGES) {
            recentChanges.pollLast();
        }
    }

    // -------------------------------------------------------------------------
    // Directory registration
    // -------------------------------------------------------------------------

    private void registerRecursive(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // Check if this directory itself is excluded by any repo config
                    RepoConfig repo = findRepo(dir);
                    if (repo != null && isExcluded(dir, repo)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    registerDir(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Could not visit {}: {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Failed to register directory tree at {}: {}", root, e.getMessage(), e);
        }
    }

    private void registerDir(Path dir) {
        try {
            WatchKey key;
            if (HIGH_SENSITIVITY != null) {
                // macOS: use HIGH sensitivity for ~2s polling instead of ~10s
                key = dir.register(watchService,
                        new WatchEvent.Kind[]{ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE},
                        HIGH_SENSITIVITY);
            } else {
                key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
            }
            keyToDir.put(key, dir);
            log.trace("Registered watch: {}", dir);
        } catch (IOException e) {
            log.warn("Could not register watch for {}: {}", dir, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Tries to load the macOS-specific HIGH sensitivity modifier for faster
     * file change detection. Returns null on non-macOS platforms.
     */
    private static WatchEvent.Modifier getHighSensitivityModifier() {
        try {
            Class<?> clazz = Class.forName("com.sun.nio.file.SensitivityWatchEventModifier");
            return (WatchEvent.Modifier) Enum.valueOf(
                    (Class<? extends Enum>) clazz, "HIGH");
        } catch (Exception e) {
            // Not on macOS or the internal API is unavailable
            return null;
        }
    }

    /**
     * Finds the RepoConfig whose root path is an ancestor of the given file path.
     * Returns {@code null} if the file is not under any configured repo.
     */
    private RepoConfig findRepo(Path absolutePath) {
        for (Map.Entry<Path, RepoConfig> entry : repoByRoot.entrySet()) {
            if (absolutePath.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Returns {@code true} if the given path matches any of the repo's exclude patterns.
     * Uses simple glob matching via {@link FileSystem#getPathMatcher(String)}.
     */
    private boolean isExcluded(Path absolutePath, RepoConfig repo) {
        if (repo.getExcludePatterns() == null || repo.getExcludePatterns().isEmpty()) {
            return false;
        }
        FileSystem fs = FileSystems.getDefault();
        // Match against the absolute path string for "**" patterns
        String pathStr = absolutePath.toString().replace('\\', '/');
        for (String pattern : repo.getExcludePatterns()) {
            try {
                PathMatcher matcher = fs.getPathMatcher("glob:" + pattern);
                // Try matching the absolute path directly
                if (matcher.matches(absolutePath)) return true;
                // Also try matching individual path components using the pattern
                // For patterns like "**/target/**", check if any segment matches
                if (pattern.contains("**")) {
                    String normalizedPattern = pattern.replace('\\', '/');
                    if (matchesGlobSegment(pathStr, normalizedPattern)) return true;
                }
            } catch (Exception e) {
                // Invalid pattern; skip it
            }
        }
        return false;
    }

    /**
     * Simple glob segment matcher for patterns like {@code &#42;&#42;/target/&#42;&#42;}.
     * Converts glob wildcards to a regex and tests against the normalized path string.
     */
    private boolean matchesGlobSegment(String pathStr, String pattern) {
        // Build a regex from the glob pattern
        StringBuilder regex = new StringBuilder("^");
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '*' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                regex.append(".*");
                i += 2;
                // skip trailing /
                if (i < pattern.length() && pattern.charAt(i) == '/') i++;
            } else if (c == '*') {
                regex.append("[^/]*");
                i++;
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        regex.append("$");
        try {
            return pathStr.matches(regex.toString());
        } catch (Exception e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Data types
    // -------------------------------------------------------------------------

    /** Type of file change event. */
    public enum FileChangeType {
        MODIFIED, DELETED
    }

    /** Record of a file change event for observability. */
    public record FileChangeEvent(
            String repoId,
            String filePath,
            FileChangeType changeType,
            Instant timestamp
    ) {}
}
