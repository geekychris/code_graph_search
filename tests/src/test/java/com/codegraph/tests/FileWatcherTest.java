package com.codegraph.tests;

import com.codegraph.core.config.AppConfig;
import com.codegraph.core.config.RepoConfig;
import com.codegraph.core.model.ElementType;
import com.codegraph.core.model.Language;
import com.codegraph.core.model.SearchQuery;
import com.codegraph.indexer.IndexerService;
import com.codegraph.parser.ParserRegistry;
import com.codegraph.parser.java.JavaSourceParser;
import com.codegraph.parser.markdown.MarkdownFileParser;
import com.codegraph.watcher.FileWatcher;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests file system watching and incremental re-indexing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileWatcherTest {

    private Path tempRepoDir;
    private Path tempDataDir;
    private IndexerService service;
    private FileWatcher watcher;
    private ParserRegistry parserRegistry;
    private AppConfig config;

    @BeforeAll
    void setUp() throws Exception {
        tempRepoDir = Files.createTempDirectory("codegraph-watch-repo-");
        tempDataDir = Files.createTempDirectory("codegraph-watch-data-");

        config = new AppConfig();
        AppConfig.IndexerConfig indexerConfig = new AppConfig.IndexerConfig();
        indexerConfig.setDataDir(tempDataDir.toString());
        indexerConfig.setWatchDebounceMs(200); // short debounce for tests
        config.setIndexer(indexerConfig);

        service = new IndexerService(indexerConfig);

        parserRegistry = new ParserRegistry();
        parserRegistry.register(new JavaSourceParser());
        parserRegistry.register(new MarkdownFileParser());

        // Create a test repo with an initial Java file
        writeJavaFile("com/example/Hello.java", """
                package com.example;

                /** Hello class */
                public class Hello {
                    private String message;

                    public String getMessage() {
                        return message;
                    }
                }
                """);

        // Index the initial state
        var repoConfig = new RepoConfig();
        repoConfig.setId("watch-test-repo");
        repoConfig.setName("Watch Test Repo");
        repoConfig.setPath(tempRepoDir.toString());
        repoConfig.setLanguages(List.of(Language.JAVA, Language.MARKDOWN));
        config.setRepos(List.of(repoConfig));
        service.indexRepository(repoConfig, parserRegistry);

        // Start file watcher
        watcher = new FileWatcher(config, service, parserRegistry);
        watcher.startWatching();
        Thread.sleep(200); // give watcher time to register
    }

    private void writeJavaFile(String relativePath, String content) throws IOException {
        Path file = tempRepoDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @AfterAll
    void tearDown() throws IOException {
        watcher.stop();
        service.close();
        cleanDir(tempRepoDir);
        cleanDir(tempDataDir);
    }

    private void cleanDir(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    @Test
    @Order(1)
    void initialIndexingWorked() {
        var q = new SearchQuery("Hello");
        q.setElementTypes(List.of(ElementType.CLASS));
        var results = service.search(q);
        assertThat(results).isNotEmpty();
        assertThat(results).anyMatch(e -> e.getName().equals("Hello"));
    }

    @Test
    @Order(2)
    void newFileTriggerReindex() throws Exception {
        // Write a new file
        writeJavaFile("com/example/World.java", """
                package com.example;

                public class World {
                    public void greet() {}
                }
                """);

        // Wait for watcher debounce + processing
        TimeUnit.MILLISECONDS.sleep(800);

        var q = new SearchQuery("World");
        q.setElementTypes(List.of(ElementType.CLASS));
        var results = service.search(q);
        assertThat(results).anyMatch(e -> e.getName().equals("World"));
    }

    @Test
    @Order(3)
    void modifiedFileReindex() throws Exception {
        // Modify Hello.java to add a new method
        writeJavaFile("com/example/Hello.java", """
                package com.example;

                public class Hello {
                    private String message;

                    public String getMessage() { return message; }

                    public void setMessage(String m) { this.message = m; }

                    public String toUpperCase() { return message.toUpperCase(); }
                }
                """);

        TimeUnit.MILLISECONDS.sleep(800);

        var q = new SearchQuery("toUpperCase");
        q.setElementTypes(List.of(ElementType.METHOD));
        var results = service.search(q);
        assertThat(results).isNotEmpty();
        assertThat(results).anyMatch(e -> e.getName().equals("toUpperCase"));
    }

    @Test
    @Order(4)
    void deletedFileRemovesElements() throws Exception {
        // Verify World class exists first
        var q = new SearchQuery("World");
        q.setElementTypes(List.of(ElementType.CLASS));
        assertThat(service.search(q)).isNotEmpty();

        // Delete the file
        Files.deleteIfExists(tempRepoDir.resolve("com/example/World.java"));
        TimeUnit.MILLISECONDS.sleep(800);

        // World class should be removed
        var results = service.search(q);
        assertThat(results).noneMatch(e -> e.getName().equals("World"));
    }

    @Test
    @Order(5)
    void watcherIsRunning() {
        assertThat(watcher.isWatching()).isTrue();
    }
}
