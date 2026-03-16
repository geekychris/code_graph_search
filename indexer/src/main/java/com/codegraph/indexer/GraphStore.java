package com.codegraph.indexer;

import com.codegraph.core.model.CodeEdge;
import com.codegraph.core.model.CodeElement;
import com.codegraph.core.model.EdgeType;
import com.codegraph.core.model.Repository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Manages RocksDB graph storage for CodeElements, CodeEdges, and Repositories.
 *
 * Column families:
 *   default       - unused (required by RocksDB)
 *   nodes         - elementId -> JSON CodeElement
 *   edges_out     - {fromId}\x00{edgeType}\x00{toId} -> JSON edge metadata
 *   edges_in      - {toId}\x00{edgeType}\x00{fromId} -> empty bytes (reverse index)
 *   file_elements - {repoId}\x00{filePath} -> JSON array of elementIds
 *   repos         - repoId -> JSON Repository
 */
public class GraphStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GraphStore.class);

    private static final byte SEP = 0x00;
    private static final byte[] EMPTY = new byte[0];

    private static final String CF_DEFAULT       = "default";
    private static final String CF_NODES         = "nodes";
    private static final String CF_EDGES_OUT     = "edges_out";
    private static final String CF_EDGES_IN      = "edges_in";
    private static final String CF_FILE_ELEMENTS = "file_elements";
    private static final String CF_REPOS         = "repos";

    private final RocksDB db;
    private final ColumnFamilyHandle cfNodes;
    private final ColumnFamilyHandle cfEdgesOut;
    private final ColumnFamilyHandle cfEdgesIn;
    private final ColumnFamilyHandle cfFileElements;
    private final ColumnFamilyHandle cfRepos;
    private final List<ColumnFamilyHandle> cfHandles;

    private final ObjectMapper mapper;

    static {
        RocksDB.loadLibrary();
    }

    public GraphStore(Path dbDir) throws RocksDBException {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());

        // Define all column families
        List<ColumnFamilyDescriptor> cfDescriptors = List.of(
            new ColumnFamilyDescriptor(CF_DEFAULT.getBytes(StandardCharsets.UTF_8), new ColumnFamilyOptions()),
            new ColumnFamilyDescriptor(CF_NODES.getBytes(StandardCharsets.UTF_8), new ColumnFamilyOptions()),
            new ColumnFamilyDescriptor(CF_EDGES_OUT.getBytes(StandardCharsets.UTF_8), new ColumnFamilyOptions()),
            new ColumnFamilyDescriptor(CF_EDGES_IN.getBytes(StandardCharsets.UTF_8), new ColumnFamilyOptions()),
            new ColumnFamilyDescriptor(CF_FILE_ELEMENTS.getBytes(StandardCharsets.UTF_8), new ColumnFamilyOptions()),
            new ColumnFamilyDescriptor(CF_REPOS.getBytes(StandardCharsets.UTF_8), new ColumnFamilyOptions())
        );

        DBOptions dbOptions = new DBOptions()
            .setCreateIfMissing(true)
            .setCreateMissingColumnFamilies(true);

        cfHandles = new ArrayList<>();
        this.db = RocksDB.open(dbOptions, dbDir.toString(), cfDescriptors, cfHandles);

        // Map handles by index (order matches cfDescriptors)
        cfNodes        = cfHandles.get(1);
        cfEdgesOut     = cfHandles.get(2);
        cfEdgesIn      = cfHandles.get(3);
        cfFileElements = cfHandles.get(4);
        cfRepos        = cfHandles.get(5);
    }

    // -------------------------------------------------------------------------
    // Element operations
    // -------------------------------------------------------------------------

    public void saveElement(CodeElement el) {
        if (el == null || el.getId() == null) return;
        try {
            byte[] key = el.getId().getBytes(StandardCharsets.UTF_8);
            byte[] value = mapper.writeValueAsBytes(el);
            db.put(cfNodes, key, value);

            // Update file_elements index
            if (el.getRepoId() != null && el.getFilePath() != null) {
                byte[] fileKey = fileElementsKey(el.getRepoId(), el.getFilePath());
                List<String> ids = getFileElementIds(fileKey);
                if (!ids.contains(el.getId())) {
                    ids.add(el.getId());
                    db.put(cfFileElements, fileKey, mapper.writeValueAsBytes(ids));
                }
            }
        } catch (Exception e) {
            log.error("Failed to save element {}: {}", el.getId(), e.getMessage(), e);
        }
    }

    public void saveElements(List<CodeElement> elements) {
        if (elements == null) return;
        try (WriteBatch batch = new WriteBatch()) {
            // Collect file_elements updates per file key
            Map<String, List<String>> fileUpdates = new LinkedHashMap<>();

            for (CodeElement el : elements) {
                if (el == null || el.getId() == null) continue;
                byte[] key = el.getId().getBytes(StandardCharsets.UTF_8);
                byte[] value = mapper.writeValueAsBytes(el);
                batch.put(cfNodes, key, value);

                if (el.getRepoId() != null && el.getFilePath() != null) {
                    String fk = el.getRepoId() + "\u0000" + el.getFilePath();
                    fileUpdates.computeIfAbsent(fk, k -> new ArrayList<>()).add(el.getId());
                }
            }

            // Merge file_elements
            for (Map.Entry<String, List<String>> entry : fileUpdates.entrySet()) {
                String[] parts = entry.getKey().split("\u0000", 2);
                byte[] fileKey = fileElementsKey(parts[0], parts[1]);
                List<String> existing = getFileElementIds(fileKey);
                for (String id : entry.getValue()) {
                    if (!existing.contains(id)) existing.add(id);
                }
                batch.put(cfFileElements, fileKey, mapper.writeValueAsBytes(existing));
            }

            db.write(new WriteOptions(), batch);
        } catch (Exception e) {
            log.error("Failed to save elements batch: {}", e.getMessage(), e);
        }
    }

    public Optional<CodeElement> getElement(String id) {
        if (id == null) return Optional.empty();
        try {
            byte[] value = db.get(cfNodes, id.getBytes(StandardCharsets.UTF_8));
            if (value == null) return Optional.empty();
            return Optional.of(mapper.readValue(value, CodeElement.class));
        } catch (Exception e) {
            log.error("Failed to get element {}: {}", id, e.getMessage(), e);
            return Optional.empty();
        }
    }

    public List<CodeElement> getElementsForFile(String repoId, String filePath) {
        byte[] fileKey = fileElementsKey(repoId, filePath);
        List<String> ids = getFileElementIds(fileKey);
        List<CodeElement> results = new ArrayList<>();
        for (String id : ids) {
            getElement(id).ifPresent(results::add);
        }
        return results;
    }

    /**
     * List all file paths indexed for a given repository by scanning the
     * file_elements column family prefix.
     */
    public List<String> listFilePaths(String repoId) {
        List<String> paths = new ArrayList<>();
        byte[] prefix = (repoId + "\u0000").getBytes(StandardCharsets.UTF_8);
        try (RocksIterator it = db.newIterator(cfFileElements)) {
            for (it.seek(prefix); it.isValid(); it.next()) {
                byte[] key = it.key();
                if (!hasPrefix(key, prefix)) break;
                String filePath = new String(key, prefix.length, key.length - prefix.length, StandardCharsets.UTF_8);
                paths.add(filePath);
            }
        }
        return paths;
    }

    public void deleteElementsForFile(String repoId, String filePath) {
        try {
            byte[] fileKey = fileElementsKey(repoId, filePath);
            List<String> ids = getFileElementIds(fileKey);

            try (WriteBatch batch = new WriteBatch()) {
                for (String id : ids) {
                    batch.delete(cfNodes, id.getBytes(StandardCharsets.UTF_8));
                    deleteEdgesForElement(id, batch);
                }
                batch.delete(cfFileElements, fileKey);
                db.write(new WriteOptions(), batch);
            }
        } catch (Exception e) {
            log.error("Failed to delete elements for file {}/{}: {}", repoId, filePath, e.getMessage(), e);
        }
    }

    public void deleteByRepo(String repoId) {
        try {
            // Scan all nodes that belong to this repo
            List<String> toDelete = new ArrayList<>();
            try (RocksIterator it = db.newIterator(cfNodes)) {
                for (it.seekToFirst(); it.isValid(); it.next()) {
                    try {
                        CodeElement el = mapper.readValue(it.value(), CodeElement.class);
                        if (repoId.equals(el.getRepoId())) {
                            toDelete.add(el.getId());
                        }
                    } catch (Exception ignored) {}
                }
            }

            try (WriteBatch batch = new WriteBatch()) {
                for (String id : toDelete) {
                    batch.delete(cfNodes, id.getBytes(StandardCharsets.UTF_8));
                    deleteEdgesForElement(id, batch);
                }

                // Delete file_elements entries for this repo
                byte[] repoPrefix = (repoId + "\u0000").getBytes(StandardCharsets.UTF_8);
                try (RocksIterator it = db.newIterator(cfFileElements)) {
                    for (it.seek(repoPrefix); it.isValid(); it.next()) {
                        if (!hasPrefix(it.key(), repoPrefix)) break;
                        batch.delete(cfFileElements, it.key());
                    }
                }

                // Delete repo entry
                batch.delete(cfRepos, repoId.getBytes(StandardCharsets.UTF_8));
                db.write(new WriteOptions(), batch);
            }
        } catch (Exception e) {
            log.error("Failed to delete repo {}: {}", repoId, e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Edge operations
    // -------------------------------------------------------------------------

    public void saveEdge(CodeEdge edge) {
        if (edge == null || edge.getFromId() == null || edge.getToId() == null || edge.getEdgeType() == null) return;
        try (WriteBatch batch = new WriteBatch()) {
            byte[] outKey = edgeOutKey(edge.getFromId(), edge.getEdgeType().name(), edge.getToId());
            byte[] inKey  = edgeInKey(edge.getToId(), edge.getEdgeType().name(), edge.getFromId());
            byte[] value  = edge.getMetadata() != null ? mapper.writeValueAsBytes(edge.getMetadata()) : EMPTY;
            batch.put(cfEdgesOut, outKey, value);
            batch.put(cfEdgesIn,  inKey,  EMPTY);
            db.write(new WriteOptions(), batch);
        } catch (Exception e) {
            log.error("Failed to save edge {}: {}", edge.getId(), e.getMessage(), e);
        }
    }

    public void saveEdges(List<CodeEdge> edges) {
        if (edges == null) return;
        try (WriteBatch batch = new WriteBatch()) {
            for (CodeEdge edge : edges) {
                if (edge == null || edge.getFromId() == null || edge.getToId() == null || edge.getEdgeType() == null) continue;
                byte[] outKey = edgeOutKey(edge.getFromId(), edge.getEdgeType().name(), edge.getToId());
                byte[] inKey  = edgeInKey(edge.getToId(), edge.getEdgeType().name(), edge.getFromId());
                byte[] value  = edge.getMetadata() != null ? mapper.writeValueAsBytes(edge.getMetadata()) : EMPTY;
                batch.put(cfEdgesOut, outKey, value);
                batch.put(cfEdgesIn,  inKey,  EMPTY);
            }
            db.write(new WriteOptions(), batch);
        } catch (Exception e) {
            log.error("Failed to save edges batch: {}", e.getMessage(), e);
        }
    }

    public List<CodeEdge> getEdgesFrom(String elementId) {
        return scanEdgesOut(elementId, null);
    }

    public List<CodeEdge> getEdgesFrom(String elementId, EdgeType type) {
        return scanEdgesOut(elementId, type.name());
    }

    public List<CodeEdge> getEdgesTo(String elementId) {
        return scanEdgesIn(elementId, null);
    }

    public List<CodeEdge> getEdgesTo(String elementId, EdgeType type) {
        return scanEdgesIn(elementId, type.name());
    }

    /**
     * Returns the neighbor elements reachable from elementId via the given edge type.
     * @param outgoing true = follow edges_out (elementId is fromId), false = follow edges_in (elementId is toId)
     */
    public List<CodeElement> getNeighbors(String elementId, EdgeType type, boolean outgoing) {
        List<CodeEdge> edges = outgoing ? getEdgesFrom(elementId, type) : getEdgesTo(elementId, type);
        List<CodeElement> result = new ArrayList<>();
        for (CodeEdge edge : edges) {
            String neighborId = outgoing ? edge.getToId() : edge.getFromId();
            getElement(neighborId).ifPresent(result::add);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Repository operations
    // -------------------------------------------------------------------------

    public void saveRepo(Repository repo) {
        if (repo == null || repo.getId() == null) return;
        try {
            db.put(cfRepos,
                repo.getId().getBytes(StandardCharsets.UTF_8),
                mapper.writeValueAsBytes(repo));
        } catch (Exception e) {
            log.error("Failed to save repo {}: {}", repo.getId(), e.getMessage(), e);
        }
    }

    public Optional<Repository> getRepo(String id) {
        if (id == null) return Optional.empty();
        try {
            byte[] value = db.get(cfRepos, id.getBytes(StandardCharsets.UTF_8));
            if (value == null) return Optional.empty();
            return Optional.of(mapper.readValue(value, Repository.class));
        } catch (Exception e) {
            log.error("Failed to get repo {}: {}", id, e.getMessage(), e);
            return Optional.empty();
        }
    }

    public List<Repository> listRepos() {
        List<Repository> result = new ArrayList<>();
        try (RocksIterator it = db.newIterator(cfRepos)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                try {
                    result.add(mapper.readValue(it.value(), Repository.class));
                } catch (Exception ignored) {}
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private List<String> getFileElementIds(byte[] fileKey) {
        try {
            byte[] value = db.get(cfFileElements, fileKey);
            if (value == null) return new ArrayList<>();
            return mapper.readValue(value, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void deleteEdgesForElement(String elementId, WriteBatch batch) throws RocksDBException {
        byte[] outPrefix = prefixOf(elementId);
        try (RocksIterator it = db.newIterator(cfEdgesOut)) {
            for (it.seek(outPrefix); it.isValid(); it.next()) {
                if (!hasPrefix(it.key(), outPrefix)) break;
                batch.delete(cfEdgesOut, it.key());
                // Also delete corresponding edges_in entry
                String[] parts = parseEdgeKey(it.key());
                if (parts != null && parts.length == 3) {
                    batch.delete(cfEdgesIn, edgeInKey(parts[2], parts[1], parts[0]));
                }
            }
        }
        // Delete all edges_in entries pointing to this element
        byte[] inPrefix = prefixOf(elementId);
        try (RocksIterator it = db.newIterator(cfEdgesIn)) {
            for (it.seek(inPrefix); it.isValid(); it.next()) {
                if (!hasPrefix(it.key(), inPrefix)) break;
                batch.delete(cfEdgesIn, it.key());
                // Also delete corresponding edges_out entry
                String[] parts = parseEdgeKey(it.key());
                if (parts != null && parts.length == 3) {
                    batch.delete(cfEdgesOut, edgeOutKey(parts[2], parts[1], parts[0]));
                }
            }
        }
    }

    private List<CodeEdge> scanEdgesOut(String elementId, String edgeTypeName) {
        List<CodeEdge> result = new ArrayList<>();
        byte[] prefix = edgeTypeName != null
            ? edgePrefixWithType(elementId, edgeTypeName)
            : prefixOf(elementId);
        try (RocksIterator it = db.newIterator(cfEdgesOut)) {
            for (it.seek(prefix); it.isValid(); it.next()) {
                if (!hasPrefix(it.key(), prefix)) break;
                String[] parts = parseEdgeKey(it.key());
                if (parts == null || parts.length != 3) continue;
                CodeEdge edge = new CodeEdge(parts[0], parts[2], edgeTypeOf(parts[1]));
                result.add(edge);
            }
        }
        return result;
    }

    private List<CodeEdge> scanEdgesIn(String elementId, String edgeTypeName) {
        List<CodeEdge> result = new ArrayList<>();
        byte[] prefix = edgeTypeName != null
            ? edgePrefixWithType(elementId, edgeTypeName)
            : prefixOf(elementId);
        try (RocksIterator it = db.newIterator(cfEdgesIn)) {
            for (it.seek(prefix); it.isValid(); it.next()) {
                if (!hasPrefix(it.key(), prefix)) break;
                // edges_in key: {toId}\x00{edgeType}\x00{fromId}
                String[] parts = parseEdgeKey(it.key());
                if (parts == null || parts.length != 3) continue;
                // parts[0]=toId, parts[1]=edgeType, parts[2]=fromId
                CodeEdge edge = new CodeEdge(parts[2], parts[0], edgeTypeOf(parts[1]));
                result.add(edge);
            }
        }
        return result;
    }

    // Key builders
    private byte[] edgeOutKey(String fromId, String edgeType, String toId) {
        return compositeKey(fromId, edgeType, toId);
    }

    private byte[] edgeInKey(String toId, String edgeType, String fromId) {
        return compositeKey(toId, edgeType, fromId);
    }

    private byte[] compositeKey(String... parts) {
        int totalLen = 0;
        byte[][] encoded = new byte[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            encoded[i] = parts[i].getBytes(StandardCharsets.UTF_8);
            totalLen += encoded[i].length;
        }
        totalLen += parts.length - 1; // separators
        byte[] key = new byte[totalLen];
        int pos = 0;
        for (int i = 0; i < encoded.length; i++) {
            System.arraycopy(encoded[i], 0, key, pos, encoded[i].length);
            pos += encoded[i].length;
            if (i < encoded.length - 1) key[pos++] = SEP;
        }
        return key;
    }

    private byte[] prefixOf(String id) {
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        byte[] prefix = new byte[idBytes.length + 1];
        System.arraycopy(idBytes, 0, prefix, 0, idBytes.length);
        prefix[idBytes.length] = SEP;
        return prefix;
    }

    private byte[] edgePrefixWithType(String id, String edgeType) {
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        byte[] typeBytes = edgeType.getBytes(StandardCharsets.UTF_8);
        byte[] prefix = new byte[idBytes.length + 1 + typeBytes.length + 1];
        int pos = 0;
        System.arraycopy(idBytes, 0, prefix, pos, idBytes.length);
        pos += idBytes.length;
        prefix[pos++] = SEP;
        System.arraycopy(typeBytes, 0, prefix, pos, typeBytes.length);
        pos += typeBytes.length;
        prefix[pos] = SEP;
        return prefix;
    }

    private byte[] fileElementsKey(String repoId, String filePath) {
        return compositeKey(repoId, filePath);
    }

    /** Parse a composite key with \x00 separators into exactly 3 parts. */
    private String[] parseEdgeKey(byte[] key) {
        // Find two separator positions
        int sep1 = -1, sep2 = -1;
        for (int i = 0; i < key.length; i++) {
            if (key[i] == SEP) {
                if (sep1 < 0) sep1 = i;
                else { sep2 = i; break; }
            }
        }
        if (sep1 < 0 || sep2 < 0) return null;
        String part0 = new String(key, 0, sep1, StandardCharsets.UTF_8);
        String part1 = new String(key, sep1 + 1, sep2 - sep1 - 1, StandardCharsets.UTF_8);
        String part2 = new String(key, sep2 + 1, key.length - sep2 - 1, StandardCharsets.UTF_8);
        return new String[]{part0, part1, part2};
    }

    private boolean hasPrefix(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) return false;
        }
        return true;
    }

    private EdgeType edgeTypeOf(String name) {
        try { return EdgeType.valueOf(name); } catch (Exception e) { return null; }
    }

    @Override
    public void close() {
        for (ColumnFamilyHandle handle : cfHandles) {
            handle.close();
        }
        db.close();
    }
}
