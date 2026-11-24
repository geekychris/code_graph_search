package com.codegraph.app.stats;

import java.util.*;

/**
 * Decides a file's language and kind from its path. The rules are documented in
 * docs/STATS.md; bump {@link StatsScanner#REVISION} when they change, so repos
 * are counted again.
 */
public final class FileClassifier {

    private FileClassifier() {}

    /** How a language writes comments, for line counting. */
    public enum Comments {
        /** // and block slash-star. */ C,
        /** #. */ HASH,
        /** -- and block slash-star. */ SQL,
        /** angle-bang-dash-dash blocks. */ XML,
        /** -- and --[[ ]]. */ LUA,
        /** ;. */ SEMI,
        /** ; anywhere, or * in the first column (assemblers). */ ASM,
        /** -- and {- -}. */ HASKELL,
        /** %. */ PERCENT,
        /** ' and REM. */ BASIC,
        /** //, { } and (* *). */ PASCAL,
        /** (* *). */ ML,
        /** REM and ::. */ BATCH,
        /** Prose and data: no comments, every non-blank line counts as code. */ NONE
    }

    /** A language and its comment style. */
    public record Lang(String name, Comments comments) {}

    private static final Map<String, Lang> BY_EXT = new HashMap<>();
    private static final Map<String, Lang> BY_NAME = new HashMap<>();

    private static void ext(String name, Comments c, String... exts) {
        Lang l = new Lang(name, c);
        for (String e : exts) BY_EXT.put(e, l);
    }

    private static void file(String name, Comments c, String... names) {
        Lang l = new Lang(name, c);
        for (String n : names) BY_NAME.put(n.toLowerCase(Locale.ROOT), l);
    }

    static {
        ext("Java", Comments.C, "java");
        ext("Kotlin", Comments.C, "kt", "kts");
        ext("Scala", Comments.C, "scala", "sc");
        ext("Groovy", Comments.C, "groovy", "gradle");
        ext("Go", Comments.C, "go");
        ext("Rust", Comments.C, "rs");
        ext("C", Comments.C, "c", "h");
        ext("C++", Comments.C, "cc", "cpp", "cxx", "hpp", "hh", "hxx", "ino", "ipp", "tpp");
        ext("Objective-C", Comments.C, "m", "mm");
        ext("C#", Comments.C, "cs");
        ext("F#", Comments.ML, "fs", "fsi", "fsx");
        ext("Swift", Comments.C, "swift");
        ext("Dart", Comments.C, "dart");
        ext("JavaScript", Comments.C, "js", "mjs", "cjs", "jsx");
        ext("TypeScript", Comments.C, "ts", "tsx", "mts", "cts");
        ext("Vue", Comments.XML, "vue");
        ext("Svelte", Comments.XML, "svelte");
        ext("PHP", Comments.C, "php");
        ext("Zig", Comments.C, "zig");
        ext("GLSL", Comments.C, "glsl", "vert", "frag", "hlsl", "metal", "wgsl", "shader");
        ext("OpenSCAD", Comments.C, "scad");
        ext("Protobuf", Comments.C, "proto");
        ext("Thrift", Comments.C, "thrift");
        ext("GraphQL", Comments.HASH, "graphql", "gql");
        ext("Python", Comments.HASH, "py", "pyi", "pyw");
        ext("Ruby", Comments.HASH, "rb", "rake", "gemspec");
        ext("Perl", Comments.HASH, "pl", "pm");
        ext("R", Comments.HASH, "r");
        ext("Julia", Comments.HASH, "jl");
        ext("Elixir", Comments.HASH, "ex", "exs");
        ext("Nim", Comments.HASH, "nim");
        ext("Shell", Comments.HASH, "sh", "bash", "zsh", "ksh", "fish", "command");
        ext("PowerShell", Comments.HASH, "ps1", "psm1");
        ext("Batch", Comments.BATCH, "bat", "cmd");
        ext("Tcl", Comments.HASH, "tcl");
        ext("AppleScript", Comments.SQL, "applescript");
        ext("SQL", Comments.SQL, "sql");
        ext("Lua", Comments.LUA, "lua");
        ext("Haskell", Comments.HASKELL, "hs", "lhs");
        ext("Elm", Comments.HASKELL, "elm");
        ext("OCaml", Comments.ML, "ml", "mli");
        ext("Erlang", Comments.PERCENT, "erl", "hrl");
        ext("MATLAB", Comments.PERCENT, "matlab");
        ext("TeX", Comments.PERCENT, "tex", "sty", "cls", "bib");
        ext("Lisp", Comments.SEMI, "lisp", "lsp", "el", "cl", "clj", "cljs", "cljc", "edn", "scm", "ss", "rkt", "fnl");
        ext("Assembly", Comments.ASM, "s", "asm", "a68", "68k", "i", "inc", "x68");
        ext("BASIC", Comments.BASIC, "bas", "bb", "vb", "vbs", "frm");
        ext("Pascal", Comments.PASCAL, "pas", "pp", "dpr", "lpr", "inc.pas");
        ext("Fortran", Comments.NONE, "f", "f90", "f95", "for");
        ext("AmigaE", Comments.C, "e");
        ext("Verilog", Comments.C, "v", "sv", "svh");
        ext("VHDL", Comments.SQL, "vhd", "vhdl");
        ext("HTML", Comments.XML, "html", "htm", "xhtml", "jsp", "ftl", "hbs", "mustache", "ejs", "erb", "j2", "jinja");
        ext("CSS", Comments.C, "css", "scss", "sass", "less", "styl");
        ext("XML", Comments.XML, "xml", "xsd", "xsl", "xslt", "wsdl", "plist", "fxml", "xaml", "csproj", "fsproj", "vcxproj", "props", "targets", "resx");
        ext("JSON", Comments.NONE, "json", "json5", "jsonc", "jsonl", "ndjson", "avsc", "geojson", "webmanifest", "har", "ipynb");
        ext("YAML", Comments.HASH, "yaml", "yml");
        ext("TOML", Comments.HASH, "toml");
        ext("INI", Comments.SEMI, "ini", "cfg", "conf", "desktop", "service", "editorconfig");
        ext("Properties", Comments.HASH, "properties", "env");
        ext("HCL", Comments.HASH, "tf", "tfvars", "hcl", "nomad");
        ext("Nix", Comments.HASH, "nix");
        ext("Makefile", Comments.HASH, "mk", "mak", "make");
        ext("CMake", Comments.HASH, "cmake");
        ext("Markdown", Comments.NONE, "md", "markdown", "mdx", "mdc");
        ext("AsciiDoc", Comments.NONE, "adoc", "asciidoc", "asc");
        ext("reStructuredText", Comments.NONE, "rst");
        ext("Text", Comments.NONE, "txt", "text", "log");
        ext("CSV", Comments.NONE, "csv", "tsv", "psv");
        ext("Diff", Comments.NONE, "diff", "patch");
        ext("SVG", Comments.XML, "svg");

        file("Makefile", Comments.HASH, "Makefile", "GNUmakefile", "makefile", "Makefile.am", "Makefile.in");
        file("Dockerfile", Comments.HASH, "Dockerfile", "Containerfile");
        file("CMake", Comments.HASH, "CMakeLists.txt");
        file("Groovy", Comments.C, "Jenkinsfile");
        file("Ruby", Comments.HASH, "Gemfile", "Rakefile", "Podfile", "Vagrantfile", "Brewfile");
        file("Starlark", Comments.HASH, "BUILD", "BUILD.bazel", "WORKSPACE", "WORKSPACE.bazel", "MODULE.bazel");
        file("Text", Comments.NONE, "LICENSE", "LICENCE", "COPYING", "NOTICE", "AUTHORS", "CONTRIBUTORS", "CHANGELOG", "README", "TODO");
        file("Shell", Comments.HASH, "gradlew", "mvnw", ".bashrc", ".zshrc", ".profile");
        file("Ignore list", Comments.HASH, ".gitignore", ".dockerignore", ".npmignore", ".gitattributes", ".helmignore", ".prettierignore", ".eslintignore");
        file("Go", Comments.C, "go.mod", "go.work");
        file("Text", Comments.NONE, "go.sum");
        file("Python", Comments.HASH, "requirements.txt", "requirements-dev.txt", "constraints.txt", "Pipfile");
    }

    /** Extensions of files that are never text: counted by size only. */
    private static final Set<String> BINARY = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "icns", "webp", "tif", "tiff", "psd", "xcf", "heic", "avif",
            "mp3", "wav", "ogg", "flac", "aiff", "aif", "mod", "sid", "mp4", "mov", "avi", "mkv", "webm",
            "ttf", "otf", "woff", "woff2", "eot",
            "zip", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar", "war", "ear", "lha", "lzh", "adf", "dms", "hdf", "iso", "dmg",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "key", "numbers", "pages",
            "class", "o", "obj", "a", "so", "dylib", "dll", "exe", "bin", "dat", "db", "sqlite", "sqlite3", "parquet",
            "avro", "orc", "npy", "npz", "pkl", "pt", "onnx", "safetensors", "gguf", "stl", "3mf", "glb", "gltf", "fbx", "blend",
            "wasm", "pyc", "lib", "rom", "iff", "ilbm", "8svx", "amos", "prg", "atr", "xex", "st", "msa", "dsk");

    private static final Set<String> TEST_DIRS = Set.of("test", "tests", "__tests__", "spec", "specs", "testing", "testdata",
            "test-data", "e2e", "it", "integration-tests", "fixtures", "__mocks__", "mocks", "androidtest", "unittest", "unittests");
    private static final Set<String> DOC_DIRS = Set.of("docs", "doc", "documentation", "manual", "wiki", "guides");
    private static final Set<String> CI_DIRS = Set.of(".github", ".circleci", ".gitlab", ".buildkite", ".woodpecker", ".drone");
    private static final Set<String> LOCKFILES = Set.of("package-lock.json", "yarn.lock", "pnpm-lock.yaml", "npm-shrinkwrap.json",
            "go.sum", "cargo.lock", "poetry.lock", "pipfile.lock", "gemfile.lock", "composer.lock", "pubspec.lock",
            "mix.lock", "flake.lock", "uv.lock", "bun.lockb", "packages.lock.json", "gradle.lockfile");
    private static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
            "settings.gradle.kts", "gradle.properties", "package.json", "go.mod", "go.work", "cargo.toml", "makefile",
            "gnumakefile", "cmakelists.txt", "setup.py", "setup.cfg", "pyproject.toml", "pipfile", "requirements.txt",
            "requirements-dev.txt", "constraints.txt", "build.xml", "meson.build", "build", "build.bazel", "workspace",
            "workspace.bazel", "module.bazel", "rakefile", "gemfile", "podfile", "pubspec.yaml", "mix.exs", "deno.json",
            "gradlew", "mvnw", "justfile", "taskfile.yml", "vite.config.ts", "vite.config.js", "webpack.config.js",
            "rollup.config.js", "tsconfig.json", "babel.config.js", ".babelrc", "dockerfile", "containerfile",
            "docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml", "skaffold.yaml", "tiltfile");
    private static final Set<String> CI_FILES = Set.of("jenkinsfile", ".gitlab-ci.yml", ".travis.yml", "azure-pipelines.yml",
            "bitbucket-pipelines.yml", "cloudbuild.yaml", "appveyor.yml", ".drone.yml", "codecov.yml");
    private static final Set<String> DOC_NAMES = Set.of("readme", "license", "licence", "copying", "notice", "authors",
            "contributors", "changelog", "changes", "history", "contributing", "code_of_conduct", "security", "todo");
    private static final Set<String> PROSE = Set.of("Markdown", "AsciiDoc", "reStructuredText", "Text", "TeX");
    private static final Set<String> CONFIG_LANGS = Set.of("JSON", "YAML", "TOML", "INI", "Properties", "XML", "HCL",
            "Nix", "Ignore list", "Starlark");
    private static final Set<String> DATA_LANGS = Set.of("CSV", "Diff");

    /** The language of a path, or null when its extension isn't known (counted as "Other"). */
    public static Lang language(String path) {
        String name = baseName(path);
        Lang l = BY_NAME.get(name.toLowerCase(Locale.ROOT));
        if (l != null) return l;
        if (name.startsWith("Dockerfile") || name.startsWith("Containerfile")) return BY_NAME.get("dockerfile");
        if (name.startsWith(".env")) return BY_EXT.get("env");
        String ext = extension(name);
        return ext == null ? null : BY_EXT.get(ext);
    }

    /** Whether the extension says binary, before reading the file. */
    public static boolean binaryByName(String path) {
        String ext = extension(baseName(path));
        return ext != null && BINARY.contains(ext);
    }

    /**
     * The file's kind. Order matters: generated beats everything, then assets, CI,
     * tests, docs, build, config, data, then source.
     */
    public static String kind(String path, String language, boolean binary) {
        String lower = path.toLowerCase(Locale.ROOT);
        String name = baseName(lower);
        List<String> dirs = dirs(lower);

        if (LOCKFILES.contains(name) || name.contains(".min.") || name.endsWith(".pb.go") || name.endsWith("_pb2.py")
                || name.endsWith("_pb2_grpc.py") || name.endsWith(".g.dart") || name.endsWith(".designer.cs")
                || name.contains(".generated.") || dirs.contains("generated") || dirs.contains("__generated__")
                || dirs.contains("gen-src")) {
            return "generated";
        }
        if (binary) return "asset";
        if (CI_FILES.contains(name) || (!dirs.isEmpty() && CI_DIRS.contains(dirs.get(0))
                && (dirs.contains("workflows") || !".github".equals(dirs.get(0))))) {
            return "ci";
        }
        if (isTest(lower, name, dirs)) return "test";
        String stem = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        if ((language != null && PROSE.contains(language)) || DOC_NAMES.contains(stem)
                || dirs.stream().anyMatch(DOC_DIRS::contains)) {
            return language != null && !PROSE.contains(language) && !"SVG".equals(language) && !DOC_NAMES.contains(stem)
                    ? sourceOrConfig(language) : "docs";
        }
        if (BUILD_FILES.contains(name) || name.startsWith("dockerfile") || name.endsWith(".cmake")
                || name.endsWith(".csproj") || name.endsWith(".sln") || name.endsWith(".gradle.kts")) {
            return "build";
        }
        if (language == null) return "other";
        return sourceOrConfig(language);
    }

    private static String sourceOrConfig(String language) {
        if (CONFIG_LANGS.contains(language)) return "config";
        if (DATA_LANGS.contains(language)) return "data";
        if ("SVG".equals(language)) return "asset";
        if (PROSE.contains(language)) return "docs";
        return "source";
    }

    private static boolean isTest(String lower, String name, List<String> dirs) {
        if (dirs.stream().anyMatch(TEST_DIRS::contains)) return true;
        if (lower.contains("/src/test/") || lower.startsWith("src/test/")) return true;
        return name.endsWith("_test.go") || name.matches(".*(test|tests|spec|it)\\.(java|kt|scala|groovy|cs|swift)")
                || name.startsWith("test_") && name.endsWith(".py") || name.endsWith("_test.py")
                || name.matches(".*\\.(test|spec)\\.[cm]?[jt]sx?") || name.endsWith("_spec.rb") || name.endsWith("_test.rb")
                || name.endsWith("_test.rs") || name.endsWith("_test.dart") || name.endsWith("_test.exs")
                || name.endsWith("_test.c") || name.endsWith("_test.cpp") || name.endsWith("_test.cc")
                || name.endsWith(".bats");
    }

    static String baseName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    private static String extension(String name) {
        int i = name.lastIndexOf('.');
        return i <= 0 || i == name.length() - 1 ? null : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static List<String> dirs(String lower) {
        String[] parts = lower.split("/");
        return parts.length <= 1 ? List.of() : Arrays.asList(parts).subList(0, parts.length - 1);
    }
}
