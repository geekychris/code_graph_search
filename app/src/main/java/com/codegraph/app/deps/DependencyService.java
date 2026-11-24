package com.codegraph.app.deps;

import com.codegraph.core.model.Repository;
import com.codegraph.deps.graph.DependencyGraph;
import com.codegraph.deps.model.RepoManifests;
import com.codegraph.deps.parse.ManifestScanner;
import com.codegraph.indexer.IndexerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/**
 * Keeps the portfolio dependency graph current.
 *
 * <p>After each index of a repo, its build manifests are scanned and stored (as the
 * {@code manifests} repo fact), the {@link DependencyGraph} is rebuilt from every
 * repo's stored manifests, and cross-repo references are resolved for that repo and
 * for the repos that depend on it (a library indexed after its consumers makes their
 * references resolvable).
 */
public class DependencyService implements Supplier<DependencyGraph> {

    private static final Logger log = LoggerFactory.getLogger(DependencyService.class);
    public static final String FACT_KIND = "manifests";

    private final IndexerService indexer;
    private final CrossRepoResolver resolver;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, RepoManifests> manifests = new HashMap<>();
    private volatile DependencyGraph graph = DependencyGraph.build(Map.of());

    public DependencyService(IndexerService indexer) {
        this.indexer = indexer;
        this.resolver = new CrossRepoResolver(indexer);
    }

    /** Loads stored manifests, scans indexed repos that have none, and builds the graph. */
    public synchronized void load() {
        for (var e : indexer.listRepoFacts(FACT_KIND).entrySet()) {
            try {
                manifests.put(e.getKey(), mapper.readValue(e.getValue(), RepoManifests.class));
            } catch (Exception ex) {
                log.warn("Stored manifests for {} are unreadable; rescanning: {}", e.getKey(), ex.getMessage());
            }
        }
        for (Repository r : indexer.listRepositories()) {
            if (!manifests.containsKey(r.getId()) && r.getRootPath() != null && Files.isDirectory(Path.of(r.getRootPath()))) {
                scanAndStore(r);
            }
        }
        rebuild();
        log.info("Dependency graph: {} repos with manifests, {} internal edges",
                graph.repos().size(), graph.edges(x -> true).size());
    }

    /** Index listener: rescan this repo, rebuild, and resolve references around it. */
    public void onRepoIndexed(String repoId) {
        Repository repo = indexer.getRepository(repoId).orElse(null);
        if (repo == null || repo.getRootPath() == null) return;
        DependencyGraph g;
        synchronized (this) {
            scanAndStore(repo);
            rebuild();
            g = graph;
        }
        resolveAround(repoId, g);
    }

    @Override
    public DependencyGraph get() {
        return graph;
    }

    /** Re-runs cross-repo resolution for every repo (after a full sync, or on demand). */
    public int resolveAll() {
        DependencyGraph g = graph;
        int total = 0;
        for (String repo : g.repos()) total += resolveOne(repo, g);
        return total;
    }

    private void resolveAround(String repoId, DependencyGraph g) {
        resolveOne(repoId, g);
        for (String dependent : g.closure(repoId, -1, true, true, r -> true).keySet()) {
            resolveOne(dependent, g);
        }
    }

    private int resolveOne(String repoId, DependencyGraph g) {
        Set<String> candidates = g.closure(repoId, -1, false, true, r -> true).keySet();
        try {
            return resolver.resolve(repoId, candidates);
        } catch (Exception e) {
            log.warn("Cross-repo resolution failed for {}: {}", repoId, e.getMessage());
            return 0;
        }
    }

    private void scanAndStore(Repository repo) {
        RepoManifests m = ManifestScanner.scan(Path.of(repo.getRootPath()));
        manifests.put(repo.getId(), m);
        try {
            indexer.saveRepoFact(repo.getId(), FACT_KIND, repo.getIndexedCommit(), mapper.writeValueAsString(m));
        } catch (Exception e) {
            log.warn("Cannot store manifests for {}: {}", repo.getId(), e.getMessage());
        }
        if (!m.warnings().isEmpty()) {
            log.info("Repo {}: {} manifest warning(s), e.g. {}", repo.getId(), m.warnings().size(), m.warnings().get(0));
        }
    }

    private void rebuild() {
        // Drop repos that were removed from the registry
        Set<String> known = new HashSet<>();
        indexer.listRepositories().forEach(r -> known.add(r.getId()));
        manifests.keySet().retainAll(known);
        graph = DependencyGraph.build(manifests);
    }
}
