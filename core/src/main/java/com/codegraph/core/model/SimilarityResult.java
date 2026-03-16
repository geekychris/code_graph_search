package com.codegraph.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SimilarityResult {
    private List<CodeElement> commonNeighbors;
    private int neighborCountA;
    private int neighborCountB;
    private double jaccardSimilarity;
    private double adamicAdarIndex;
    private CodeElement elementA;
    private CodeElement elementB;
    private long computeTimeMs;

    public SimilarityResult() {}

    public List<CodeElement> getCommonNeighbors() { return commonNeighbors; }
    public void setCommonNeighbors(List<CodeElement> commonNeighbors) { this.commonNeighbors = commonNeighbors; }
    public int getNeighborCountA() { return neighborCountA; }
    public void setNeighborCountA(int neighborCountA) { this.neighborCountA = neighborCountA; }
    public int getNeighborCountB() { return neighborCountB; }
    public void setNeighborCountB(int neighborCountB) { this.neighborCountB = neighborCountB; }
    public double getJaccardSimilarity() { return jaccardSimilarity; }
    public void setJaccardSimilarity(double jaccardSimilarity) { this.jaccardSimilarity = jaccardSimilarity; }
    public double getAdamicAdarIndex() { return adamicAdarIndex; }
    public void setAdamicAdarIndex(double adamicAdarIndex) { this.adamicAdarIndex = adamicAdarIndex; }
    public CodeElement getElementA() { return elementA; }
    public void setElementA(CodeElement elementA) { this.elementA = elementA; }
    public CodeElement getElementB() { return elementB; }
    public void setElementB(CodeElement elementB) { this.elementB = elementB; }
    public long getComputeTimeMs() { return computeTimeMs; }
    public void setComputeTimeMs(long computeTimeMs) { this.computeTimeMs = computeTimeMs; }
}
