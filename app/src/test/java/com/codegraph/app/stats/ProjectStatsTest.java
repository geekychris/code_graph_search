package com.codegraph.app.stats;

import com.codegraph.core.stats.Counts;
import com.codegraph.core.stats.RepoStats;
import com.codegraph.core.stats.StatsRollup;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Project stats: classification, line counting, scanning a git checkout, and rollups. */
class ProjectStatsTest {

    @Test
    void classifiesLanguagesAndKinds() {
        Object[][] cases = {
                {"src/main/java/a/Foo.java", "Java", "source"},
                {"src/test/java/a/FooTest.java", "Java", "test"},
                {"pkg/server_test.go", "Go", "test"},
                {"web/src/app.spec.ts", "TypeScript", "test"},
                {"tests/test_api.py", "Python", "test"},
                {"README.md", "Markdown", "docs"},
                {"docs/guide.adoc", "AsciiDoc", "docs"},
                {"LICENSE", "Text", "docs"},
                {"pom.xml", "XML", "build"},
                {"web/package.json", "JSON", "build"},
                {"Dockerfile.dev", "Dockerfile", "build"},
                {"Makefile", "Makefile", "build"},
                {".github/workflows/ci.yml", "YAML", "ci"},
                {"Jenkinsfile", "Groovy", "ci"},
                {"deploy/k8s/app.yaml", "YAML", "config"},
                {"src/main/resources/application.properties", "Properties", "config"},
                {".gitignore", "Ignore list", "config"},
                {"web/package-lock.json", "JSON", "generated"},
                {"static/app.min.js", "JavaScript", "generated"},
                {"data/scores.csv", "CSV", "data"},
                {"src/boot.s", "Assembly", "source"},
                {"src/game.e", "AmigaE", "source"},
                {"logo.svg", "SVG", "asset"},
                {"scripts/run", null, "other"},
        };
        for (Object[] c : cases) {
            String path = (String) c[0];
            FileClassifier.Lang lang = FileClassifier.language(path);
            assertThat(lang == null ? null : lang.name()).as("language of " + path).isEqualTo(c[1]);
            assertThat(FileClassifier.kind(path, lang == null ? null : lang.name(), FileClassifier.binaryByName(path)))
                    .as("kind of " + path).isEqualTo(c[2]);
        }
        assertThat(FileClassifier.binaryByName("art/title.iff")).isTrue();
        assertThat(FileClassifier.kind("art/title.png", null, true)).isEqualTo("asset");
    }

    @Test
    void countsCodeCommentsAndBlanks() {
        String java = """
                package a;

                /**
                 * A thing.
                 */
                public class Foo { // trailing comment is code
                    // a comment
                    int x = 1; /* inline */

                    /* one-line block */
                }
                """;
        LineCounter.Lines l = LineCounter.count(java, FileClassifier.Comments.C);
        assertThat(l).isEqualTo(new LineCounter.Lines(4, 5, 2));

        String py = "#!/usr/bin/env python3\n# comment\n\ndef f():\n    return 1  # code\n";
        assertThat(LineCounter.count(py, FileClassifier.Comments.HASH)).isEqualTo(new LineCounter.Lines(2, 2, 1));

        String asm = "* header comment\n\tmove.l d0,d1 ; trailing\n; full line\n\n\trts\n";
        assertThat(LineCounter.count(asm, FileClassifier.Comments.ASM)).isEqualTo(new LineCounter.Lines(2, 2, 1));

        String html = "<div>\n<!--\n  note\n-->\n</div>\n";
        assertThat(LineCounter.count(html, FileClassifier.Comments.XML)).isEqualTo(new LineCounter.Lines(2, 3, 0));

        assertThat(LineCounter.count("10 PRINT \"HI\"\nREM a comment\n' another\n20 GOTO 10\n", FileClassifier.Comments.BASIC))
                .isEqualTo(new LineCounter.Lines(2, 2, 0));
        assertThat(LineCounter.count("no newline at end", FileClassifier.Comments.NONE)).isEqualTo(new LineCounter.Lines(1, 0, 0));
        assertThat(LineCounter.count("", FileClassifier.Comments.NONE)).isEqualTo(new LineCounter.Lines(0, 0, 0));
        assertThat(LineCounter.count("a\r\n\r\nb\r\n", FileClassifier.Comments.NONE)).isEqualTo(new LineCounter.Lines(2, 0, 1));
    }

    @Test
    void scansTrackedFilesOfAGitCheckout() throws Exception {
        Path dir = Files.createTempDirectory("stats-repo");
        write(dir, "src/main/java/a/Foo.java", "package a;\n\n// hi\nclass Foo {}\n");
        write(dir, "src/test/java/a/FooTest.java", "package a;\nclass FooTest {}\n");
        write(dir, "README.md", "# Foo\n\nText.\n");
        write(dir, "pom.xml", "<project>\n</project>\n");
        write(dir, "node_modules/lib/index.js", "module.exports = 1;\n");
        write(dir, "untracked.txt", "not added to git\n");
        Files.write(dir.resolve("logo.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 1});
        Files.write(dir.resolve("data.bin.txt"), new byte[]{'a', 0, 'b'}); // NUL byte: binary whatever the name
        git(dir, "init", "-q");
        git(dir, "add", "src", "README.md", "pom.xml", "node_modules", "logo.png", "data.bin.txt");
        git(dir, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "init");

        RepoStats s = StatsScanner.scan(dir, "demo", "abc123");
        Map<String, Counts> kinds = s.byKind();
        assertThat(kinds.get("source").files).isEqualTo(1);
        assertThat(kinds.get("source").code).isEqualTo(2);
        assertThat(kinds.get("source").comment).isEqualTo(1);
        assertThat(kinds.get("test").files).isEqualTo(1);
        assertThat(kinds.get("docs").lines).isEqualTo(3);
        assertThat(kinds.get("build").files).isEqualTo(1);
        assertThat(kinds.get("asset").files).isEqualTo(2);
        assertThat(kinds.get("asset").lines).isZero();
        assertThat(s.byLanguage()).containsKeys("Java", "Markdown", "XML", "Binary").doesNotContainKey("JavaScript");
        assertThat(s.excluded()).isEqualTo(1);           // node_modules
        assertThat(s.totals().files).isEqualTo(6);       // untracked.txt isn't counted
        assertThat(s.byDir()).containsKeys("src", ".");
        assertThat(s.largest()).extracting(RepoStats.FileLines::path).first().isEqualTo("src/main/java/a/Foo.java");
        assertThat(s.revision()).isEqualTo(StatsScanner.REVISION);

        // stored as JSON and read back
        ObjectMapper m = new ObjectMapper();
        RepoStats back = m.readValue(m.writeValueAsString(s), RepoStats.class);
        assertThat(back.totals().code).isEqualTo(s.totals().code);
        assertThat(back.largest()).hasSize(s.largest().size());
    }

    @Test
    void rollsUpByAnyDimensionWithFiltersAndBreakdowns() {
        RepoStats a = stats("alpha", Map.of(
                "Java", Map.of("source", new Counts(10, 1000, 800, 100, 100, 0), "test", new Counts(5, 400, 350, 10, 40, 0)),
                "Markdown", Map.of("docs", new Counts(2, 100, 90, 0, 10, 0))));
        RepoStats b = stats("beta", Map.of(
                "Go", Map.of("source", new Counts(4, 500, 450, 20, 30, 0)),
                "Java", Map.of("source", new Counts(1, 50, 40, 5, 5, 0))));
        RepoStats hidden = stats("secret", Map.of("Java", Map.of("source", new Counts(1, 9999, 9999, 0, 0, 0))));
        Map<String, StatsRollup.RepoMeta> meta = Map.of(
                "alpha", new StatsRollup.RepoMeta("tools", "public", List.of("cli", "jvm")),
                "beta", new StatsRollup.RepoMeta("tools", "private", List.of("go")),
                "secret", new StatsRollup.RepoMeta("other", "private", List.of()));
        List<RepoStats> visible = List.of(a, b); // the service filters by visibility before rolling up

        StatsRollup.Result byFamily = StatsRollup.rollup(visible, meta::get, StatsRollup.Query.of("family"));
        assertThat(byFamily.repos()).isEqualTo(2);
        assertThat(byFamily.totals().code).isEqualTo(800 + 350 + 90 + 450 + 40);
        assertThat(byFamily.groups()).singleElement().satisfies(g -> {
            assertThat(g.key()).isEqualTo("tools");
            assertThat(g.repos()).isEqualTo(2);
        });

        // Java code per repo, test vs source
        StatsRollup.Query q = new StatsRollup.Query("repo", "kind", "code", Set.of(), Set.of(), Set.of("Java"),
                Set.of(), Set.of(), Set.of(), 12);
        StatsRollup.Result javaByRepo = StatsRollup.rollup(visible, meta::get, q);
        assertThat(javaByRepo.groups()).extracting(StatsRollup.Group::key).containsExactly("alpha", "beta");
        assertThat(javaByRepo.groups().get(0).breakdown()).extracting(StatsRollup.Part::key).containsExactly("source", "test");
        assertThat(javaByRepo.totals().code).isEqualTo(800 + 350 + 40);

        // by language, sorted by code; the totals agree with the family rollup
        StatsRollup.Result byLang = StatsRollup.rollup(visible, meta::get, StatsRollup.Query.of("language"));
        assertThat(byLang.groups()).extracting(StatsRollup.Group::key).containsExactly("Java", "Go", "Markdown");
        assertThat(byLang.totals().code).isEqualTo(byFamily.totals().code);

        // tags: a repo in several groups, but the totals count it once
        StatsRollup.Result byTag = StatsRollup.rollup(visible, meta::get, StatsRollup.Query.of("tag"));
        assertThat(byTag.groups()).extracting(StatsRollup.Group::key).contains("cli", "jvm", "go");
        assertThat(byTag.totals().code).isEqualTo(byFamily.totals().code);

        // visibility filter, kinds filter
        StatsRollup.Query priv = new StatsRollup.Query("repo", "none", "files", Set.of(), Set.of(), Set.of(),
                Set.of("source"), Set.of("private"), Set.of(), 12);
        assertThat(StatsRollup.rollup(visible, meta::get, priv).groups()).extracting(StatsRollup.Group::key).containsExactly("beta");

        // a breakdown limit folds the rest into (other)
        StatsRollup.Query tight = new StatsRollup.Query("none", "language", "code", Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), 1);
        assertThat(StatsRollup.rollup(visible, meta::get, tight).groups().get(0).breakdown())
                .extracting(StatsRollup.Part::key).containsExactly("Java", "(other)");

        // the service passes only visible repos; a rollup over them never includes the hidden one
        assertThat(StatsRollup.rollup(visible, meta::get, StatsRollup.Query.of("repo")).groups())
                .extracting(StatsRollup.Group::key).doesNotContain("secret");
        assertThat(hidden.totals().code).isEqualTo(9999);
    }

    private static RepoStats stats(String id, Map<String, Map<String, Counts>> cells) {
        return new RepoStats(id, "c", "now", StatsScanner.REVISION, cells, Map.of(), List.of(), 0);
    }

    private static void write(Path dir, String rel, String content) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static void git(Path dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 3];
        cmd[0] = "git";
        cmd[1] = "-C";
        cmd[2] = dir.toString();
        System.arraycopy(args, 0, cmd, 3, args.length);
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        assertThat(p.waitFor()).as(String.join(" ", args)).isZero();
    }
}
