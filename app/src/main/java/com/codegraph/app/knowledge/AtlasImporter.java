package com.codegraph.app.knowledge;

import com.codegraph.core.knowledge.RepoCard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Reads a repo-atlas checkout: one dossier per repo ({@code repos/<name>.md}, YAML
 * front matter plus "## " sections) and the portfolio write-ups in {@code synthesis/}.
 * Read-only; nothing in the checkout is run.
 */
public final class AtlasImporter {

    private static final Logger log = LoggerFactory.getLogger(AtlasImporter.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private AtlasImporter() {}

    public static boolean looksLikeAtlas(Path dir) {
        return dir != null && Files.isDirectory(dir.resolve("repos")) && Files.isRegularFile(dir.resolve("AGENTS.md"));
    }

    /** Cards keyed by repo name. Dossiers that don't parse are skipped with a warning. */
    public static Map<String, RepoCard> readDossiers(Path atlasDir) {
        Map<String, RepoCard> out = new TreeMap<>();
        Path repos = atlasDir.resolve("repos");
        try (Stream<Path> files = Files.list(repos)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".md"))::iterator) {
                String file = f.getFileName().toString();
                if (file.startsWith("_")) continue;   // _TEMPLATE.md
                try {
                    RepoCard card = parseDossier(Files.readString(f));
                    if (card != null) out.put(card.repoId(), card);
                } catch (Exception e) {
                    log.warn("Skipping dossier {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Cannot read dossiers in {}: {}", repos, e.getMessage());
        }
        return out;
    }

    /** {@code synthesis/*.md} by file name without extension. */
    public static Map<String, String> readSynthesis(Path atlasDir) {
        Map<String, String> out = new TreeMap<>();
        Path dir = atlasDir.resolve("synthesis");
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".md")).forEach(p -> {
                try {
                    String name = p.getFileName().toString();
                    out.put(name.substring(0, name.length() - 3), Files.readString(p));
                } catch (IOException e) {
                    log.warn("Cannot read {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", dir, e.getMessage());
        }
        return out;
    }

    static RepoCard parseDossier(String text) throws IOException {
        if (!text.startsWith("---")) return null;
        int end = text.indexOf("\n---", 3);
        if (end < 0) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> fm = YAML.readValue(text.substring(3, end), Map.class);
        if (fm == null || fm.get("name") == null) return null;
        String body = text.substring(text.indexOf('\n', end + 1) + 1);
        return new RepoCard(
                str(fm.get("name")),
                str(fm.get("family")),
                str(fm.get("category")),
                str(fm.get("status")),
                integer(fm.get("maturity")),
                str(fm.get("purpose")),
                list(fm.get("languages")),
                list(fm.get("tech")),
                list(fm.get("tags")),
                list(fm.get("related")),
                str(fm.get("superseded_by")),
                str(fm.get("analyzed")),
                str(fm.get("analyzed_commit")),
                sections(body),
                "repo-atlas");
    }

    /** "## Heading" sections in order; text before the first heading is dropped. */
    static Map<String, String> sections(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        String current = null;
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (line.startsWith("## ")) {
                if (current != null) out.put(current, sb.toString().strip());
                current = line.substring(3).strip();
                sb.setLength(0);
            } else if (current != null) {
                sb.append(line).append('\n');
            }
        }
        if (current != null) out.put(current, sb.toString().strip());
        return out;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().strip();
        return s.isEmpty() ? null : s;
    }

    private static Integer integer(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return o == null ? null : Integer.parseInt(o.toString().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> list(Object o) {
        if (o == null) return List.of();
        if (o instanceof Collection<?> c) {
            List<String> out = new ArrayList<>();
            for (Object x : c) if (x != null && !x.toString().isBlank()) out.add(x.toString().strip());
            return out;
        }
        String s = o.toString().strip();
        return s.isEmpty() ? List.of() : List.of(s);
    }
}
