package com.codegraph.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CodeEdge {

    /** Metadata key: qualified name the parser expected the target to have. */
    public static final String META_TARGET_QNAME = "targetQName";
    /** Metadata key: element type the parser expected the target to have. */
    public static final String META_TARGET_TYPE = "targetType";
    /** Metadata key on resolver-made edges: how the target was found ("cross-repo"). */
    public static final String META_RESOLVED = "resolved";

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
