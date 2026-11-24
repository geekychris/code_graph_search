package com.codegraph.app.deps;

import com.codegraph.core.model.*;
import com.codegraph.indexer.IndexerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

/**
 * Links code in one repository to the types and methods it uses from another.
 *
 * <p>Parsers compute edge targets inside the parsing repo, so a call to a class that
 * lives in a library repo points at an id that does not exist. Edges that carry the
 * target's qualified name ({@link CodeEdge#META_TARGET_QNAME}) are looked up in the
 * repos this repo depends on, and a matching edge to the real element is added
 * (marked {@code resolved=cross-repo}). Java imports are linked the same way, as
 * {@code IMPORTS} edges from the import to the imported type.
 *
 * <p>Only repos in the dependency closure are searched, so two unrelated repos that
 * happen to share a class name are never linked.
 */
public class CrossRepoResolver {

    private static final Logger log = LoggerFactory.getLogger(CrossRepoResolver.class);
    private static final Set<EdgeType> RESOLVABLE =
            EnumSet.of(EdgeType.CALLS, EdgeType.EXTENDS, EdgeType.IMPLEMENTS, EdgeType.INSTANTIATES);
    private static final Set<ElementType> TYPES =
            EnumSet.of(ElementType.CLASS, ElementType.INTERFACE, ElementType.ENUM);

    private final IndexerService graph;

    public CrossRepoResolver(IndexerService graph) {
        this.graph = graph;
    }

    /**
     * Resolves dangling references from {@code repoId} into {@code candidateRepos}.
     * Returns the number of cross-repo edges written.
     */
    public int resolve(String repoId, Set<String> candidateRepos) {
        if (candidateRepos.isEmpty()) return 0;
        Function<String, Optional<CodeElement>> lookup = cache(candidateRepos);
        List<CodeEdge> toSave = new ArrayList<>();

        for (String file : graph.listFilePaths(repoId)) {
            for (CodeElement el : graph.getElementsByFile(repoId, file)) {
                if (el.getElementType() == ElementType.IMPORT && el.getLanguage() == Language.JAVA) {
                    String qn = el.getQualifiedName();
                    if (qn != null && !qn.endsWith(".*")) {
                        lookup.apply(qn + "|TYPE").ifPresent(target -> toSave.add(
                                resolved(el.getId(), target, EdgeType.IMPORTS)));
                    }
                    continue;
                }
                for (CodeEdge edge : graph.getEdgesFrom(el.getId())) {
                    if (!RESOLVABLE.contains(edge.getEdgeType()) || edge.getMetadata() == null) continue;
                    if (CodeEdge.META_RESOLVED.equals(edge.getMetadata().get(CodeEdge.META_RESOLVED))) continue;
                    String qn = edge.getMetadata().get(CodeEdge.META_TARGET_QNAME);
                    if (qn == null || qn.isBlank()) continue;
                    if (graph.getElementById(edge.getToId()).isPresent()) continue;   // resolved locally
                    String kind = "METHOD".equals(edge.getMetadata().get(CodeEdge.META_TARGET_TYPE)) ? "METHOD" : "TYPE";
                    lookup.apply(qn + "|" + kind).ifPresent(target ->
                            toSave.add(resolved(edge.getFromId(), target, edge.getEdgeType())));
                }
            }
        }
        graph.saveEdges(toSave);
        if (!toSave.isEmpty()) {
            log.info("Repo '{}': {} cross-repo references resolved", repoId, toSave.size());
        }
        return toSave.size();
    }

    private static CodeEdge resolved(String fromId, CodeElement target, EdgeType type) {
        CodeEdge e = new CodeEdge(fromId, target.getId(), type);
        Map<String, String> meta = new HashMap<>();
        meta.put(CodeEdge.META_RESOLVED, "cross-repo");
        meta.put("targetRepo", target.getRepoId());
        e.setMetadata(meta);
        return e;
    }

    /** Qualified-name lookups restricted to candidate repos, memoised for one pass. */
    private Function<String, Optional<CodeElement>> cache(Set<String> candidateRepos) {
        Map<String, Optional<CodeElement>> memo = new HashMap<>();
        return key -> memo.computeIfAbsent(key, k -> {
            int bar = k.lastIndexOf('|');
            String qn = k.substring(0, bar);
            boolean method = k.endsWith("|METHOD");
            return graph.getElementsByQualifiedName(null, qn).stream()
                    .filter(e -> candidateRepos.contains(e.getRepoId()))
                    .filter(e -> method ? e.getElementType() == ElementType.METHOD
                            || e.getElementType() == ElementType.CONSTRUCTOR
                            : TYPES.contains(e.getElementType()))
                    .findFirst();
        });
    }
}
