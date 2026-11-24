package com.codegraph.deps.model;

import java.util.Locale;

/**
 * Identity of a published artifact, without a version.
 *
 * @param ecosystem package namespace
 * @param name      {@code groupId:artifactId} for Maven, the module path for Go, the
 *                  package name for npm and Cargo, the PEP 503 normalised name for PyPI
 */
public record Coordinate(Ecosystem ecosystem, String name) {

    public static Coordinate maven(String groupId, String artifactId) {
        return new Coordinate(Ecosystem.MAVEN, groupId + ":" + artifactId);
    }

    public static Coordinate pypi(String name) {
        return new Coordinate(Ecosystem.PYPI, name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-"));
    }

    /** Stable key such as {@code maven:com.hitorro:hitorro-core}. */
    public String key() { return ecosystem.prefix() + ":" + name; }

    /** Parses a {@link #key()} back into a coordinate. */
    public static Coordinate parse(String key) {
        int i = key.indexOf(':');
        return new Coordinate(Ecosystem.valueOf(key.substring(0, i).toUpperCase(Locale.ROOT)), key.substring(i + 1));
    }

    @Override
    public String toString() { return key(); }
}
