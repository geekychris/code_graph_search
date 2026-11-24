package com.codegraph.deps.model;

/**
 * One declared dependency of a module.
 *
 * @param coordinate      what is depended on
 * @param declaredVersion the version text as written (may hold {@code ${prop}}, a range, or be null)
 * @param resolvedVersion the version after property, parent, BOM and catalog resolution, or null
 * @param scope           compile, test, runtime, provided, import, dev, peer, optional, build, indirect...
 * @param managed         true if the version came from dependency management, a BOM or a catalog
 * @param localPath       repo-relative path for local/sibling references (go replace, file:, path =), or null
 */
public record Dependency(Coordinate coordinate, String declaredVersion, String resolvedVersion,
                         String scope, boolean managed, String localPath) {

    public Dependency withResolved(String version, boolean viaManagement) {
        return new Dependency(coordinate, declaredVersion, version, scope, viaManagement, localPath);
    }
}
