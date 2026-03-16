package com.codegraph.core.model;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum Language {
    JAVA("java", List.of(".java")),
    GO("go", List.of(".go")),
    RUST("rust", List.of(".rs")),
    TYPESCRIPT("typescript", List.of(".ts", ".tsx")),
    JAVASCRIPT("javascript", List.of(".js", ".jsx", ".mjs", ".cjs")),
    C("c", List.of(".c", ".h")),
    CPP("cpp", List.of(".cpp", ".cc", ".cxx", ".hpp", ".hxx", ".h++")),
    MARKDOWN("markdown", List.of(".md", ".markdown")),
    YAML("yaml", List.of(".yaml", ".yml")),
    JSON("json", List.of(".json")),
    UNKNOWN("unknown", List.of());

    public final String id;
    public final List<String> extensions;

    Language(String id, List<String> extensions) {
        this.id = id;
        this.extensions = extensions;
    }

    public static Optional<Language> fromExtension(String ext) {
        String lower = ext.toLowerCase();
        return Arrays.stream(values())
                .filter(l -> l.extensions.contains(lower))
                .findFirst();
    }

    public static Language fromFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return UNKNOWN;
        return fromExtension(fileName.substring(dot)).orElse(UNKNOWN);
    }

    public boolean isSource() {
        return this != MARKDOWN && this != YAML && this != JSON && this != UNKNOWN;
    }
}
