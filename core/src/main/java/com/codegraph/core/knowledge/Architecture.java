package com.codegraph.core.knowledge;

import java.util.List;
import java.util.Map;

/**
 * One level of a repo's structure, derived from the code graph. {@code path} is the
 * group being looked inside ("" for the top); its {@code groups} are the next level
 * down (directories, packages or modules), {@code edges} count code references
 * between them, and {@code external} counts references to other repos.
 */
public record Architecture(
        String repoId,
        String path,
        List<String> breadcrumbs,
        List<Group> groups,
        List<Edge> edges,
        List<External> external,
        List<KeyType> keyTypes,
        String commit) {

    public record Group(String id, String label, int files, int elements, int types,
                        Map<String, Integer> languages, boolean hasChildren, int inbound, int outbound) {}

    public record Edge(String from, String to, int weight, Map<String, Integer> kinds) {}

    /** References to another repo: in total, by group here, and the most-used targets there. */
    public record External(String repoId, int weight, Map<String, Integer> fromGroups, List<String> targets) {}

    /** The most-referenced types in this part of the repo: its load-bearing classes. */
    public record KeyType(String id, String name, String qualifiedName, String kind, String path,
                          String group, int inbound, int crossRepoInbound) {}
}
