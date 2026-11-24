package com.codegraph.core.stats;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A repo's size at one commit: every tracked file classified by language and
 * kind (source, test, config, docs, build, ci, data, generated, asset, other)
 * with its line counts. {@code cells} is the language × kind matrix that every
 * total and rollup is summed from.
 *
 * @param revision    the scanner's rules version; a different one means re-count
 * @param cells       language → kind → counts
 * @param byDir       the repo's top-level directories (files at the root are "."), the largest 30
 * @param largest     the largest source and test files by lines, at most 15
 * @param excluded    files skipped as vendored or build output (node_modules, dist, vendor, …)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RepoStats(
        String repoId,
        String commit,
        String computedAt,
        int revision,
        Map<String, Map<String, Counts>> cells,
        Map<String, Counts> byDir,
        List<FileLines> largest,
        long excluded) {

    /** One file's size, for the largest-files list. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileLines(String path, String language, String kind, long lines, long code) {}

    /** The kinds a file can be, in display order. */
    public static final List<String> KINDS = List.of(
            "source", "test", "config", "build", "ci", "docs", "data", "generated", "asset", "other");

    /** Everything in the repo. */
    @JsonIgnore
    public Counts totals() {
        Counts c = new Counts();
        cells.values().forEach(m -> m.values().forEach(c::add));
        return c;
    }

    /** Counts per language, all kinds together. */
    @JsonIgnore
    public Map<String, Counts> byLanguage() {
        Map<String, Counts> out = new TreeMap<>();
        cells.forEach((lang, kinds) -> kinds.values().forEach(c -> out.computeIfAbsent(lang, k -> new Counts()).add(c)));
        return out;
    }

    /** Counts per kind, all languages together. */
    @JsonIgnore
    public Map<String, Counts> byKind() {
        Map<String, Counts> out = new TreeMap<>();
        cells.values().forEach(kinds -> kinds.forEach((kind, c) -> out.computeIfAbsent(kind, k -> new Counts()).add(c)));
        return out;
    }
}
