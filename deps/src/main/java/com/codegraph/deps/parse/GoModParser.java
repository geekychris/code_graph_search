package com.codegraph.deps.parse;

import com.codegraph.deps.model.Coordinate;
import com.codegraph.deps.model.Dependency;
import com.codegraph.deps.model.Ecosystem;
import com.codegraph.deps.model.ModuleManifest;

import java.util.*;

/**
 * Reads {@code go.mod} (module, go version, require, replace) and {@code go.work} (use).
 * A {@code replace X => ../dir} is recorded as the dependency's local path, which is how
 * sibling-checkout builds such as cloud-stack point at their libraries.
 */
final class GoModParser {

    private GoModParser() {}

    static ModuleManifest parseGoMod(String relPath, String text) {
        String module = null, goVersion = null;
        List<String[]> requires = new ArrayList<>();          // path, version, scope
        Map<String, String[]> replaces = new HashMap<>();      // path -> [newPath, version]
        String block = null;
        for (String raw : text.split("\n")) {
            String comment = raw.contains("//") ? raw.substring(raw.indexOf("//") + 2).trim() : "";
            String line = raw.contains("//") ? raw.substring(0, raw.indexOf("//")).trim() : raw.trim();
            if (line.isEmpty()) continue;
            if (block != null) {
                if (line.equals(")")) { block = null; continue; }
                directive(block, line, comment, requires, replaces);
                continue;
            }
            String[] parts = line.split("\\s+", 2);
            String verb = parts[0];
            String rest = parts.length > 1 ? parts[1].trim() : "";
            switch (verb) {
                case "module" -> module = unquote(rest);
                case "go" -> goVersion = rest;
                case "require", "replace" -> {
                    if (rest.equals("(")) block = verb; else directive(verb, rest, comment, requires, replaces);
                }
                default -> { if (rest.equals("(")) block = verb; }   // exclude, retract, tool...
            }
        }
        String dir = relPath.contains("/") ? relPath.substring(0, relPath.lastIndexOf('/')) : "";
        List<Dependency> deps = new ArrayList<>();
        for (String[] r : requires) {
            String[] rep = replaces.get(r[0]);
            String local = null;
            String path = r[0], version = r[1];
            if (rep != null) {
                if (rep[0].startsWith(".") || rep[0].startsWith("/")) local = MavenParser.normalize(dir, rep[0]);
                else { path = rep[0]; if (rep[1] != null) version = rep[1]; }
            }
            deps.add(new Dependency(new Coordinate(Ecosystem.GO, r[0]), r[1], version, r[2], false, local));
            if (!path.equals(r[0])) {
                // replaced by another module: depend on that one too
                deps.add(new Dependency(new Coordinate(Ecosystem.GO, path), version, version, r[2], false, null));
            }
        }
        // replace entries for modules not required directly still say "this repo uses that sibling"
        for (Map.Entry<String, String[]> e : replaces.entrySet()) {
            boolean required = requires.stream().anyMatch(r -> r[0].equals(e.getKey()));
            String[] rep = e.getValue();
            if (!required && (rep[0].startsWith(".") || rep[0].startsWith("/"))) {
                deps.add(new Dependency(new Coordinate(Ecosystem.GO, e.getKey()), null, null, "replace", false,
                        MavenParser.normalize(dir, rep[0])));
            }
        }
        Map<String, String> extra = new LinkedHashMap<>();
        if (goVersion != null) extra.put("go", goVersion);
        Coordinate produces = module == null ? null : new Coordinate(Ecosystem.GO, module);
        return new ModuleManifest(relPath, "go", produces, null, null, deps, List.of(), extra);
    }

    private static void directive(String verb, String line, String comment,
                                  List<String[]> requires, Map<String, String[]> replaces) {
        if (verb.equals("require")) {
            String[] p = line.split("\\s+");
            if (p.length >= 2) {
                requires.add(new String[]{unquote(p[0]), p[1], comment.contains("indirect") ? "indirect" : "compile"});
            }
        } else if (verb.equals("replace")) {
            int arrow = line.indexOf("=>");
            if (arrow < 0) return;
            String[] left = line.substring(0, arrow).trim().split("\\s+");
            String[] right = line.substring(arrow + 2).trim().split("\\s+");
            replaces.put(unquote(left[0]), new String[]{unquote(right[0]), right.length > 1 ? right[1] : null});
        }
    }

    /** {@code go.work}: the {@code use} directories become submodules. */
    static List<String> parseGoWorkUses(String relPath, String text) {
        String dir = relPath.contains("/") ? relPath.substring(0, relPath.lastIndexOf('/')) : "";
        List<String> uses = new ArrayList<>();
        boolean block = false;
        for (String raw : text.split("\n")) {
            String line = raw.contains("//") ? raw.substring(0, raw.indexOf("//")).trim() : raw.trim();
            if (block) {
                if (line.equals(")")) block = false;
                else if (!line.isEmpty()) uses.add(MavenParser.normalize(dir, unquote(line)));
            } else if (line.startsWith("use")) {
                String rest = line.substring(3).trim();
                if (rest.equals("(")) block = true;
                else if (!rest.isEmpty()) uses.add(MavenParser.normalize(dir, unquote(rest)));
            }
        }
        return uses;
    }

    private static String unquote(String s) {
        return s.replace("\"", "").replace("`", "").trim();
    }
}
