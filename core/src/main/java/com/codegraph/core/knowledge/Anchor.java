package com.codegraph.core.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Where a concept lives in the code. A session gives a qualified name or a path;
 * the service resolves it to an element id when it can.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Anchor(String label, String qualifiedName, String path, String elementId, String elementType) {
}
