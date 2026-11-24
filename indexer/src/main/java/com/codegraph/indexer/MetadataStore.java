package com.codegraph.indexer;

import com.codegraph.core.model.Repository;
import com.codegraph.core.model.Visibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Manages SQLite metadata for repositories and file index.
 * Uses a single synchronized connection for simplicity and thread safety.
 */
public class MetadataStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MetadataStore.class);

    private final Connection conn;

    public MetadataStore(Path dbFile) throws SQLException {
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        conn = DriverManager.getConnection(url);
        conn.setAutoCommit(true);
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS repos (
                    id           TEXT PRIMARY KEY,
                    name         TEXT,
                    root_path    TEXT,
                    status       TEXT,
                    last_indexed TEXT,
                    element_count INT DEFAULT 0,
                    file_count   INT DEFAULT 0,
                    description  TEXT
                )
                """);
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS file_index (
                    repo_id       TEXT NOT NULL,
                    file_path     TEXT NOT NULL,
                    last_modified BIGINT,
                    file_hash     TEXT,
                    PRIMARY KEY (repo_id, file_path)
                )
                """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_file_index_repo ON file_index(repo_id)");
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS repo_facts (
                    repo_id    TEXT NOT NULL,
                    kind       TEXT NOT NULL,
                    commit_sha TEXT,
                    json       TEXT NOT NULL,
                    updated    TEXT,
                    PRIMARY KEY (repo_id, kind)
                )
                """);
        }
        migratePortfolioColumns();
    }

    // -------------------------------------------------------------------------
    // Repo facts: derived data per repo and kind (e.g. "manifests"), as JSON
    // -------------------------------------------------------------------------

    public synchronized void putFact(String repoId, String kind, String commit, String json) {
        String sql = """
            INSERT INTO repo_facts (repo_id, kind, commit_sha, json, updated) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(repo_id, kind) DO UPDATE SET
                commit_sha = excluded.commit_sha, json = excluded.json, updated = excluded.updated
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repoId);
            ps.setString(2, kind);
            ps.setString(3, commit);
            ps.setString(4, json);
            ps.setString(5, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to store {} for {}: {}", kind, repoId, e.getMessage(), e);
        }
    }

    /** All facts of one kind: repo id → JSON. */
    public synchronized Optional<String> getFact(String repoId, String kind) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT json FROM repo_facts WHERE repo_id = ? AND kind = ?")) {
            ps.setString(1, repoId);
            ps.setString(2, kind);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to read {} for {}: {}", kind, repoId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    public synchronized Map<String, String> listFacts(String kind) {
        Map<String, String> out = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT repo_id, json FROM repo_facts WHERE kind = ? ORDER BY repo_id")) {
            ps.setString(1, kind);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            log.error("Failed to list {}: {}", kind, e.getMessage(), e);
        }
        return out;
    }

    /** Portfolio columns added after the first release; older databases get them here. */
    private static final String[][] PORTFOLIO_COLUMNS = {
        {"visibility", "TEXT"}, {"source", "TEXT"}, {"git_url", "TEXT"}, {"html_url", "TEXT"},
        {"default_branch", "TEXT"}, {"indexed_commit", "TEXT"}, {"pushed_at", "TEXT"},
        {"archived", "INT DEFAULT 0"}
    };

    private void migratePortfolioColumns() throws SQLException {
        Set<String> existing = new HashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(repos)")) {
            while (rs.next()) existing.add(rs.getString("name"));
        }
        try (Statement stmt = conn.createStatement()) {
            for (String[] col : PORTFOLIO_COLUMNS) {
                if (!existing.contains(col[0])) {
                    stmt.executeUpdate("ALTER TABLE repos ADD COLUMN " + col[0] + " " + col[1]);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Repository operations
    // -------------------------------------------------------------------------

    public synchronized void upsertRepo(Repository repo) {
        String sql = """
            INSERT INTO repos (id, name, root_path, status, last_indexed, element_count, file_count, description,
                               visibility, source, git_url, html_url, default_branch, indexed_commit, pushed_at, archived)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name           = excluded.name,
                root_path      = excluded.root_path,
                status         = excluded.status,
                last_indexed   = excluded.last_indexed,
                element_count  = excluded.element_count,
                file_count     = excluded.file_count,
                description    = excluded.description,
                visibility     = excluded.visibility,
                source         = excluded.source,
                git_url        = excluded.git_url,
                html_url       = excluded.html_url,
                default_branch = excluded.default_branch,
                indexed_commit = excluded.indexed_commit,
                pushed_at      = excluded.pushed_at,
                archived       = excluded.archived
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repo.getId());
            ps.setString(2, repo.getName());
            ps.setString(3, repo.getRootPath());
            ps.setString(4, repo.getStatus() != null ? repo.getStatus().name() : null);
            ps.setString(5, repo.getLastIndexed() != null ? repo.getLastIndexed().toString() : null);
            ps.setInt(6, repo.getElementCount());
            ps.setInt(7, repo.getFileCount());
            ps.setString(8, repo.getDescription());
            ps.setString(9, repo.getVisibility() != null ? repo.getVisibility().name() : null);
            ps.setString(10, repo.getSource());
            ps.setString(11, repo.getGitUrl());
            ps.setString(12, repo.getHtmlUrl());
            ps.setString(13, repo.getDefaultBranch());
            ps.setString(14, repo.getIndexedCommit());
            ps.setString(15, repo.getPushedAt() != null ? repo.getPushedAt().toString() : null);
            ps.setInt(16, repo.isArchived() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to upsert repo {}: {}", repo.getId(), e.getMessage(), e);
        }
    }

    public synchronized Optional<Repository> getRepo(String id) {
        String sql = "SELECT * FROM repos WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRepo(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to get repo {}: {}", id, e.getMessage(), e);
        }
        return Optional.empty();
    }

    public synchronized List<Repository> listRepos() {
        List<Repository> result = new ArrayList<>();
        String sql = "SELECT * FROM repos ORDER BY name";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(mapRepo(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list repos: {}", e.getMessage(), e);
        }
        return result;
    }

    public synchronized void updateRepoStatus(String repoId, Repository.IndexingStatus status) {
        String sql = "UPDATE repos SET status = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, repoId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update repo status {}: {}", repoId, e.getMessage(), e);
        }
    }

    public synchronized void updateRepoStats(String repoId, int elementCount, int fileCount) {
        String sql = "UPDATE repos SET element_count = ?, file_count = ?, last_indexed = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, elementCount);
            ps.setInt(2, fileCount);
            ps.setString(3, Instant.now().toString());
            ps.setString(4, repoId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update repo stats {}: {}", repoId, e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // File index operations
    // -------------------------------------------------------------------------

    public synchronized void deleteFileIndex(String repoId, String filePath) {
        String sql = "DELETE FROM file_index WHERE repo_id = ? AND file_path = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repoId);
            ps.setString(2, filePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete file index {}/{}: {}", repoId, filePath, e.getMessage(), e);
        }
    }

    public synchronized void deleteRepoIndex(String repoId) {
        String sql1 = "DELETE FROM file_index WHERE repo_id = ?";
        String sql2 = "DELETE FROM repos WHERE id = ?";
        try (PreparedStatement ps1 = conn.prepareStatement(sql1);
             PreparedStatement ps2 = conn.prepareStatement(sql2);
             PreparedStatement ps3 = conn.prepareStatement("DELETE FROM repo_facts WHERE repo_id = ?")) {
            ps1.setString(1, repoId);
            ps1.executeUpdate();
            ps3.setString(1, repoId);
            ps3.executeUpdate();
            ps2.setString(1, repoId);
            ps2.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete repo index {}: {}", repoId, e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Repository mapRepo(ResultSet rs) throws SQLException {
        Repository repo = new Repository();
        repo.setId(rs.getString("id"));
        repo.setName(rs.getString("name"));
        repo.setRootPath(rs.getString("root_path"));
        String statusStr = rs.getString("status");
        if (statusStr != null) {
            try { repo.setStatus(Repository.IndexingStatus.valueOf(statusStr)); } catch (Exception ignored) {}
        }
        String lastIndexed = rs.getString("last_indexed");
        if (lastIndexed != null) {
            try { repo.setLastIndexed(Instant.parse(lastIndexed)); } catch (Exception ignored) {}
        }
        repo.setElementCount(rs.getInt("element_count"));
        repo.setFileCount(rs.getInt("file_count"));
        repo.setDescription(rs.getString("description"));
        String visibility = rs.getString("visibility");
        if (visibility != null) {
            try { repo.setVisibility(Visibility.valueOf(visibility)); } catch (Exception ignored) {}
        }
        repo.setSource(rs.getString("source"));
        repo.setGitUrl(rs.getString("git_url"));
        repo.setHtmlUrl(rs.getString("html_url"));
        repo.setDefaultBranch(rs.getString("default_branch"));
        repo.setIndexedCommit(rs.getString("indexed_commit"));
        String pushedAt = rs.getString("pushed_at");
        if (pushedAt != null) {
            try { repo.setPushedAt(Instant.parse(pushedAt)); } catch (Exception ignored) {}
        }
        repo.setArchived(rs.getInt("archived") != 0);
        return repo;
    }

    @Override
    public synchronized void close() {
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException e) {
            log.warn("Error closing SQLite connection: {}", e.getMessage());
        }
    }
}
