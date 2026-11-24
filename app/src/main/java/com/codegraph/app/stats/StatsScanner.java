package com.codegraph.app.stats;

import com.codegraph.core.stats.Counts;
import com.codegraph.core.stats.RepoStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Counts a repo checkout: every file git tracks, classified by language and
 * kind (FileClassifier) and counted by line (LineCounter). Vendored and
 * build-output directories are skipped and only counted as excluded.
 */
public final class StatsScanner {

    private static final Logger log = LoggerFactory.getLogger(StatsScanner.class);

    /** Bump when classification or counting changes: stored stats with another revision are recounted. */
    public static final int REVISION = 2;

    /** Files bigger than this are counted by size only (no lines). */
    static final long MAX_TEXT_BYTES = 4L << 20;

    /** Directories whose contents are someone else's code or build output. */
    static final Set<String> EXCLUDED_DIRS = Set.of("node_modules", "bower_components", "vendor", "third_party",
            "thirdparty", ".venv", "venv", "site-packages", "__pycache__", ".tox", "dist", "target", ".idea",
            ".gradle", "pods", ".yarn", ".pnpm-store", "carthage", ".next", ".nuxt", ".terraform");

    private StatsScanner() {}

    /** Counts the checkout at dir, as of commit. */
    public static RepoStats scan(Path dir, String repoId, String commit) throws IOException {
        List<String> files = trackedFiles(dir);
        Map<String, Map<String, Counts>> cells = new TreeMap<>();
        Map<String, Counts> byDir = new HashMap<>();
        List<RepoStats.FileLines> sized = new ArrayList<>();
        long excluded = 0;

        for (String rel : files) {
            if (isExcluded(rel)) { excluded++; continue; }
            Path p = dir.resolve(rel);
            if (!Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            long size = Files.size(p);
            FileClassifier.Lang lang = FileClassifier.language(rel);
            boolean binary = FileClassifier.binaryByName(rel);
            Counts c = new Counts();
            c.files = 1;
            c.bytes = size;
            if (!binary && size <= MAX_TEXT_BYTES && !FileClassifier.sizeOnly(rel)) {
                byte[] bytes = Files.readAllBytes(p);
                if (hasNul(bytes)) {
                    binary = true;
                } else {
                    // ISO-8859-1 maps every byte to one char: line breaks and ASCII
                    // comment markers survive whatever the real encoding is
                    LineCounter.Lines l = LineCounter.count(new String(bytes, StandardCharsets.ISO_8859_1),
                            lang == null ? FileClassifier.Comments.NONE : lang.comments());
                    c.lines = l.total();
                    c.code = l.code();
                    c.comment = l.comment();
                    c.blank = l.blank();
                }
            }
            String language = binary ? "Binary" : lang == null ? "Other" : lang.name();
            String kind = FileClassifier.kind(rel, lang == null ? null : lang.name(), binary, c.lines);
            cells.computeIfAbsent(language, k -> new TreeMap<>()).computeIfAbsent(kind, k -> new Counts()).add(c);
            int slash = rel.indexOf('/');
            byDir.computeIfAbsent(slash < 0 ? "." : rel.substring(0, slash), k -> new Counts()).add(c);
            if (("source".equals(kind) || "test".equals(kind)) && c.lines > 0) {
                sized.add(new RepoStats.FileLines(rel, language, kind, c.lines, c.code));
            }
        }

        sized.sort(Comparator.comparingLong(RepoStats.FileLines::lines).reversed());
        Map<String, Counts> topDirs = new LinkedHashMap<>();
        byDir.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Counts> e) -> e.getValue().lines).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(30).forEach(e -> topDirs.put(e.getKey(), e.getValue()));
        return new RepoStats(repoId, commit, Instant.now().toString(), REVISION, cells, topDirs,
                List.copyOf(sized.subList(0, Math.min(15, sized.size()))), excluded);
    }

    static boolean isExcluded(String rel) {
        String[] parts = rel.toLowerCase(Locale.ROOT).split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (EXCLUDED_DIRS.contains(parts[i])) return true;
        }
        return false;
    }

    private static boolean hasNul(byte[] b) {
        for (int i = 0, n = Math.min(b.length, 8000); i < n; i++) {
            if (b[i] == 0) return true;
        }
        return false;
    }

    /** The files git tracks at the checkout's HEAD; every file under dir when it isn't a git checkout. */
    static List<String> trackedFiles(Path dir) throws IOException {
        if (Files.exists(dir.resolve(".git"))) {
            Process proc = new ProcessBuilder("git", "-C", dir.toString(), "-c", "core.quotepath=off", "ls-files", "-z")
                    .redirectErrorStream(false).start();
            byte[] out;
            try (InputStream in = proc.getInputStream()) {
                out = in.readAllBytes();
            }
            try {
                if (proc.waitFor(2, TimeUnit.MINUTES) && proc.exitValue() == 0) {
                    List<String> files = new ArrayList<>();
                    int start = 0;
                    for (int i = 0; i < out.length; i++) {
                        if (out[i] == 0) {
                            if (i > start) files.add(new String(out, start, i - start, StandardCharsets.UTF_8));
                            start = i + 1;
                        }
                    }
                    return files;
                }
                log.warn("git ls-files failed in {}; walking the directory instead", dir);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted listing " + dir, e);
            }
        }
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .filter(r -> !r.startsWith(".git/"))
                    .sorted().toList();
        }
    }
}
