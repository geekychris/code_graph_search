package com.codegraph.core.model;

import java.util.List;

public class PathQuery {
    private String fromId;
    private String toId;
    private int maxDepth = 6;
    private EdgeDirection direction = EdgeDirection.BOTH;
    private List<EdgeType> edgeTypes;  // null = all
    private int maxPaths = 50;

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
}
