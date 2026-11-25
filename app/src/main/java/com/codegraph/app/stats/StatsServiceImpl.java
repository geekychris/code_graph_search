package com.codegraph.app.stats;

import com.codegraph.core.knowledge.KnowledgeService;
import com.codegraph.core.knowledge.RepoCard;
import com.codegraph.core.model.Repository;
import com.codegraph.core.stats.RepoStats;
import com.codegraph.core.stats.StatsRollup;
import com.codegraph.core.stats.StatsService;
import com.codegraph.indexer.IndexerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/**
 * Stats per repo, stored as the repo fact {@value #FACT} at the indexed commit,
 * and rollups over them. Families and tags come from the repo cards (repo-atlas
 * or session-written), visibility from the repo.
 */
public class StatsServiceImpl implements StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsServiceImpl.class);
    public static final String FACT = "stats";

    private final IndexerService indexer;
    private final KnowledgeService knowledge;
    private final ObjectMapper mapper = new ObjectMapper();

    public StatsServiceImpl(IndexerService indexer, KnowledgeService knowledge) {
        this.indexer = indexer;
        this.knowledge = knowledge;
    }

    /** Whether the repo's stats are missing, from another commit, or from older rules. */
    public boolean stale(String repoId, String commit) {
        return repoStats(repoId).map(s -> !Objects.equals(s.commit(), commit) || s.revision() != StatsScanner.REVISION)
                .orElse(true);
    }

    /** Counts the checkout and stores the result. Failures are logged, never thrown: stats are extra. */
    public void compute(String repoId, Path dir, String commit) {
        long start = System.currentTimeMillis();
        try {
            RepoStats s = StatsScanner.scan(dir, repoId, commit);
            indexer.saveRepoFact(repoId, FACT, commit, mapper.writeValueAsString(s));
            log.info("Stats for {}: {} in {} ms", repoId, s.totals(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("Couldn't count {}: {}", repoId, e.getMessage());
        }
    }

    /**
     * Index listener for local repos (no git URL: not synced by the portfolio,
     * which counts its own repos in indexRepo). Counts the working tree at HEAD.
     */
    public void onRepoIndexed(String repoId) {
        indexer.getRepository(repoId).ifPresent(r -> {
            if (r.getGitUrl() != null || r.getRootPath() == null) return;
            Path dir = Path.of(r.getRootPath());
            String head = gitHead(dir);
            if (stale(repoId, head)) compute(repoId, dir, head);
        });
    }

    private static String gitHead(Path dir) {
        try {
            Process p = new ProcessBuilder("git", "-C", dir.toString(), "rev-parse", "HEAD").start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            return p.waitFor() == 0 && !out.isEmpty() ? out : "working-tree";
        } catch (Exception e) {
            return "working-tree";
        }
    }

    @Override
    public Optional<RepoStats> repoStats(String repoId) {
        return indexer.getRepoFact(repoId, FACT).flatMap(this::read);
    }

    @Override
    public StatsRollup.Result rollup(StatsRollup.Query query, Predicate<String> visible) {
        List<RepoStats> all = new ArrayList<>();
        indexer.listRepoFacts(FACT).forEach((repo, json) -> {
            if (visible.test(repo)) read(json).ifPresent(all::add);
        });
        Map<String, StatsRollup.RepoMeta> meta = new HashMap<>();
        Map<String, RepoCard> cards = new HashMap<>();
        if (knowledge != null) knowledge.cards(visible).forEach(c -> cards.put(c.repoId(), c));
        for (RepoStats s : all) {
            RepoCard card = cards.get(s.repoId());
            String vis = indexer.getRepository(s.repoId()).map(Repository::getVisibility)
                    .map(v -> v.name().toLowerCase(Locale.ROOT)).orElse(null);
            meta.put(s.repoId(), new StatsRollup.RepoMeta(card == null ? null : card.family(), vis,
                    card == null || card.tags() == null ? List.of() : card.tags()));
        }
        return StatsRollup.rollup(all, meta::get, query);
    }

    private Optional<RepoStats> read(String json) {
        try {
            return Optional.of(mapper.readValue(json, RepoStats.class));
        } catch (Exception e) {
            log.warn("Unreadable stats: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
