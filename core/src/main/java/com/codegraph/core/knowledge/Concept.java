package com.codegraph.core.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A concept a repo embodies: a capability, technique, pattern, API, piece of
 * infrastructure or domain idea. {@code slug} is the cross-repo key: the same slug
 * in two repos is the same concept.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Concept(
        String slug,
        String name,
        String kind,
        String summary,
        String details,
        String howToUse,
        List<Anchor> anchors,
        List<String> related) {

    public static final List<String> KINDS =
            List.of("capability", "technique", "pattern", "api", "infrastructure", "domain");

    /** Lower-case words joined by dashes: "Vector Search" and "vector-search" are one concept. */
    public static String slugOf(String name) {
        if (name == null) return "";
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    }
}
