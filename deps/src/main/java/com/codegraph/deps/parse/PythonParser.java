package com.codegraph.deps.parse;

import com.codegraph.deps.model.Coordinate;
import com.codegraph.deps.model.Dependency;
import com.codegraph.deps.model.ModuleManifest;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code pyproject.toml} (PEP 621 and Poetry) and {@code requirements*.txt}.
 * Only names and version specifiers are taken from PEP 508 strings.
 */
final class PythonParser {

    private static final Pattern PEP508 = Pattern.compile("^\\s*([A-Za-z0-9][A-Za-z0-9._-]*)\\s*(\\[[^]]*])?\\s*([^;]*)");

    private PythonParser() {}

    static ModuleManifest parsePyproject(String relPath, String text) {
        Map<String, Object> toml = MiniToml.parse(text);
        String name = MiniToml.text(toml, "project.name").or(() -> MiniToml.text(toml, "tool.poetry.name")).orElse(null);
        String version = MiniToml.text(toml, "project.version").or(() -> MiniToml.text(toml, "tool.poetry.version")).orElse(null);
        List<Dependency> deps = new ArrayList<>();
        for (Object o : MiniToml.list(MiniToml.at(toml, "project.dependencies"))) pep508(MiniToml.str(o), "compile", deps);
        for (Map.Entry<String, Object> extra : MiniToml.map(MiniToml.at(toml, "project.optional-dependencies")).entrySet()) {
            for (Object o : MiniToml.list(extra.getValue())) pep508(MiniToml.str(o), "optional", deps);
        }
        poetry(MiniToml.map(MiniToml.at(toml, "tool.poetry.dependencies")), "compile", deps);
        poetry(MiniToml.map(MiniToml.at(toml, "tool.poetry.dev-dependencies")), "dev", deps);
        for (Map.Entry<String, Object> g : MiniToml.map(MiniToml.at(toml, "tool.poetry.group")).entrySet()) {
            poetry(MiniToml.map(MiniToml.map(g.getValue()).get("dependencies")), "dev", deps);
        }
        Coordinate produces = name == null ? null : Coordinate.pypi(name);
        return new ModuleManifest(relPath, "python", produces, version, null, deps, List.of(), Map.of());
    }

    static ModuleManifest parseRequirements(String relPath, String text) {
        List<Dependency> deps = new ArrayList<>();
        String scope = relPath.toLowerCase().contains("dev") || relPath.toLowerCase().contains("test") ? "dev" : "compile";
        for (String raw : text.split("\n")) {
            String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')).trim() : raw.trim();
            if (line.isEmpty() || line.startsWith("-") || line.contains("://")) continue;
            pep508(line, scope, deps);
        }
        return new ModuleManifest(relPath, "python", null, null, null, deps, List.of(), Map.of());
    }

    private static void pep508(String spec, String scope, List<Dependency> deps) {
        if (spec == null) return;
        Matcher m = PEP508.matcher(spec);
        if (!m.find()) return;
        String constraint = m.group(3).trim();
        String exact = constraint.startsWith("==") ? constraint.substring(2).trim() : null;
        deps.add(new Dependency(Coordinate.pypi(m.group(1)), constraint.isEmpty() ? null : constraint, exact,
                scope, false, null));
    }

    private static void poetry(Map<String, Object> table, String scope, List<Dependency> deps) {
        for (Map.Entry<String, Object> e : table.entrySet()) {
            if (e.getKey().equalsIgnoreCase("python")) continue;
            Object v = e.getValue();
            String version = v instanceof String s ? s : MiniToml.str(MiniToml.map(v).get("version"));
            String path = v instanceof String ? null : MiniToml.str(MiniToml.map(v).get("path"));
            deps.add(new Dependency(Coordinate.pypi(e.getKey()), version,
                    version == null ? null : NpmParser.exactVersion(version), scope, false, path));
        }
    }
}
