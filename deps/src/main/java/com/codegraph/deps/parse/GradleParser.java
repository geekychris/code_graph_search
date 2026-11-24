package com.codegraph.deps.parse;

import com.codegraph.deps.model.Coordinate;
import com.codegraph.deps.model.Dependency;
import com.codegraph.deps.model.ModuleManifest;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Gradle builds (Groovy and Kotlin DSL) without running Gradle.
 *
 * <p>Recognises {@code group}/{@code version} assignments (and {@code gradle.properties}),
 * {@code settings.gradle} project names and includes, and dependency declarations in the
 * usual forms: {@code implementation("g:a:v")}, {@code api 'g:a:v'}, named arguments
 * ({@code group: 'g', name: 'a', version: 'v'}), {@code platform(...)} BOMs, version
 * catalog accessors ({@code libs.foo.bar} from {@code gradle/libs.versions.toml}) and
 * {@code project(":sub")} references. Builds that compute dependencies in code are only
 * partly understood.
 */
final class GradleParser {

    private static final String CONFS =
            "implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|testCompileOnly"
            + "|annotationProcessor|kapt|ksp|compile|testCompile|developmentOnly|integrationTestImplementation";
    private static final Pattern STRING_DEP = Pattern.compile(
            "\\b(" + CONFS + ")\\s*\\(?\\s*(platform\\s*\\(\\s*|enforcedPlatform\\s*\\(\\s*)?[\"']([^\"'$:\\s]+):([^\"'$:\\s]+)(?::([^\"'\\s]+))?[\"']");
    private static final Pattern NAMED_DEP = Pattern.compile(
            "\\b(" + CONFS + ")\\s*\\(?\\s*group\\s*[:=]\\s*[\"']([^\"']+)[\"']\\s*,\\s*name\\s*[:=]\\s*[\"']([^\"']+)[\"'](?:\\s*,\\s*version\\s*[:=]\\s*[\"']([^\"']+)[\"'])?");
    private static final Pattern CATALOG_DEP = Pattern.compile(
            "\\b(" + CONFS + ")\\s*\\(?\\s*(platform\\s*\\(\\s*)?libs\\.([A-Za-z0-9_.]+)");
    private static final Pattern PROJECT_DEP = Pattern.compile(
            "\\b(" + CONFS + ")\\s*\\(?\\s*project\\s*\\(\\s*(?:path\\s*[:=]\\s*)?[\"']([^\"']+)[\"']");
    private static final Pattern GROUP = Pattern.compile("(?m)^\\s*group\\s*=?\\s*[\"']([^\"']+)[\"']");
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*version\\s*=?\\s*[\"']([^\"']+)[\"']");
    private static final Pattern ROOT_NAME = Pattern.compile("rootProject\\.name\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*include\\s*\\(?([^)\\n]+)\\)?");
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");

    private GradleParser() {}

    /** settings.gradle: root project name and included project paths (":a:b" → "a/b"). */
    record Settings(String rootName, List<String> includes) {}

    static Settings parseSettings(String text) {
        String root = find(ROOT_NAME, text);
        List<String> includes = new ArrayList<>();
        Matcher m = INCLUDE.matcher(text);
        while (m.find()) {
            Matcher q = QUOTED.matcher(m.group(1));
            while (q.find()) includes.add(q.group(1).replaceFirst("^:", "").replace(':', '/'));
        }
        return new Settings(root, includes);
    }

    /** gradle/libs.versions.toml: catalog accessor ("foo.bar") → [group:artifact, version]. */
    static Map<String, String[]> parseCatalog(String text) {
        Map<String, Object> toml = MiniToml.parse(text);
        Map<String, Object> versions = MiniToml.map(toml.get("versions"));
        Map<String, String[]> out = new HashMap<>();
        for (Map.Entry<String, Object> e : MiniToml.map(toml.get("libraries")).entrySet()) {
            String ga = null, version = null;
            if (e.getValue() instanceof String s) {
                String[] p = s.split(":");
                if (p.length >= 2) ga = p[0] + ":" + p[1];
                if (p.length >= 3) version = p[2];
            } else {
                Map<String, Object> t = MiniToml.map(e.getValue());
                if (MiniToml.str(t.get("module")) != null) ga = MiniToml.str(t.get("module"));
                else if (MiniToml.str(t.get("group")) != null) ga = MiniToml.str(t.get("group")) + ":" + MiniToml.str(t.get("name"));
                Object v = t.get("version");
                if (v instanceof String s) version = s;
                else {
                    Map<String, Object> vt = MiniToml.map(v);
                    String ref = MiniToml.str(vt.get("ref"));
                    version = ref != null ? MiniToml.str(versions.get(ref)) : MiniToml.str(vt.get("strictly"));
                }
                String ref = MiniToml.str(t.get("version.ref"));
                if (ref == null) ref = MiniToml.str(MiniToml.map(t.get("version")).get("ref"));
                if (ref != null) version = MiniToml.str(versions.get(ref));
            }
            if (ga != null) out.put(e.getKey().replace('-', '.').replace('_', '.'), new String[]{ga, version});
        }
        return out;
    }

    static ModuleManifest parseBuild(String relPath, String text, String projectName, String inheritedGroup,
                                     String inheritedVersion, Map<String, String[]> catalog,
                                     Map<String, String> projectPathToName) {
        String body = stripComments(text);
        String group = Optional.ofNullable(find(GROUP, body)).orElse(inheritedGroup);
        String version = Optional.ofNullable(find(VERSION, body)).orElse(inheritedVersion);
        List<Dependency> deps = new ArrayList<>();

        Matcher m = STRING_DEP.matcher(body);
        while (m.find()) {
            boolean bom = m.group(2) != null;
            String v = m.group(5);
            deps.add(new Dependency(Coordinate.maven(m.group(3), m.group(4)), v, v,
                    bom ? "import" : scope(m.group(1)), bom, null));
        }
        m = NAMED_DEP.matcher(body);
        while (m.find()) {
            deps.add(new Dependency(Coordinate.maven(m.group(2), m.group(3)), m.group(4), m.group(4),
                    scope(m.group(1)), false, null));
        }
        m = CATALOG_DEP.matcher(body);
        while (m.find()) {
            String key = m.group(3).replace('_', '.');
            if (key.startsWith("versions.") || key.startsWith("plugins.") || key.startsWith("bundles.")) continue;
            String[] lib = catalog.get(key);
            if (lib == null) continue;
            String[] ga = lib[0].split(":");
            if (ga.length < 2) continue;
            boolean bom = m.group(2) != null;
            deps.add(new Dependency(Coordinate.maven(ga[0], ga[1]), "libs." + m.group(3), lib[1],
                    bom ? "import" : scope(m.group(1)), true, null));
        }
        m = PROJECT_DEP.matcher(body);
        while (m.find()) {
            String path = m.group(2).replaceFirst("^:", "").replace(':', '/');
            String name = projectPathToName.getOrDefault(path, path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path);
            if (group != null) {
                deps.add(new Dependency(Coordinate.maven(group, name), null, version, scope(m.group(1)), false, path));
            }
        }
        Coordinate produces = group == null || projectName == null ? null : Coordinate.maven(group, projectName);
        return new ModuleManifest(relPath, "gradle", produces, version, null, deps, List.of(), Map.of());
    }

    static String group(String text) { return find(GROUP, stripComments(text)); }

    static String version(String text) { return find(VERSION, stripComments(text)); }

    /** gradle.properties: plain key=value. */
    static Map<String, String> properties(String text) {
        Map<String, String> out = new HashMap<>();
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.startsWith("#") || !l.contains("=")) continue;
            out.put(l.substring(0, l.indexOf('=')).trim(), l.substring(l.indexOf('=') + 1).trim());
        }
        return out;
    }

    private static String scope(String conf) {
        String c = conf.toLowerCase();
        if (c.startsWith("test") || c.startsWith("integrationtest")) return "test";
        if (c.equals("compileonly") || c.equals("annotationprocessor") || c.equals("kapt") || c.equals("ksp")) return "provided";
        if (c.equals("runtimeonly") || c.equals("developmentonly")) return "runtime";
        return "compile";
    }

    private static String find(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
