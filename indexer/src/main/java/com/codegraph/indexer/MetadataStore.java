package com.codegraph.indexer;

import com.codegraph.core.model.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    }

    // -------------------------------------------------------------------------
    // Repository operations
    // -------------------------------------------------------------------------

    public synchronized void upsertRepo(Repository repo) {
        String sql = """
            INSERT INTO repos (id, name, root_path, status, last_indexed, element_count, file_count, description)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name          = excluded.name,
                root_path     = excluded.root_path,
                status        = excluded.status,
                last_indexed  = excluded.last_indexed,
                element_count = excluded.element_count,
                file_count    = excluded.file_count,
                description   = excluded.description
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

    public synchronized void upsertFileIndex(String repoId, String filePath, long lastModified, String fileHash) {
        String sql = """
            INSERT INTO file_index (repo_id, file_path, last_modified, file_hash)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(repo_id, file_path) DO UPDATE SET
                last_modified = excluded.last_modified,
                file_hash     = excluded.file_hash
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repoId);
            ps.setString(2, filePath);
            ps.setLong(3, lastModified);
            ps.setString(4, fileHash);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to upsert file index {}/{}: {}", repoId, filePath, e.getMessage(), e);
        }
    }

    /**
     * Returns true if the file is new (not in index) or its lastModified has changed.
     */
    public synchronized boolean isFileModified(String repoId, String filePath, long lastModified) {
        String sql = "SELECT last_modified FROM file_index WHERE repo_id = ? AND file_path = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repoId);
            ps.setString(2, filePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return true; // new file
                return rs.getLong("last_modified") != lastModified;
            }
        } catch (SQLException e) {
            log.error("Failed to check file modified {}/{}: {}", repoId, filePath, e.getMessage(), e);
            return true; // assume modified on error
        }
    }

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
             PreparedStatement ps2 = conn.prepareStatement(sql2)) {
            ps1.setString(1, repoId);
            ps1.executeUpdate();
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
