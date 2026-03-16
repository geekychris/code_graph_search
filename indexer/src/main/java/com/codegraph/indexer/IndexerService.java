package com.codegraph.indexer;

import com.codegraph.core.config.AppConfig;
import com.codegraph.core.config.RepoConfig;
import com.codegraph.core.model.*;
import com.codegraph.core.service.GraphService;
import com.codegraph.parser.ParseResult;
import com.codegraph.parser.ParserRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Primary implementation of {@link GraphService}.
 * Delegates persistence to {@link LuceneIndexer} (full-text search),
 * {@link GraphStore} (RocksDB graph), and {@link MetadataStore} (SQLite metadata).
 */
public class IndexerService implements GraphService {

    private static final Logger log = LoggerFactory.getLogger(IndexerService.class);

    private final LuceneIndexer lucene;
    private final GraphStore    graph;
    private final MetadataStore metadata;
    private final ExecutorService executor;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public IndexerService(AppConfig.IndexerConfig config) {
        this(config.getDataDir(), config.getIndexingThreads());
    }

    public IndexerService(String dataDir) {
        this(dataDir, 4);
    }

    public IndexerService(String dataDir, int threads) {
        Path root = Paths.get(dataDir);
        Path luceneDir = root.resolve("lucene");
        Path rocksDir  = root.resolve("rocksdb");
        Path sqliteFile = root.resolve("metadata.db");

        try {
            Files.createDirectories(luceneDir);
            Files.createDirectories(rocksDir);
            Files.createDirectories(root); // ensure parent for sqlite
        } catch (IOException e) {
            throw new RuntimeException("Failed to create data directories under " + dataDir, e);
        }

        try {
            this.lucene = new LuceneIndexer(luceneDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open Lucene index at " + luceneDir, e);
        }

        try {
            this.graph = new GraphStore(rocksDir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to open RocksDB at " + rocksDir, e);
        }

        try {
            this.metadata = new MetadataStore(sqliteFile);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open SQLite at " + sqliteFile, e);
        }

        this.executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "indexer-worker");
            t.setDaemon(true);
            return t;
        });
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    @Override
    public void saveElement(CodeElement element) {
        if (element == null) return;
        graph.saveElement(element);
        lucene.indexElement(element);
    }

    @Override
    public void saveElements(List<CodeElement> elements) {
        if (elements == null || elements.isEmpty()) return;
        // Partition into chunks and save in parallel
        int chunkSize = Math.max(1, elements.size() / Math.max(1, getThreadCount()));
        List<List<CodeElement>> chunks = partition(elements, chunkSize);
        List<Future<?>> futures = new ArrayList<>();
        for (List<CodeElement> chunk : chunks) {
            futures.add(executor.submit(() -> {
                graph.saveElements(chunk);
                for (CodeElement el : chunk) {
                    lucene.indexElement(el);
                }
            }));
        }
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception e) {
                log.error("Error in parallel element save: {}", e.getMessage(), e);
            }
        }
    }

    @Override
    public void saveEdge(CodeEdge edge) {
        if (edge == null) return;
        graph.saveEdge(edge);
    }

    @Override
    public void saveEdges(List<CodeEdge> edges) {
        if (edges == null || edges.isEmpty()) return;
        graph.saveEdges(edges);
    }

    @Override
    public void deleteElementsForFile(String repoId, String filePath) {
        graph.deleteElementsForFile(repoId, filePath);
        lucene.deleteElementsForFile(repoId, filePath);
        metadata.deleteFileIndex(repoId, filePath);
    }

    @Override
    public void deleteRepo(String repoId) {
        graph.deleteByRepo(repoId);
        lucene.deleteByRepo(repoId);
        metadata.deleteRepoIndex(repoId);
    }

    // -------------------------------------------------------------------------
    // Element retrieval
    // -------------------------------------------------------------------------

    @Override
    public Optional<CodeElement> getElementById(String id) {
        // RocksDB is faster for direct key lookup
        return graph.getElement(id);
    }

    @Override
    public List<CodeElement> getElementsByFile(String repoId, String filePath) {
        return graph.getElementsForFile(repoId, filePath);
    }

    @Override
    public List<CodeElement> getElementsByQualifiedName(String repoId, String qualifiedName) {
        SearchQuery sq = new SearchQuery();
        if (repoId != null) sq.setRepoIds(List.of(repoId));
        sq.setQualifiedNamePrefix(qualifiedName);
        sq.setLimit(500);
        List<CodeElement> results = lucene.search(sq);
        // Filter for exact match (prefix search may return broader results)
        return results.stream()
            .filter(el -> qualifiedName.equals(el.getQualifiedName()))
            .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Graph traversal
    // -------------------------------------------------------------------------

    @Override
    public Optional<CodeElement> getParent(String elementId) {
        return getElementById(elementId)
            .flatMap(el -> el.getParentId() != null ? getElementById(el.getParentId()) : Optional.empty());
    }

    @Override
    public List<CodeElement> getAncestors(String elementId) {
        List<CodeElement> ancestors = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String current = elementId;
        while (current != null) {
            if (!visited.add(current)) break; // cycle guard
            Optional<CodeElement> el = getElementById(current);
            if (el.isEmpty()) break;
            String parentId = el.get().getParentId();
            if (parentId == null) break;
            Optional<CodeElement> parent = getElementById(parentId);
            if (parent.isEmpty()) break;
            ancestors.add(parent.get());
            current = parentId;
        }
        return ancestors;
    }

    @Override
    public List<CodeElement> getChildren(String elementId) {
        return graph.getNeighbors(elementId, EdgeType.CONTAINS, true);
    }

    @Override
    public List<CodeElement> getChildren(String elementId, ElementType type) {
        return getChildren(elementId).stream()
            .filter(el -> type.equals(el.getElementType()))
            .collect(Collectors.toList());
    }

    @Override
    public List<CodeElement> getSiblings(String elementId) {
        return getParent(elementId)
            .map(parent -> {
                List<CodeElement> children = getChildren(parent.getId());
                return children.stream()
                    .filter(el -> !elementId.equals(el.getId()))
                    .sorted(Comparator.comparingInt(CodeElement::getLineStart))
                    .collect(Collectors.toList());
            })
            .orElse(Collections.emptyList());
    }

    // -------------------------------------------------------------------------
    // Type hierarchy
    // -------------------------------------------------------------------------

    @Override
    public Optional<CodeElement> getSuperclass(String classId) {
        List<CodeElement> superclasses = graph.getNeighbors(classId, EdgeType.EXTENDS, true);
        return superclasses.isEmpty() ? Optional.empty() : Optional.of(superclasses.get(0));
    }

    @Override
    public List<CodeElement> getInterfaces(String classId) {
        return graph.getNeighbors(classId, EdgeType.IMPLEMENTS, true);
    }

    @Override
    public List<CodeElement> getSubclasses(String classId) {
        return graph.getNeighbors(classId, EdgeType.EXTENDS, false);
    }

    @Override
    public List<CodeElement> getImplementors(String interfaceId) {
        return graph.getNeighbors(interfaceId, EdgeType.IMPLEMENTS, false);
    }

    @Override
    public Optional<CodeElement> getOverriddenMethod(String methodId) {
        List<CodeElement> overridden = graph.getNeighbors(methodId, EdgeType.OVERRIDES, true);
        return overridden.isEmpty() ? Optional.empty() : Optional.of(overridden.get(0));
    }

    @Override
    public List<CodeElement> getOverriders(String methodId) {
        return graph.getNeighbors(methodId, EdgeType.OVERRIDES, false);
    }

    // -------------------------------------------------------------------------
    // Call graph
    // -------------------------------------------------------------------------

    @Override
    public List<CodeElement> getCallers(String methodId) {
        return graph.getNeighbors(methodId, EdgeType.CALLS, false);
    }

    @Override
    public List<CodeElement> getCallees(String methodId) {
        return graph.getNeighbors(methodId, EdgeType.CALLS, true);
    }

    /**
     * BFS from fromMethodId to toMethodId via CALLS edges.
     * Returns up to 10 paths, each path containing the nodes in order from -> to.
     */
    @Override
    public List<List<CodeElement>> getCallChain(String fromMethodId, String toMethodId, int maxDepth) {
        List<List<CodeElement>> allPaths = new ArrayList<>();
        if (fromMethodId == null || toMethodId == null) return allPaths;

        Optional<CodeElement> startEl = getElementById(fromMethodId);
        if (startEl.isEmpty()) return allPaths;

        // BFS queue: each entry is a path (list of element IDs)
        Queue<List<String>> queue = new ArrayDeque<>();
        queue.offer(List.of(fromMethodId));

        int maxPaths = 10;

        while (!queue.isEmpty() && allPaths.size() < maxPaths) {
            List<String> path = queue.poll();
            if (path.size() > maxDepth + 1) continue;

            String last = path.get(path.size() - 1);
            if (last.equals(toMethodId)) {
                // Found a path - resolve all elements
                List<CodeElement> resolved = new ArrayList<>();
                boolean valid = true;
                for (String id : path) {
                    Optional<CodeElement> el = getElementById(id);
                    if (el.isEmpty()) { valid = false; break; }
                    resolved.add(el.get());
                }
                if (valid) allPaths.add(resolved);
                continue;
            }

            if (path.size() > maxDepth) continue;

            List<CodeEdge> outEdges = graph.getEdgesFrom(last, EdgeType.CALLS);
            Set<String> pathSet = new HashSet<>(path);
            for (CodeEdge edge : outEdges) {
                String nextId = edge.getToId();
                if (!pathSet.contains(nextId)) { // avoid cycles
                    List<String> newPath = new ArrayList<>(path);
                    newPath.add(nextId);
                    queue.offer(newPath);
                }
            }
        }

        return allPaths;
    }

    // -------------------------------------------------------------------------
    // Documentation
    // -------------------------------------------------------------------------

    @Override
    public List<CodeElement> getComments(String elementId) {
        // Comments document elements: edge DOCUMENTS goes from comment -> element
        // edges_in on elementId with DOCUMENTS type gives us comment elements
        return graph.getNeighbors(elementId, EdgeType.DOCUMENTS, false);
    }

    @Override
    public List<CodeElement> getAnnotations(String elementId) {
        // ANNOTATES edge: from annotation -> target element
        return graph.getNeighbors(elementId, EdgeType.ANNOTATES, false);
    }

    // -------------------------------------------------------------------------
    // Imports / dependencies
    // -------------------------------------------------------------------------

    @Override
    public List<CodeElement> getImports(String fileElementId) {
        return getChildren(fileElementId, ElementType.IMPORT);
    }

    @Override
    public List<CodeElement> getImportedBy(String fileElementId) {
        return graph.getNeighbors(fileElementId, EdgeType.IMPORTS, false);
    }

    @Override
    public List<CodeElement> getUsages(String elementId) {
        List<CodeElement> result = new ArrayList<>();
        result.addAll(graph.getNeighbors(elementId, EdgeType.CALLS,        false));
        result.addAll(graph.getNeighbors(elementId, EdgeType.USES_TYPE,    false));
        result.addAll(graph.getNeighbors(elementId, EdgeType.INSTANTIATES, false));
        // Deduplicate by id
        Map<String, CodeElement> seen = new LinkedHashMap<>();
        for (CodeElement el : result) {
            seen.put(el.getId(), el);
        }
        return new ArrayList<>(seen.values());
    }

    // -------------------------------------------------------------------------
    // Repository
    // -------------------------------------------------------------------------

    @Override
    public List<Repository> listRepositories() {
        return metadata.listRepos();
    }

    @Override
    public Optional<Repository> getRepository(String repoId) {
        return metadata.getRepo(repoId);
    }

    @Override
    public void saveRepository(Repository repo) {
        metadata.upsertRepo(repo);
        graph.saveRepo(repo);
    }

    @Override
    public List<String> listFilePaths(String repoId) {
        return graph.listFilePaths(repoId);
    }

    // -------------------------------------------------------------------------
    // FOAF / Connectivity
    // -------------------------------------------------------------------------

    @Override
    public PathResult findShortestPath(PathQuery query) {
        return FoafAlgorithms.shortestPath(graph, this::getElementById, query);
    }

    @Override
    public PathResult findAllShortestPaths(PathQuery query) {
        return FoafAlgorithms.allShortestPaths(graph, this::getElementById, query);
    }

    @Override
    public PathResult findAllPaths(PathQuery query) {
        return FoafAlgorithms.allPaths(graph, this::getElementById, query);
    }

    @Override
    public SimilarityResult computeSimilarity(String elementIdA, String elementIdB,
                                               EdgeDirection direction, List<EdgeType> edgeTypes) {
        return FoafAlgorithms.similarity(graph, this::getElementById, elementIdA, elementIdB, direction, edgeTypes);
    }

    // -------------------------------------------------------------------------
    // Edges
    // -------------------------------------------------------------------------

    @Override
    public List<CodeEdge> getEdgesFrom(String elementId) {
        return graph.getEdgesFrom(elementId);
    }

    @Override
    public List<CodeEdge> getEdgesTo(String elementId) {
        return graph.getEdgesTo(elementId);
    }

    @Override
    public List<CodeEdge> getEdgesFrom(String elementId, EdgeType type) {
        return graph.getEdgesFrom(elementId, type);
    }

    @Override
    public List<CodeEdge> getEdgesTo(String elementId, EdgeType type) {
        return graph.getEdgesTo(elementId, type);
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    @Override
    public List<CodeElement> search(SearchQuery query) {
        return lucene.search(query);
    }

    @Override
    public long count(SearchQuery query) {
        return lucene.count(query);
    }

    // -------------------------------------------------------------------------
    // Subgraph
    // -------------------------------------------------------------------------

    @Override
    public SubGraph getSubGraph(String rootElementId, int depth, List<EdgeType> edgeTypes) {
        Set<String> visitedNodes = new LinkedHashSet<>();
        List<CodeEdge> collectedEdges = new ArrayList<>();

        // BFS
        Queue<String> queue = new ArrayDeque<>();
        queue.offer(rootElementId);
        visitedNodes.add(rootElementId);

        Map<String, Integer> depthMap = new HashMap<>();
        depthMap.put(rootElementId, 0);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentDepth = depthMap.getOrDefault(current, 0);
            if (currentDepth >= depth) continue;

            // Collect outgoing edges
            List<CodeEdge> outEdges = new ArrayList<>();
            if (edgeTypes == null || edgeTypes.isEmpty()) {
                outEdges = graph.getEdgesFrom(current);
            } else {
                for (EdgeType et : edgeTypes) {
                    outEdges.addAll(graph.getEdgesFrom(current, et));
                }
            }

            for (CodeEdge edge : outEdges) {
                collectedEdges.add(edge);
                String neighbor = edge.getToId();
                if (!visitedNodes.contains(neighbor)) {
                    visitedNodes.add(neighbor);
                    depthMap.put(neighbor, currentDepth + 1);
                    queue.offer(neighbor);
                }
            }
        }

        // Resolve nodes
        List<CodeElement> nodes = new ArrayList<>();
        for (String id : visitedNodes) {
            getElementById(id).ifPresent(nodes::add);
        }

        return new SubGraph(nodes, collectedEdges);
    }

    // -------------------------------------------------------------------------
    // Full repository indexing
    // -------------------------------------------------------------------------

    /**
     * Indexes an entire repository by walking its directory tree, parsing each
     * file with the default ParserRegistry, and saving all discovered elements
     * and edges.
     */
    public void indexRepository(RepoConfig repoConfig) {
        indexRepository(repoConfig, ParserRegistry.withDefaults());
    }

    /**
     * Indexes an entire repository using the supplied ParserRegistry.
     */
    public void indexRepository(RepoConfig repoConfig, ParserRegistry parserRegistry) {
        String repoId = repoConfig.getId();
        Path root = Path.of(repoConfig.getPath()).toAbsolutePath().normalize();

        if (!Files.isDirectory(root)) {
            log.error("Repo '{}' path does not exist or is not a directory: {}", repoId, root);
            return;
        }

        // Create/update Repository record
        Repository repo = getRepository(repoId).orElseGet(Repository::new);
        repo.setId(repoId);
        repo.setName(repoConfig.getName() != null ? repoConfig.getName() : repoId);
        repo.setRootPath(repoConfig.getPath());
        if (repoConfig.getLanguages() != null && !repoConfig.getLanguages().isEmpty()) {
            repo.setLanguages(repoConfig.getLanguages());
        }
        repo.setStatus(Repository.IndexingStatus.INDEXING);
        saveRepository(repo);

        log.info("Indexing repo '{}' at {}", repoId, root);

        AtomicInteger fileCount = new AtomicInteger(0);
        AtomicInteger elementCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        try {
            List<Path> filesToParse = new ArrayList<>();

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

            CountDownLatch latch = new CountDownLatch(filesToParse.size());
            for (Path file : filesToParse) {
                executor.submit(() -> {
                    try {
                        ParseResult result = parserRegistry.parse(file, repoId, root);
                        fileCount.incrementAndGet();
                        if (result.isSuccess()) {
                            if (!result.getElements().isEmpty()) {
                                // Save directly — don't use saveElements() which
                                // submits to the same executor and causes deadlock
                                graph.saveElements(result.getElements());
                                for (CodeElement el : result.getElements()) {
                                    lucene.indexElement(el);
                                }
                                elementCount.addAndGet(result.getElements().size());
                            }
                            if (!result.getEdges().isEmpty()) {
                                graph.saveEdges(result.getEdges());
                            }
                        } else {
                            errorCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        log.error("Error parsing {}: {}", file, e.getMessage(), e);
                        errorCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();

            // Commit Lucene after bulk indexing
            try { lucene.commit(); } catch (IOException e) {
                log.warn("Error committing Lucene after indexing: {}", e.getMessage());
            }

            repo.setStatus(Repository.IndexingStatus.READY);
            repo.setLastIndexed(Instant.now());
            repo.setFileCount(fileCount.get());
            repo.setElementCount(elementCount.get());
            saveRepository(repo);

            log.info("Repo '{}' indexed: {} files, {} elements, {} parse errors",
                    repoId, fileCount.get(), elementCount.get(), errorCount.get());

        } catch (IOException e) {
            log.error("IO error walking repo '{}': {}", repoId, e.getMessage(), e);
            repo.setStatus(Repository.IndexingStatus.ERROR);
            saveRepository(repo);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            repo.setStatus(Repository.IndexingStatus.ERROR);
            saveRepository(repo);
        }
    }

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

    private static boolean matchesGlob(String path, String pattern) {
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
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try {
            lucene.commit();
        } catch (IOException e) {
            log.warn("Error committing Lucene on close: {}", e.getMessage());
        }
        lucene.close();
        graph.close();
        metadata.close();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private int getThreadCount() {
        if (executor instanceof ThreadPoolExecutor tpe) {
            return tpe.getCorePoolSize();
        }
        return 4;
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }
}
