package com.codegraph.mcp.tools;

import com.codegraph.core.model.Repository;
import com.codegraph.core.service.GraphService;
import com.codegraph.core.service.IndexManagementService;
import com.codegraph.mcp.McpTool;

import java.util.*;

/**
 * MCP tools for managing the code index: reindexing repos, adding/removing repos,
 * controlling file watching, and viewing recent file changes.
 */
public class IndexManagementTools {

    // -------------------------------------------------------------------------
    // reindex_repo
    // -------------------------------------------------------------------------

    public static class ReindexRepo implements McpTool {
        private final IndexManagementService mgmt;
        public ReindexRepo(IndexManagementService mgmt) { this.mgmt = mgmt; }

        @Override public String getName() { return "reindex_repo"; }

        @Override
        public String getDescription() {
            return "Trigger a full re-index of a repository. This deletes existing index data and re-parses "
                    + "all files. The operation runs asynchronously — use list_repos to check progress. "
                    + "Use this when files have changed and the index is stale, or after modifying parser config.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            Map<String, Object> repoId = new LinkedHashMap<>();
            repoId.put("type", "string");
            repoId.put("description", "The repository ID to reindex.");
            props.put("repo_id", repoId);
            schema.put("properties", props);
            schema.put("required", List.of("repo_id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String repoId = (String) args.get("repo_id");
            if (repoId == null || repoId.isBlank()) return "Error: repo_id is required";
            boolean started = mgmt.reindexRepo(repoId);
            if (started) {
                return "Reindexing started for repo: " + repoId + "\nUse list_repos to check progress.";
            } else {
                return "Repository not found: " + repoId;
            }
        }
    }

    // -------------------------------------------------------------------------
    // add_repo
    // -------------------------------------------------------------------------

    public static class AddRepo implements McpTool {
        private final IndexManagementService mgmt;
        public AddRepo(IndexManagementService mgmt) { this.mgmt = mgmt; }

        @Override public String getName() { return "add_repo"; }

        @Override
        public String getDescription() {
            return "Add a new repository to the code graph and start indexing it. Provide the absolute "
                    + "path to the repository root directory. The repo will be indexed asynchronously and "
                    + "automatically watched for file changes (if watching is enabled).";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();

            Map<String, Object> id = new LinkedHashMap<>();
            id.put("type", "string");
            id.put("description", "Unique ID for the repository (e.g. 'my-project').");
            props.put("id", id);

            Map<String, Object> name = new LinkedHashMap<>();
            name.put("type", "string");
            name.put("description", "Display name for the repository.");
            props.put("name", name);

            Map<String, Object> path = new LinkedHashMap<>();
            path.put("type", "string");
            path.put("description", "Absolute path to the repository root directory.");
            props.put("path", path);

            schema.put("properties", props);
            schema.put("required", List.of("id", "path"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String id = (String) args.get("id");
            String name = (String) args.getOrDefault("name", id);
            String path = (String) args.get("path");
            if (id == null || path == null) return "Error: 'id' and 'path' are required";

            boolean added = mgmt.addRepo(id, name, path);
            if (added) {
                return "Repository '" + id + "' added and indexing started.\n"
                        + "Path: " + path + "\n"
                        + "Use list_repos to check indexing progress.";
            } else {
                return "Failed to add repository. The path may not exist or is not a directory: " + path;
            }
        }
    }

    // -------------------------------------------------------------------------
    // remove_repo
    // -------------------------------------------------------------------------

    public static class RemoveRepo implements McpTool {
        private final IndexManagementService mgmt;
        public RemoveRepo(IndexManagementService mgmt) { this.mgmt = mgmt; }

        @Override public String getName() { return "remove_repo"; }

        @Override
        public String getDescription() {
            return "Remove a repository from the code graph. This deletes all indexed elements, edges, "
                    + "and search data for the repo. The repository will also be unregistered from file watching. "
                    + "This action cannot be undone — use add_repo to re-add and reindex.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            Map<String, Object> repoId = new LinkedHashMap<>();
            repoId.put("type", "string");
            repoId.put("description", "The repository ID to remove.");
            props.put("repo_id", repoId);
            schema.put("properties", props);
            schema.put("required", List.of("repo_id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String repoId = (String) args.get("repo_id");
            if (repoId == null) return "Error: repo_id is required";
            boolean removed = mgmt.removeRepo(repoId);
            return removed
                    ? "Repository '" + repoId + "' removed. All indexed data deleted."
                    : "Repository not found: " + repoId;
        }
    }

    // -------------------------------------------------------------------------
    // get_watch_status
    // -------------------------------------------------------------------------

    public static class GetWatchStatus implements McpTool {
        private final IndexManagementService mgmt;
        public GetWatchStatus(IndexManagementService mgmt) { this.mgmt = mgmt; }

        @Override public String getName() { return "get_watch_status"; }

        @Override
        public String getDescription() {
            return "Get the status of the file watcher — whether it's running, which repos are being "
                    + "watched, and how many directories are monitored. On macOS, uses native FSEvents "
                    + "for efficient change detection.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", new LinkedHashMap<>());
            schema.put("required", List.of());
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            StringBuilder sb = new StringBuilder("## File Watcher Status\n\n");
            sb.append("- **Running:** ").append(mgmt.isWatching() ? "Yes" : "No").append("\n");
            sb.append("- **Watched directories:** ").append(mgmt.getWatchedDirectoryCount()).append("\n");

            Set<String> watchedRepos = mgmt.getWatchedRepoIds();
            sb.append("- **Watched repositories:** ").append(watchedRepos.size()).append("\n");
            for (String id : watchedRepos) {
                sb.append("  - ").append(id).append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // list_recent_changes
    // -------------------------------------------------------------------------

    public static class ListRecentChanges implements McpTool {
        private final IndexManagementService mgmt;
        public ListRecentChanges(IndexManagementService mgmt) { this.mgmt = mgmt; }

        @Override public String getName() { return "list_recent_changes"; }

        @Override
        public String getDescription() {
            return "List recently changed files detected by the file watcher. Shows which files were "
                    + "modified or deleted, with timestamps. Useful for understanding what changed "
                    + "recently and verifying that the watcher is picking up changes. Optionally filter "
                    + "by repository ID.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();

            Map<String, Object> repoId = new LinkedHashMap<>();
            repoId.put("type", "string");
            repoId.put("description", "Optional repository ID to filter changes by.");
            props.put("repo_id", repoId);

            Map<String, Object> limit = new LinkedHashMap<>();
            limit.put("type", "integer");
            limit.put("description", "Maximum number of changes to return (default 50).");
            limit.put("default", 50);
            props.put("limit", limit);

            schema.put("properties", props);
            schema.put("required", List.of());
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String repoId = (String) args.get("repo_id");
            int limit = ((Number) args.getOrDefault("limit", 50)).intValue();

            List<IndexManagementService.FileChange> changes = repoId != null
                    ? mgmt.getRecentChanges(repoId, limit)
                    : mgmt.getRecentChanges(limit);

            if (changes.isEmpty()) {
                return "No recent file changes recorded."
                        + (mgmt.isWatching() ? "" : "\n\nNote: File watcher is not currently running.");
            }

            StringBuilder sb = new StringBuilder("## Recent File Changes");
            if (repoId != null) sb.append(" (repo: ").append(repoId).append(")");
            sb.append(" — ").append(changes.size()).append(" event(s)\n\n");

            for (IndexManagementService.FileChange change : changes) {
                String icon = change.changeType().equals("DELETED") ? "[DEL]" : "[MOD]";
                sb.append("- ").append(icon).append(" ");
                if (repoId == null) sb.append("[").append(change.repoId()).append("] ");
                sb.append(change.filePath());
                sb.append(" — ").append(change.timestamp()).append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // start_watching / stop_watching
    // -------------------------------------------------------------------------

    public static class StartWatching implements McpTool {
        private final IndexManagementService mgmt;
        public StartWatching(IndexManagementService mgmt) { this.mgmt = mgmt; }

        @Override public String getName() { return "start_watching"; }

        @Override
        public String getDescription() {
            return "Start watching a repository for file changes. When files are created, modified, or "
                    + "deleted, the index is automatically updated. Changes are debounced to avoid "
                    + "excessive reindexing during rapid edits.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            Map<String, Object> repoId = new LinkedHashMap<>();
            repoId.put("type", "string");
            repoId.put("description", "The repository ID to start watching.");
            props.put("repo_id", repoId);
            schema.put("properties", props);
            schema.put("required", List.of("repo_id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String repoId = (String) args.get("repo_id");
            if (repoId == null) return "Error: repo_id is required";
            boolean started = mgmt.startWatching(repoId);
            return started
                    ? "Now watching repo '" + repoId + "' for file changes."
                    : "Failed to start watching. Repository may not exist: " + repoId;
        }
    }

    public static class StopWatching implements McpTool {
        private final IndexManagementService mgmt;
        public StopWatching(IndexManagementService mgmt) { this.mgmt = mgmt; }

        @Override public String getName() { return "stop_watching"; }

        @Override
        public String getDescription() {
            return "Stop watching a repository for file changes. The existing index data is preserved — "
                    + "only automatic updates stop. Use start_watching to resume.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            Map<String, Object> repoId = new LinkedHashMap<>();
            repoId.put("type", "string");
            repoId.put("description", "The repository ID to stop watching.");
            props.put("repo_id", repoId);
            schema.put("properties", props);
            schema.put("required", List.of("repo_id"));
            return schema;
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            String repoId = (String) args.get("repo_id");
            if (repoId == null) return "Error: repo_id is required";
            mgmt.stopWatching(repoId);
            return "Stopped watching repo '" + repoId + "'. Index data preserved.";
        }
    }
}
