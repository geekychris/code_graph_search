package com.codegraph.core.knowledge;

import java.util.List;

/**
 * A concept across the portfolio: every visible repo that has it. {@code source}
 * is "session" for concepts a session wrote, "tag" for repo-atlas tags and
 * "family" for repo-atlas families.
 */
public record ConceptIndexEntry(
        String slug,
        String name,
        String kind,
        String source,
        String summary,
        List<Usage> repos) {

    public record Usage(String repoId, String summary, String howToUse, List<Anchor> anchors) {}
}
