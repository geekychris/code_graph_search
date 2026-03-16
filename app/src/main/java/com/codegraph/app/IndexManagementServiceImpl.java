package com.codegraph.app;

import com.codegraph.core.config.AppConfig;
import com.codegraph.core.config.RepoConfig;
import com.codegraph.core.model.Repository;
import com.codegraph.core.service.GraphService;
import com.codegraph.core.service.IndexManagementService;
import com.codegraph.indexer.IndexerService;
import com.codegraph.parser.ParserRegistry;
import com.codegraph.watcher.FileWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of {@link IndexManagementService} that coordinates between
 * the indexer, file watcher, and graph service.
 */
public class IndexManagementServiceImpl implements IndexManagementService {

    private static final Logger log = LoggerFactory.getLogger(IndexManagementServiceImpl.class);

    private final GraphService graphService;
    private final ParserRegistry parserRegistry;
    private final AppConfig config;
    private volatile FileWatcher fileWatcher;

    public IndexManagementServiceImpl(GraphService graphService, ParserRegistry parserRegistry, AppConfig config) {
        this.graphService = graphService;
        this.parserRegistry = parserRegistry;
        this.config = config;
    }

    /** Set the file watcher instance (may be null if watching is disabled). */
    public void setFileWatcher(FileWatcher fileWatcher) {
        this.fileWatcher = fileWatcher;
    }

    /** Get the file watcher (for use by RestServer). */
    public FileWatcher getFileWatcher() {
        return fileWatcher;
    }

    @Override
    public boolean reindexRepo(String repoId) {
        Optional<Repository> repoOpt = graphService.getRepository(repoId);
        if (repoOpt.isEmpty()) return false;

        Repository repo = repoOpt.get();
        repo.setStatus(Repository.IndexingStatus.PENDING);
        graphService.saveRepository(repo);

        Thread.ofVirtual().start(() -> {
            try {
                if (graphService instanceof IndexerService indexerService) {
                    RepoConfig repoConfig = new RepoConfig();
                    repoConfig.setId(repo.getId());
                    repoConfig.setName(repo.getName());
                    repoConfig.setPath(repo.getRootPath());
                    if (repo.getLanguages() != null) repoConfig.setLanguages(repo.getLanguages());
                    if (repo.getDescription() != null) repoConfig.setDescription(repo.getDescription());
                    indexerService.indexRepository(repoConfig, parserRegistry);
                    log.info("Reindexing complete for repo: {}", repoId);
                }
            } catch (Exception e) {
                log.error("Reindexing failed for repo {}: {}", repoId, e.getMessage(), e);
                repo.setStatus(Repository.IndexingStatus.ERROR);
                graphService.saveRepository(repo);
            }
        });

        return true;
    }

    @Override
    public boolean addRepo(String id, String name, String path) {
        Path root = Path.of(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return false;

        Repository repo = new Repository();
        repo.setId(id);
        repo.setName(name != null ? name : id);
        repo.setRootPath(root.toString());
        repo.setStatus(Repository.IndexingStatus.PENDING);
        graphService.saveRepository(repo);

        // Start indexing
        reindexRepo(id);

        // Start watching if watcher is active
        if (fileWatcher != null && fileWatcher.isWatching()) {
            RepoConfig rc = new RepoConfig();
            rc.setId(id);
            rc.setName(name);
            rc.setPath(root.toString());
            fileWatcher.addRepo(rc);
        }

        return true;
    }

    @Override
    public boolean removeRepo(String repoId) {
        Optional<Repository> repoOpt = graphService.getRepository(repoId);
        if (repoOpt.isEmpty()) return false;

        // Stop watching
        if (fileWatcher != null) {
            fileWatcher.removeRepo(repoId);
        }

        // Delete all data
        graphService.deleteRepo(repoId);
        return true;
    }

    @Override
    public boolean startWatching(String repoId) {
        Optional<Repository> repoOpt = graphService.getRepository(repoId);
        if (repoOpt.isEmpty()) return false;
        Repository repo = repoOpt.get();

        if (fileWatcher == null) {
            // Create and start the watcher if it doesn't exist yet
            fileWatcher = new FileWatcher(config, graphService, parserRegistry);
            fileWatcher.startWatching();
        }

        RepoConfig rc = new RepoConfig();
        rc.setId(repo.getId());
        rc.setName(repo.getName());
        rc.setPath(repo.getRootPath());
        if (repo.getLanguages() != null) rc.setLanguages(repo.getLanguages());
        fileWatcher.addRepo(rc);

        // Update repo status
        repo.setStatus(Repository.IndexingStatus.WATCHING);
        graphService.saveRepository(repo);
        return true;
    }

    @Override
    public void stopWatching(String repoId) {
        if (fileWatcher != null) {
            fileWatcher.removeRepo(repoId);
        }
        // Revert status from WATCHING to READY
        graphService.getRepository(repoId).ifPresent(repo -> {
            if (repo.getStatus() == Repository.IndexingStatus.WATCHING) {
                repo.setStatus(Repository.IndexingStatus.READY);
                graphService.saveRepository(repo);
            }
        });
    }

    @Override
    public boolean isWatching() {
        return fileWatcher != null && fileWatcher.isWatching();
    }

    @Override
    public Set<String> getWatchedRepoIds() {
        return fileWatcher != null ? fileWatcher.getWatchedRepoIds() : Set.of();
    }

    @Override
    public int getWatchedDirectoryCount() {
        return fileWatcher != null ? fileWatcher.getWatchedDirectoryCount() : 0;
    }

    @Override
    public List<FileChange> getRecentChanges(int limit) {
        if (fileWatcher == null) return List.of();
        return fileWatcher.getRecentChanges(limit).stream()
                .map(e -> new FileChange(e.repoId(), e.filePath(), e.changeType().name(), e.timestamp()))
                .collect(Collectors.toList());
    }

    @Override
    public List<FileChange> getRecentChanges(String repoId, int limit) {
        if (fileWatcher == null) return List.of();
        return fileWatcher.getRecentChanges(repoId, limit).stream()
                .map(e -> new FileChange(e.repoId(), e.filePath(), e.changeType().name(), e.timestamp()))
                .collect(Collectors.toList());
    }
}
