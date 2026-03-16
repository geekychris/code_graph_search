package com.codegraph.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PathResult {
    private List<List<CodeElement>> paths;
    private List<List<CodeEdge>> pathEdges;
    private int shortestPathLength;
    private long computeTimeMs;

    public PathResult() {}

    public PathResult(List<List<CodeElement>> paths, List<List<CodeEdge>> pathEdges, int shortestPathLength, long computeTimeMs) {
        this.paths = paths;
        this.pathEdges = pathEdges;
        this.shortestPathLength = shortestPathLength;
        this.computeTimeMs = computeTimeMs;
    }

    public List<List<CodeElement>> getPaths() { return paths; }
    public void setPaths(List<List<CodeElement>> paths) { this.paths = paths; }
    public List<List<CodeEdge>> getPathEdges() { return pathEdges; }
    public void setPathEdges(List<List<CodeEdge>> pathEdges) { this.pathEdges = pathEdges; }
    public int getShortestPathLength() { return shortestPathLength; }
    public void setShortestPathLength(int shortestPathLength) { this.shortestPathLength = shortestPathLength; }
    public long getComputeTimeMs() { return computeTimeMs; }
    public void setComputeTimeMs(long computeTimeMs) { this.computeTimeMs = computeTimeMs; }
}
