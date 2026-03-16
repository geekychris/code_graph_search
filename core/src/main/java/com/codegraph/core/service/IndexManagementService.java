package com.codegraph.core.service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Service interface for index management operations: reindexing, file watching,
 * and tracking recent file changes.
 *
 * <p>Implemented by the application layer where all components (indexer, watcher,
 * parser registry) are available.
 */
public interface IndexManagementService {

    /**
     * Trigger a full re-index of the given repository.
     * This is an asynchronous operation — returns immediately.
     *
     * @param repoId the repository ID to reindex
     * @return true if the reindex was started, false if the repo was not found
     */
    boolean reindexRepo(String repoId);

    /**
     * Add a new repository and start indexing it.
     *
     * @param id   repository ID
     * @param name display name
     * @param path absolute path to the repository root
     * @return true if added successfully
     */
    boolean addRepo(String id, String name, String path);

    /**
     * Remove a repository and all its indexed data.
     *
     * @param repoId the repository ID
     * @return true if removed, false if not found
     */
    boolean removeRepo(String repoId);

    /**
     * Start watching a repository for file changes.
     *
     * @param repoId the repository ID
     * @return true if watching was started
     */
    boolean startWatching(String repoId);

    /**
     * Stop watching a repository for file changes.
     *
     * @param repoId the repository ID
     */
    void stopWatching(String repoId);

    /** Returns true if the file watcher is running. */
    boolean isWatching();

    /** Returns the set of repo IDs currently being watched. */
    Set<String> getWatchedRepoIds();

    /** Returns the number of directories being watched. */
    int getWatchedDirectoryCount();

    /** Returns recent file change events (newest first). */
    List<FileChange> getRecentChanges(int limit);

    /** Returns recent file changes for a specific repo. */
    List<FileChange> getRecentChanges(String repoId, int limit);

    /**
     * A record representing a file change event.
     */
    record FileChange(
            String repoId,
            String filePath,
            String changeType,  // "MODIFIED" or "DELETED"
            Instant timestamp
    ) {}
}
