package com.codegraph.core.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * What sessions have written about one repo: an overview (Markdown), a concept
 * drawing (Mermaid) and its concepts, with the commit they describe and who wrote them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RepoKnowledge(
        String repoId,
        String overview,
        String diagram,
        List<Concept> concepts,
        String commit,
        String author,
        Instant updated) {
}
