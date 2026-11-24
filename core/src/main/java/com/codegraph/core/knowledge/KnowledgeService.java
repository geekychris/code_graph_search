package com.codegraph.core.knowledge;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The concept layer over the code graph. Every read takes the caller's repo
 * visibility; nothing about a repo the caller can't see is returned.
 */
public interface KnowledgeService {

    /** Cards for all visible repos. */
    List<RepoCard> cards(Predicate<String> visible);

    Optional<RepoCard> card(String repoId);

    /** What sessions wrote about this repo, if anything. */
    Optional<RepoKnowledge> knowledge(String repoId);

    /** One level of a repo's structure; {@code path} "" is the top. */
    Architecture architecture(String repoId, String path, Predicate<String> visible);

    /** Concepts matching {@code query} (all of them when blank), best first. */
    List<ConceptIndexEntry> searchConcepts(String query, String kind, Predicate<String> visible, int limit);

    Optional<ConceptIndexEntry> concept(String slug, Predicate<String> visible);

    /** Portfolio-level write-ups (repo-atlas synthesis), visible when their source repo is. */
    Map<String, String> synthesis(Predicate<String> visible);

    // ---- session work queue (inversion of control: sessions write, the service never calls a model) ----

    record WorkItem(String repoId, String reason, String indexedCommit, String describedCommit) {}

    /** Repos that have no session-written knowledge, or whose knowledge is older than the index. */
    List<WorkItem> pendingWork(Predicate<String> visible, int limit);

    /**
     * Claims a repo (the given one, or the next pending one) for {@code who} and returns
     * the context a session needs to describe it. Empty when nothing is pending.
     */
    Optional<Map<String, Object>> claim(String repoId, String who, Predicate<String> visible);

    record Submission(String overview, String diagram, List<Concept> concepts) {}

    record SubmitResult(String repoId, int concepts, int anchorsResolved, int anchorsUnresolved, List<String> warnings) {}

    SubmitResult submit(String repoId, Submission submission, String who);
}
