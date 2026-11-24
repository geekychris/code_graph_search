package com.codegraph.deps.model;

/**
 * Package namespace an artifact lives in. Gradle builds publish Maven coordinates, so
 * they use {@link #MAVEN}; the build tool is recorded separately on the module.
 */
public enum Ecosystem {
    MAVEN, GO, NPM, CARGO, PYPI;

    public String prefix() { return name().toLowerCase(); }
}
