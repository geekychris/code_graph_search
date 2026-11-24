package com.codegraph.deps.parse;

import com.codegraph.deps.model.Coordinate;
import com.codegraph.deps.model.Dependency;
import com.codegraph.deps.model.Ecosystem;
import com.codegraph.deps.model.ModuleManifest;

import java.util.*;

/**
 * Reads {@code Cargo.toml}: package name and version, normal/dev/build dependencies in
 * string and table form, {@code path =} local references, {@code workspace = true}
 * inheritance from the root's {@code [workspace.dependencies]}, and workspace members.
 */
final class CargoParser {

    private CargoParser() {}

    static ModuleManifest parse(String relPath, String text, Map<String, Object> workspaceDeps,
                                List<String> membersOut) {
        Map<String, Object> toml = MiniToml.parse(text);
        String dir = relPath.contains("/") ? relPath.substring(0, relPath.lastIndexOf('/')) : "";
        String name = MiniToml.text(toml, "package.name").orElse(null);
        String version = MiniToml.text(toml, "package.version").orElse(null);
        List<Dependency> deps = new ArrayList<>();
        add(deps, MiniToml.map(toml.get("dependencies")), "compile", dir, workspaceDeps);
        add(deps, MiniToml.map(toml.get("dev-dependencies")), "dev", dir, workspaceDeps);
        add(deps, MiniToml.map(toml.get("build-dependencies")), "build", dir, workspaceDeps);
        for (Object m : MiniToml.list(MiniToml.at(toml, "workspace.members"))) {
            String s = MiniToml.str(m);
            if (s != null) membersOut.add(MavenParser.normalize(dir, s));
        }
        Coordinate produces = name == null ? null : new Coordinate(Ecosystem.CARGO, name);
        return new ModuleManifest(relPath, "cargo", produces, version, null, deps, List.of(), Map.of());
    }

    /** The root's {@code [workspace.dependencies]} table, for members that inherit. */
    static Map<String, Object> workspaceDependencies(String text) {
        return MiniToml.map(MiniToml.at(MiniToml.parse(text), "workspace.dependencies"));
    }

    private static void add(List<Dependency> deps, Map<String, Object> table, String scope, String dir,
                            Map<String, Object> workspaceDeps) {
        for (Map.Entry<String, Object> e : table.entrySet()) {
            Object spec = e.getValue();
            Map<String, Object> t = MiniToml.map(spec);
            if ("true".equals(MiniToml.str(t.get("workspace")))) spec = workspaceDeps.getOrDefault(e.getKey(), spec);
            String version;
            String path = null;
            String pkg = e.getKey();
            if (spec instanceof String s) {
                version = s;
            } else {
                Map<String, Object> st = MiniToml.map(spec);
                version = MiniToml.str(st.get("version"));
                path = MiniToml.str(st.get("path"));
                if (MiniToml.str(st.get("package")) != null) pkg = MiniToml.str(st.get("package"));
            }
            String local = path == null ? null : MavenParser.normalize(dir, path);
            deps.add(new Dependency(new Coordinate(Ecosystem.CARGO, pkg), version,
                    version == null ? null : NpmParser.exactVersion(version), scope, false, local));
        }
    }
}
