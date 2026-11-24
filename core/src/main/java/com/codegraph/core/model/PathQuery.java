package com.codegraph.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.function.Predicate;

public class PathQuery {
    private String fromId;
    private String toId;
    private int maxDepth = 6;
    private EdgeDirection direction = EdgeDirection.BOTH;
    private List<EdgeType> edgeTypes;  // null = all
    private int maxPaths = 50;

    /**
     * Optional node filter applied during traversal: paths never pass through a node
     * it rejects. Set by the visibility layer, never by clients.
     */
    @JsonIgnore
    private transient Predicate<String> nodeFilter;

    public PathQuery() {}

    public PathQuery(String fromId, String toId) {
        this.fromId = fromId;
        this.toId = toId;
    }

    public String getFromId() { return fromId; }
    public void setFromId(String fromId) { this.fromId = fromId; }
    public String getToId() { return toId; }
    public void setToId(String toId) { this.toId = toId; }
    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int maxDepth) { this.maxDepth = Math.min(maxDepth, 10); }
    public EdgeDirection getDirection() { return direction; }
    public void setDirection(EdgeDirection direction) { this.direction = direction; }
    public List<EdgeType> getEdgeTypes() { return edgeTypes; }
    public void setEdgeTypes(List<EdgeType> edgeTypes) { this.edgeTypes = edgeTypes; }
    public int getMaxPaths() { return maxPaths; }
    public void setMaxPaths(int maxPaths) { this.maxPaths = Math.min(maxPaths, 200); }

    @JsonIgnore
    public Predicate<String> getNodeFilter() { return nodeFilter; }
    @JsonIgnore
    public void setNodeFilter(Predicate<String> nodeFilter) { this.nodeFilter = nodeFilter; }
}
