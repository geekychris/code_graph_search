package com.codegraph.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
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

    /**
     * Jackson deserializer that accepts either the enum NAME ("JAVA") or the
     * lowercase id ("java"). Without this, Jackson's default enum
     * deserializer is case-sensitive on the enum name, so a config using the
     * lowercase form shown in README + config-example.yaml
     * ({@code languages: [java, go]}) throws InvalidFormatException. Because
     * {@link com.codegraph.core.config.AppConfig#loadOrDefault} catches ALL
     * exceptions silently, that failure surfaced only as "server starts on
     * port 8080 with no repos indexed" — a very hard-to-diagnose default.
     */
    @JsonCreator
    public static Language fromJson(String value) {
        if (value == null) return UNKNOWN;
        String v = value.trim();
        for (Language l : values()) {
            if (l.name().equalsIgnoreCase(v) || l.id.equalsIgnoreCase(v)) {
                return l;
            }
        }
        return UNKNOWN;
    }

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
