package com.codegraph.deps.parse;

import com.codegraph.deps.model.Coordinate;
import com.codegraph.deps.model.Dependency;
import com.codegraph.deps.model.Ecosystem;
import com.codegraph.deps.model.ModuleManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.*;

/**
 * Reads {@code package.json}: name, version, the four dependency groups, workspaces, and
 * {@code file:}/{@code link:}/{@code workspace:} local references. {@code pnpm-workspace.yaml}
 * package globs are read by {@link #pnpmWorkspaces}.
 */
final class NpmParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String[][] GROUPS = {
        {"dependencies", "compile"}, {"devDependencies", "dev"},
        {"peerDependencies", "peer"}, {"optionalDependencies", "optional"}
    };

    private NpmParser() {}

    /** Returns the manifest and, separately, the raw workspace globs (expanded by the scanner). */
    static ModuleManifest parse(String relPath, String text, List<String> workspaceGlobsOut) throws Exception {
        JsonNode root = JSON.readTree(text);
        String dir = relPath.contains("/") ? relPath.substring(0, relPath.lastIndexOf('/')) : "";
        String name = root.path("name").asText(null);
        String version = root.path("version").asText(null);
        List<Dependency> deps = new ArrayList<>();
        for (String[] g : GROUPS) {
            root.path(g[0]).fields().forEachRemaining(e -> {
                String spec = e.getValue().asText("");
                String local = null;
                for (String prefix : List.of("file:", "link:")) {
                    if (spec.startsWith(prefix)) local = MavenParser.normalize(dir, spec.substring(prefix.length()));
                }
                boolean workspace = spec.startsWith("workspace:");
                String resolved = workspace || local != null ? null : exactVersion(spec);
                deps.add(new Dependency(new Coordinate(Ecosystem.NPM, e.getKey()), spec, resolved, g[1],
                        false, local));
            });
        }
        JsonNode ws = root.path("workspaces");
        JsonNode globs = ws.isArray() ? ws : ws.path("packages");
        if (globs.isArray()) globs.forEach(n -> workspaceGlobsOut.add(MavenParser.normalize(dir, n.asText())));
        Map<String, String> extra = new LinkedHashMap<>();
        if (root.path("private").asBoolean(false)) extra.put("private", "true");
        Coordinate produces = name == null ? null : new Coordinate(Ecosystem.NPM, name);
        return new ModuleManifest(relPath, "npm", produces, version, null, deps, List.of(), extra);
    }

    static List<String> pnpmWorkspaces(String relPath, String text) {
        String dir = relPath.contains("/") ? relPath.substring(0, relPath.lastIndexOf('/')) : "";
        List<String> out = new ArrayList<>();
        try {
            JsonNode root = YAML.readTree(text);
            root.path("packages").forEach(n -> {
                String g = n.asText();
                if (!g.startsWith("!")) out.add(MavenParser.normalize(dir, g));
            });
        } catch (Exception ignored) {
            // malformed: no workspaces
        }
        return out;
    }

    /** "1.2.3" or "^1.2.3"/"~1.2.3" → "1.2.3"; ranges and tags → null. */
    static String exactVersion(String spec) {
        String s = spec.trim();
        if (s.startsWith("^") || s.startsWith("~") || s.startsWith("=")) s = s.substring(1);
        return s.matches("\\d+(\\.\\d+){0,2}([-+][0-9A-Za-z.-]+)?") ? s : null;
    }
}
