package com.codegraph.tests;

import com.codegraph.core.config.AppConfig;
import com.codegraph.core.config.RepoConfig;
import com.codegraph.core.model.Language;
import com.codegraph.indexer.IndexerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Comparator;

/**
 * Base class for integration tests. Sets up a shared IndexerService backed by
 * a temporary directory and indexes the code-graph-search project itself as test data.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaseIntegrationTest {

    protected static final Logger log = LoggerFactory.getLogger(BaseIntegrationTest.class);

    /** The root of the code-graph-search project itself - used as test corpus. */
    protected static final String PROJECT_ROOT = resolveProjectRoot();

    protected IndexerService graphService;
    protected ObjectMapper mapper;
    protected Path tempDataDir;

    private static String resolveProjectRoot() {
        // Walk up from the test class location to find the project root
        Path current = Paths.get("").toAbsolutePath();
        // When running from Maven, cwd is typically the module root
        while (current != null && !Files.exists(current.resolve("pom.xml"))) {
            current = current.getParent();
        }
        // Go to the top-level project root (it has a modules section)
        if (current != null && current.getParent() != null &&
                Files.exists(current.getParent().resolve("pom.xml"))) {
            current = current.getParent();
        }
        return current != null ? current.toString() : ".";
    }

    @BeforeAll
    void setUpIndexer() throws Exception {
        tempDataDir = Files.createTempDirectory("codegraph-test-");
        log.info("Test data dir: {}", tempDataDir);
        log.info("Project root for indexing: {}", PROJECT_ROOT);

        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        AppConfig.IndexerConfig indexerConfig = new AppConfig.IndexerConfig();
        indexerConfig.setDataDir(tempDataDir.toString());
        indexerConfig.setIndexingThreads(2);
        graphService = new IndexerService(indexerConfig);

        // Index this project as the test corpus
        indexProjectAsTestCorpus();
    }

    private void indexProjectAsTestCorpus() throws Exception {
        log.info("Starting indexing of project: {}", PROJECT_ROOT);
        var repoConfig = new RepoConfig();
        repoConfig.setId("code-graph-search");
        repoConfig.setName("Code Graph Search");
        repoConfig.setPath(PROJECT_ROOT);
        repoConfig.setLanguages(List.of(Language.JAVA, Language.TYPESCRIPT, Language.MARKDOWN, Language.YAML));

        // Use the IndexerService's built-in full-index capability
        graphService.indexRepository(repoConfig);
        log.info("Indexing complete. Elements: {}", graphService.count(new com.codegraph.core.model.SearchQuery()));
    }

    @AfterAll
    void tearDown() throws IOException {
        if (graphService != null) graphService.close();
        if (tempDataDir != null) {
            // Clean up temp dir
            try (var walk = Files.walk(tempDataDir)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        }
    }
}
