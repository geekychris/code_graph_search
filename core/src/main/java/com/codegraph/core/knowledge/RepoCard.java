package com.codegraph.core.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What a repo is, at a glance: purpose, family, status and tags, plus the longer
 * write-up sections (Purpose, Design, Technology, ...). Imported from a repo-atlas
 * dossier or submitted by a Claude session.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RepoCard(
        String repoId,
        String family,
        String category,
        String status,
        Integer maturity,
        String purpose,
        List<String> languages,
        List<String> tech,
        List<String> tags,
        List<String> related,
        String supersededBy,
        String analyzed,
        String analyzedCommit,
        Map<String, String> sections,
        String source) {

    /** This card with related repos the caller can't see removed. */
    public RepoCard visibleTo(Predicate<String> visible) {
        if (related == null || related.stream().allMatch(visible)) return this;
        return new RepoCard(repoId, family, category, status, maturity, purpose, languages, tech, tags,
                related.stream().filter(visible).toList(), supersededBy, analyzed, analyzedCommit, sections, source);
    }
}
