package com.codegraph.app.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * A repo's code references folded to file level: the raw material for every
 * architecture view. Stored as the {@code filegraph} repo fact, keyed by commit.
 *
 * @param files   code files with their element and type counts
 * @param edges   references between files ({@code to} is a file index) or to another
 *                repo ({@code to} is -1 and {@code repo} names it)
 * @param types   the referenced types and functions, with inbound counts
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FileGraph(String repoId, String commit, List<FileNode> files, List<FileEdge> edges, List<TypeNode> types) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileNode(String path, String language, int elements, int types) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileEdge(int from, int to, String repo, String target, Map<String, Integer> kinds) {
        public int weight() {
            return kinds.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TypeNode(String id, String name, String qualifiedName, String kind, int file, int inbound, int crossRepoInbound) {}
}
