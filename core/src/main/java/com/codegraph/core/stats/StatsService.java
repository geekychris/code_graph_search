package com.codegraph.core.stats;

import java.util.Optional;
import java.util.function.Predicate;

/** Project stats: per repo, and rolled up across the repos a caller may see. */
public interface StatsService {

    /** A repo's stats at its indexed commit, if counted yet. */
    Optional<RepoStats> repoStats(String repoId);

    /** Rolls up the stats of the repos visible passes. */
    StatsRollup.Result rollup(StatsRollup.Query query, Predicate<String> visible);
}
