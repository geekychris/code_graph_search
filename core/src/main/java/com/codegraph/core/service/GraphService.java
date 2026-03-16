package com.codegraph.core.service;

import com.codegraph.core.model.*;

import java.util.List;
import java.util.Optional;

/**
 * Primary service interface for all graph operations: storage, retrieval, traversal.
 */
public interface GraphService {

    // --- Write operations ---
    void saveElement(CodeElement element);
    void saveElements(List<CodeElement> elements);
    void saveEdge(CodeEdge edge);
    void saveEdges(List<CodeEdge> edges);
    void deleteElementsForFile(String repoId, String filePath);
    void deleteRepo(String repoId);

    // --- Element retrieval ---
    Optional<CodeElement> getElementById(String id);
    List<CodeElement> getElementsByFile(String repoId, String filePath);
    List<CodeElement> getElementsByQualifiedName(String repoId, String qualifiedName);

    // --- Graph traversal ---
    Optional<CodeElement> getParent(String elementId);
    List<CodeElement> getAncestors(String elementId);
    List<CodeElement> getChildren(String elementId);
    List<CodeElement> getChildren(String elementId, ElementType type);
    List<CodeElement> getSiblings(String elementId);

    // --- Type hierarchy ---
    Optional<CodeElement> getSuperclass(String classId);
    List<CodeElement> getInterfaces(String classId);
    List<CodeElement> getSubclasses(String classId);
    List<CodeElement> getImplementors(String interfaceId);
    Optional<CodeElement> getOverriddenMethod(String methodId);
    List<CodeElement> getOverriders(String methodId);

    // --- Call graph ---
    List<CodeElement> getCallers(String methodId);
    List<CodeElement> getCallees(String methodId);
    List<List<CodeElement>> getCallChain(String fromMethodId, String toMethodId, int maxDepth);

    // --- Documentation ---
    List<CodeElement> getComments(String elementId);
    List<CodeElement> getAnnotations(String elementId);

    // --- Imports / dependencies ---
    List<CodeElement> getImports(String fileElementId);
    List<CodeElement> getImportedBy(String fileElementId);
    List<CodeElement> getUsages(String elementId);

    // --- Repository ---
    List<Repository> listRepositories();
    Optional<Repository> getRepository(String repoId);
    void saveRepository(Repository repo);
    List<String> listFilePaths(String repoId);

    // --- Edges ---
    List<CodeEdge> getEdgesFrom(String elementId);
    List<CodeEdge> getEdgesTo(String elementId);
    List<CodeEdge> getEdgesFrom(String elementId, EdgeType type);
    List<CodeEdge> getEdgesTo(String elementId, EdgeType type);

    // --- Search ---
    List<CodeElement> search(com.codegraph.core.model.SearchQuery query);
    long count(com.codegraph.core.model.SearchQuery query);

    // --- FOAF / Connectivity ---
    PathResult findShortestPath(PathQuery query);
    PathResult findAllShortestPaths(PathQuery query);
    PathResult findAllPaths(PathQuery query);
    SimilarityResult computeSimilarity(String elementIdA, String elementIdB,
                                        EdgeDirection direction, List<EdgeType> edgeTypes);

    // --- Subgraph for visualization ---
    record SubGraph(List<CodeElement> nodes, List<CodeEdge> edges) {}
    SubGraph getSubGraph(String rootElementId, int depth, List<EdgeType> edgeTypes);

    void close();
}
