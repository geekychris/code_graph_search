package com.codegraph.deps.parse;

import com.codegraph.deps.model.ModuleManifest;
import com.codegraph.deps.model.RepoManifests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Finds and reads every build manifest in a repository checkout.
 *
 * <p>Supports Maven ({@code pom.xml}), Gradle ({@code build.gradle[.kts]},
 * {@code settings.gradle[.kts]}, {@code gradle.properties}, {@code gradle/libs.versions.toml}),
 * Go ({@code go.mod}), npm ({@code package.json}, {@code pnpm-workspace.yaml}), Cargo
 * ({@code Cargo.toml}) and Python ({@code pyproject.toml}, {@code requirements*.txt}).
 * Offline and side-effect free: nothing is executed. Never throws; problems are
 * returned as warnings.
 */
public final class ManifestScanner {

    private static final Logger log = LoggerFactory.getLogger(ManifestScanner.class);
    private static final long MAX_BYTES = 2_000_000;
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", "dist", "vendor", ".venv", "venv", "__pycache__",
            ".gradle", ".idea", "out", "bin", "obj", ".next", ".nuxt", "coverage", "site-packages", "Pods");

    private ManifestScanner() {}

    public static RepoManifests scan(Path repoRoot) {
        List<String> warnings = new ArrayList<>();
        Map<String, List<String>> found = find(repoRoot, warnings);
        List<ModuleManifest> modules = new ArrayList<>();

        // Maven: read all, then resolve together (parents may come after children)
        List<MavenParser.Pom> poms = new ArrayList<>();
        for (String rel : found.getOrDefault("pom.xml", List.of())) {
            MavenParser.Pom pom = MavenParser.read(repoRoot.resolve(rel), rel, warnings);
            if (pom != null) poms.add(pom);
        }
        modules.addAll(MavenParser.resolve(poms));

        for (String rel : found.getOrDefault("go.mod", List.of())) {
            read(repoRoot, rel, warnings).ifPresent(t -> modules.add(GoModParser.parseGoMod(rel, t)));
        }

        Map<String, List<String>> pnpm = new HashMap<>();
        for (String rel : found.getOrDefault("pnpm-workspace.yaml", List.of())) {
            read(repoRoot, rel, warnings).ifPresent(t -> pnpm.put(dirOf(rel), NpmParser.pnpmWorkspaces(rel, t)));
        }
        for (String rel : found.getOrDefault("package.json", List.of())) {
            read(repoRoot, rel, warnings).ifPresent(t -> {
                try {
                    List<String> globs = new ArrayList<>(pnpm.getOrDefault(dirOf(rel), List.of()));
                    ModuleManifest m = NpmParser.parse(rel, t, globs);
                    modules.add(withSubmodules(m, expandGlobs(repoRoot, globs)));
                } catch (Exception e) {
                    warnings.add(rel + ": unreadable package.json (" + e.getMessage() + ")");
                }
            });
        }

        cargo(repoRoot, found.getOrDefault("Cargo.toml", List.of()), modules, warnings);

        for (String rel : found.getOrDefault("pyproject.toml", List.of())) {
            read(repoRoot, rel, warnings).ifPresent(t -> modules.add(PythonParser.parsePyproject(rel, t)));
        }
        for (String rel : found.getOrDefault("requirements", List.of())) {
            read(repoRoot, rel, warnings).ifPresent(t -> modules.add(PythonParser.parseRequirements(rel, t)));
        }

        gradle(repoRoot, found, modules, warnings);

        modules.sort(Comparator.comparing(ModuleManifest::manifestPath));
        return new RepoManifests(List.copyOf(modules), List.copyOf(warnings));
    }

    // -------------------------------------------------------------------------

    private static void cargo(Path root, List<String> files, List<ModuleManifest> modules, List<String> warnings) {
        Map<String, Object> workspaceDeps = new HashMap<>();
        List<String> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparingInt(String::length));
        for (String rel : sorted) {
            Optional<String> text = read(root, rel, warnings);
            if (text.isEmpty()) continue;
            if (workspaceDeps.isEmpty()) workspaceDeps.putAll(CargoParser.workspaceDependencies(text.get()));
            List<String> members = new ArrayList<>();
            ModuleManifest m = CargoParser.parse(rel, text.get(), workspaceDeps, members);
            modules.add(withSubmodules(m, expandGlobs(root, members)));
        }
    }

    private static void gradle(Path root, Map<String, List<String>> found, List<ModuleManifest> modules,
                               List<String> warnings) {
        List<String> builds = found.getOrDefault("build.gradle", List.of());
        if (builds.isEmpty()) return;

        // Each settings file marks a build root
        Map<String, GradleParser.Settings> settings = new HashMap<>();
        for (String rel : found.getOrDefault("settings.gradle", List.of())) {
            read(root, rel, warnings).ifPresent(t -> settings.put(dirOf(rel), GradleParser.parseSettings(t)));
        }
        for (String rel : builds) {
            String dir = dirOf(rel);
            String buildRoot = nearest(dir, settings.keySet());
            if (buildRoot == null) buildRoot = dir;
            GradleParser.Settings s = settings.get(buildRoot);

            Map<String, String> props = read(root, join(buildRoot, "gradle.properties"), null)
                    .map(GradleParser::properties).orElse(Map.of());
            String rootBuildText = readFirst(root, join(buildRoot, "build.gradle.kts"), join(buildRoot, "build.gradle"));
            String inheritedGroup = Optional.ofNullable(rootBuildText == null ? null : GradleParser.group(rootBuildText))
                    .orElse(props.get("group"));
            String inheritedVersion = Optional.ofNullable(rootBuildText == null ? null : GradleParser.version(rootBuildText))
                    .orElse(props.get("version"));
            Map<String, String[]> catalog = read(root, join(buildRoot, "gradle/libs.versions.toml"), null)
                    .map(GradleParser::parseCatalog).orElse(Map.of());

            Map<String, String> pathToName = new HashMap<>();
            if (s != null) for (String inc : s.includes()) pathToName.put(inc, lastSegment(inc));
            String name;
            if (dir.equals(buildRoot)) {
                name = s != null && s.rootName() != null ? s.rootName()
                        : (dir.isEmpty() ? root.getFileName().toString() : lastSegment(dir));
            } else {
                String sub = buildRoot.isEmpty() ? dir : dir.substring(buildRoot.length() + 1);
                name = pathToName.getOrDefault(sub, lastSegment(dir));
            }
            String finalName = name;
            read(root, rel, warnings).ifPresent(t -> {
                ModuleManifest m = GradleParser.parseBuild(rel, t, finalName, inheritedGroup, inheritedVersion,
                        catalog, pathToName);
                List<String> subs = new ArrayList<>();
                if (s != null && dir.equals(dirOf(rel)) && dir.equals(nearest(dir, settings.keySet()))) {
                    for (String inc : s.includes()) subs.add(join(dir, inc));
                }
                modules.add(withSubmodules(m, subs));
            });
        }
    }

    // -------------------------------------------------------------------------

    /** Groups manifest files by kind, repo-relative with forward slashes. */
    private static Map<String, List<String>> find(Path root, List<String> warnings) {
        Map<String, List<String>> found = new HashMap<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) return FileVisitResult.CONTINUE;
                    String name = dir.getFileName().toString();
                    return SKIP_DIRS.contains(name) || name.startsWith(".")
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    String kind = switch (name) {
                        case "pom.xml", "go.mod", "package.json", "pnpm-workspace.yaml", "Cargo.toml",
                             "pyproject.toml" -> name;
                        case "build.gradle", "build.gradle.kts" -> "build.gradle";
                        case "settings.gradle", "settings.gradle.kts" -> "settings.gradle";
                        default -> name.startsWith("requirements") && name.endsWith(".txt") ? "requirements" : null;
                    };
                    if (kind != null) {
                        found.computeIfAbsent(kind, k -> new ArrayList<>())
                                .add(root.relativize(file).toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            warnings.add("cannot walk " + root + ": " + e.getMessage());
        }
        found.values().forEach(Collections::sort);
        return found;
    }

    private static Optional<String> read(Path root, String rel, List<String> warnings) {
        Path p = root.resolve(rel);
        try {
            if (!Files.isRegularFile(p)) return Optional.empty();
            if (Files.size(p) > MAX_BYTES) {
                if (warnings != null) warnings.add(rel + ": larger than " + MAX_BYTES + " bytes, skipped");
                return Optional.empty();
            }
            return Optional.of(Files.readString(p, StandardCharsets.UTF_8));
        } catch (Exception e) {
            if (warnings != null) warnings.add(rel + ": " + e.getMessage());
            log.debug("Cannot read {}: {}", p, e.getMessage());
            return Optional.empty();
        }
    }

    private static String readFirst(Path root, String... rels) {
        for (String rel : rels) {
            Optional<String> t = read(root, rel, null);
            if (t.isPresent()) return t.get();
        }
        return null;
    }

    /** Expands "dir/*" and plain directory entries to existing directories. */
    private static List<String> expandGlobs(Path root, List<String> globs) {
        List<String> out = new ArrayList<>();
        for (String g : globs) {
            if (g.endsWith("/*") || g.endsWith("/**")) {
                String base = g.substring(0, g.lastIndexOf('/'));
                Path dir = root.resolve(base);
                if (!Files.isDirectory(dir)) continue;
                try (var s = Files.list(dir)) {
                    s.filter(Files::isDirectory).map(p -> root.relativize(p).toString().replace('\\', '/'))
                            .sorted().forEach(out::add);
                } catch (IOException ignored) {
                    // unreadable: skip
                }
            } else if (!g.contains("*")) {
                out.add(g);
            }
        }
        return out;
    }

    private static ModuleManifest withSubmodules(ModuleManifest m, List<String> subs) {
        if (subs.isEmpty()) return m;
        List<String> all = new ArrayList<>(m.submodules());
        all.addAll(subs);
        return new ModuleManifest(m.manifestPath(), m.buildTool(), m.produces(), m.version(), m.parent(),
                m.dependencies(), List.copyOf(all), m.extra());
    }

    private static String nearest(String dir, Set<String> roots) {
        String best = null;
        for (String r : roots) {
            if ((r.isEmpty() || dir.equals(r) || dir.startsWith(r + "/")) && (best == null || r.length() > best.length())) {
                best = r;
            }
        }
        return best;
    }

    static String dirOf(String rel) {
        int i = rel.lastIndexOf('/');
        return i < 0 ? "" : rel.substring(0, i);
    }

    private static String join(String dir, String rel) {
        return dir.isEmpty() ? rel : dir + "/" + rel;
    }

    private static String lastSegment(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }
}
