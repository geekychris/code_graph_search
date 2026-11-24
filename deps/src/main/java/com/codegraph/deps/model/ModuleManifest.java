package com.codegraph.deps.model;

import java.util.List;
import java.util.Map;

/**
 * What one build module produces and depends on.
 *
 * @param manifestPath repo-relative path of the manifest file
 * @param buildTool    maven, gradle, go, npm, cargo or python
 * @param produces     the artifact the module publishes, or null (e.g. a private npm package)
 * @param version      the module's own version, resolved, or null
 * @param parent       Maven parent coordinate, or null
 * @param dependencies declared dependencies
 * @param submodules   repo-relative directories of child modules (Maven modules, workspaces)
 * @param extra        anything else worth keeping (packaging, go version, ...)
 */
public record ModuleManifest(String manifestPath, String buildTool, Coordinate produces, String version,
                             Coordinate parent, List<Dependency> dependencies, List<String> submodules,
                             Map<String, String> extra) {}
