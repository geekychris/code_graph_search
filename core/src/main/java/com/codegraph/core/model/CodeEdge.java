package com.codegraph.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CodeEdge {

    private String id;
    private String fromId;
    private String toId;
    private EdgeType edgeType;
    private Map<String, String> metadata;

    public CodeEdge() {}

    public CodeEdge(String fromId, String toId, EdgeType edgeType) {
        this.fromId = fromId;
        this.toId = toId;
        this.edgeType = edgeType;
        this.id = fromId + "_" + edgeType.name() + "_" + toId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFromId() { return fromId; }
    public void setFromId(String fromId) { this.fromId = fromId; }

    public String getToId() { return toId; }
    public void setToId(String toId) { this.toId = toId; }

    public EdgeType getEdgeType() { return edgeType; }
    public void setEdgeType(EdgeType edgeType) { this.edgeType = edgeType; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
}
