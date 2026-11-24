package com.codegraph.deps.model;

import java.util.List;

/** Every build module found in one repository, plus problems met while reading them. */
public record RepoManifests(List<ModuleManifest> modules, List<String> warnings) {

    public static RepoManifests empty() { return new RepoManifests(List.of(), List.of()); }
}
