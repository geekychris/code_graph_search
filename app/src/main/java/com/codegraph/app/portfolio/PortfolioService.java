package com.codegraph.app.portfolio;

import com.codegraph.core.config.AppConfig;
import com.codegraph.core.config.RepoConfig;
import com.codegraph.core.model.Repository;
import com.codegraph.core.model.Visibility;
import com.codegraph.core.service.PortfolioControl;
import com.codegraph.indexer.IndexerService;
import com.codegraph.parser.ParserRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Keeps the index in step with every repository the configured GitHub owners have.
 *
 * <p>A sync lists the owners' repos, records each one (visibility, URLs, default branch,
 * last push) in the registry, and queues an index job for any repo that is new, has been
 * pushed since it was indexed, or failed last time. A job fetches the repo's checkout and
 * re-indexes only the files changed since the indexed commit, falling back to a full
 * index when there is no usable previous commit.
 *
 * <p>Syncs run at startup, every {@code syncIntervalMinutes}, on demand, and per repo
 * from GitHub push webhooks.
 */
public class PortfolioService implements PortfolioControl {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);
    public static final String SOURCE_GITHUB = "github";

    private final AppConfig.PortfolioConfig config;
    private final IndexerService indexer;
    private final ParserRegistry parsers;
    private final RepoLister lister;
    private final GitMirror mirror;
    private final List<String> defaultExcludes = new RepoConfig().getExcludePatterns();
    private final ObjectMapper mapper = new ObjectMapper();

    private final ExecutorService jobs;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "portfolio-sync");
        t.setDaemon(true);
        return t;
    });

    /** Repos with a job queued or running; a second request while running re-runs once after. */
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();
    /** Latest push time seen from GitHub per repo, applied once the repo is indexed. */
    private final ConcurrentHashMap<String, Optional<Instant>> pendingPush = new ConcurrentHashMap<>();
    private volatile SyncReport lastSync;

    /** What one sync found and did. */
    public record SyncReport(Instant at, int listed, int tracked, int queued, int skipped, List<String> errors) {}

    public PortfolioService(AppConfig.PortfolioConfig config, IndexerService indexer, ParserRegistry parsers,
                            RepoLister lister, GitMirror mirror) {
        this.config = config;
        this.indexer = indexer;
        this.parsers = parsers;
        this.lister = lister;
        this.mirror = mirror;
        this.jobs = Executors.newFixedThreadPool(Math.max(1, config.getConcurrency()), r -> {
            Thread t = new Thread(r, "portfolio-index");
            t.setDaemon(true);
            return t;
        });
    }

    /** Builds the service from config, reading the GitHub token from the configured environment variable. */
    public static PortfolioService fromConfig(AppConfig.PortfolioConfig config, IndexerService indexer,
                                              ParserRegistry parsers) {
        String token = System.getenv(config.getGithubTokenEnv());
        if (token == null || token.isBlank()) {
            log.warn("{} is not set: only public repositories will be synced", config.getGithubTokenEnv());
            token = null;
        }
        GitHubClient github = new GitHubClient(config.getGithubApiUrl(), token);
        GitMirror mirror = new GitMirror(Path.of(config.getMirrorDir()).toAbsolutePath(), token,
                config.getGitTimeoutSeconds());
        return new PortfolioService(config, indexer, parsers, github, mirror);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void start() {
        int minutes = Math.max(1, config.getSyncIntervalMinutes());
        scheduler.scheduleWithFixedDelay(this::syncAllQuietly, 0, minutes, TimeUnit.MINUTES);
        log.info("Portfolio sync every {} min for owners {}", minutes, config.getOwners());
    }

    public void stop() {
        scheduler.shutdownNow();
        jobs.shutdownNow();
    }

    private void syncAllQuietly() {
        try {
            syncAll();
        } catch (Exception e) {
            log.error("Portfolio sync failed: {}", e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Sync
    // -------------------------------------------------------------------------

    /** Lists every owner's repos, updates the registry and queues index jobs. */
    public synchronized SyncReport syncAll() {
        int listed = 0, tracked = 0, queued = 0, skipped = 0;
        List<String> errors = new ArrayList<>();
        for (String owner : config.getOwners()) {
            List<RepoLister.RemoteRepo> remote;
            try {
                remote = lister.listRepos(owner);
            } catch (Exception e) {
                errors.add(owner + ": " + e.getMessage());
                log.error("Cannot list repos for {}: {}", owner, e.getMessage());
                continue;
            }
            listed += remote.size();
            for (RepoLister.RemoteRepo r : remote) {
                if (!wanted(r)) {
                    skipped++;
                    continue;
                }
                tracked++;
                Repository repo = record(owner, r);
                if (needsIndexing(repo, r)) {
                    if (enqueue(repo.getId())) queued++;
                }
            }
        }
        lastSync = new SyncReport(Instant.now(), listed, tracked, queued, skipped, errors);
        log.info("Portfolio sync: {} listed, {} tracked, {} queued, {} skipped{}", listed, tracked, queued, skipped,
                errors.isEmpty() ? "" : ", errors: " + errors);
        return lastSync;
    }

    public SyncReport lastSync() { return lastSync; }

    @Override
    public Object syncNow() { return syncAll(); }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("owners", config.getOwners());
        m.put("lastSync", lastSync);
        m.put("activeJobs", new TreeSet<>(inFlight.keySet()));
        return m;
    }

    /** Repo ids with a job queued or running. */
    public Set<String> activeJobs() { return Set.copyOf(inFlight.keySet()); }

    private boolean wanted(RepoLister.RemoteRepo r) {
        if (r.fork() && !config.isIncludeForks()) return false;
        if (r.archived() && !config.isIncludeArchived()) return false;
        if (!config.getInclude().isEmpty() && !config.getInclude().contains(r.name())) return false;
        return !config.getExclude().contains(r.name());
    }

    /** Registry id: the bare name for the first owner, {@code owner__name} for any others. */
    String repoId(String owner, String name) {
        boolean primary = config.getOwners().isEmpty() || config.getOwners().get(0).equalsIgnoreCase(owner);
        return primary ? name : owner + "__" + name;
    }

    private Repository record(String owner, RepoLister.RemoteRepo r) {
        String id = repoId(owner, r.name());
        Repository repo = indexer.getRepository(id).orElseGet(() -> {
            Repository fresh = new Repository();
            fresh.setStatus(Repository.IndexingStatus.PENDING);
            return fresh;
        });
        repo.setId(id);
        repo.setName(r.name());
        repo.setSource(SOURCE_GITHUB);
        repo.setVisibility(r.isPrivate() ? Visibility.PRIVATE : Visibility.PUBLIC);
        repo.setGitUrl(r.cloneUrl());
        repo.setHtmlUrl(r.htmlUrl());
        repo.setDefaultBranch(r.defaultBranch());
        repo.setArchived(r.archived());
        repo.setRootPath(mirror.dirFor(id).toString());
        if (r.description() != null) repo.setDescription(r.description());
        // pushedAt is only advanced after a successful index, so it doubles as "indexed as of"
        indexer.saveRepository(repo);
        pendingPush.put(id, Optional.ofNullable(r.pushedAt()));
        return repo;
    }

    private boolean needsIndexing(Repository repo, RepoLister.RemoteRepo r) {
        if (repo.getIndexedCommit() == null) return true;
        if (repo.getStatus() == Repository.IndexingStatus.ERROR) return true;
        if (r.pushedAt() == null || repo.getPushedAt() == null) return true;
        return r.pushedAt().isAfter(repo.getPushedAt());
    }

    /** Queues an index job for a tracked repo. Returns false if it is unknown. */
    @Override
    public boolean enqueue(String repoId) {
        if (indexer.getRepository(repoId).isEmpty()) return false;
        Boolean previous = inFlight.putIfAbsent(repoId, Boolean.FALSE);
        if (previous != null) {
            inFlight.put(repoId, Boolean.TRUE);   // running: run once more when it finishes
            return true;
        }
        jobs.submit(() -> runJob(repoId));
        return true;
    }

    private void runJob(String repoId) {
        try {
            indexRepo(repoId);
        } catch (Exception e) {
            log.error("Index job for {} failed: {}", repoId, e.getMessage(), e);
            indexer.getRepository(repoId).ifPresent(r -> {
                r.setStatus(Repository.IndexingStatus.ERROR);
                indexer.saveRepository(r);
            });
        } finally {
            if (Boolean.TRUE.equals(inFlight.remove(repoId))) {
                enqueue(repoId);
            }
        }
    }

    /** Fetches the repo and brings its index up to the fetched commit. */
    void indexRepo(String repoId) throws Exception {
        Repository repo = indexer.getRepository(repoId).orElseThrow();
        String sha = mirror.sync(repoId, repo.getGitUrl(), repo.getDefaultBranch());
        Path dir = mirror.dirFor(repoId);
        RepoConfig rc = toRepoConfig(repo, dir);

        if (sha == null) {
            log.info("Repo {} is empty", repoId);
        } else if (sha.equals(repo.getIndexedCommit())) {
            log.debug("Repo {} already at {}", repoId, sha);
        } else {
            GitMirror.ChangeSet changes = repo.getIndexedCommit() == null ? null
                    : mirror.diff(dir, repo.getIndexedCommit(), sha);
            if (changes == null) {
                log.info("Full index of {} at {}", repoId, abbrev(sha));
                indexer.indexRepository(rc, parsers);
            } else {
                log.info("Incremental index of {} {}..{}: {} changed, {} deleted", repoId,
                        abbrev(repo.getIndexedCommit()), abbrev(sha), changes.changed().size(), changes.deleted().size());
                indexer.indexRepositoryChanges(rc, changes.changed(), changes.deleted(), parsers);
            }
        }

        Repository latest = indexer.getRepository(repoId).orElse(repo);
        latest.setIndexedCommit(sha);
        latest.setStatus(Repository.IndexingStatus.READY);
        Optional<Instant> pushed = pendingPush.remove(repoId);
        if (pushed != null) latest.setPushedAt(pushed.orElse(Instant.now()));
        else if (latest.getPushedAt() == null) latest.setPushedAt(Instant.now());
        indexer.saveRepository(latest);
    }

    private RepoConfig toRepoConfig(Repository repo, Path dir) {
        RepoConfig rc = new RepoConfig();
        rc.setId(repo.getId());
        rc.setName(repo.getName());
        rc.setPath(dir.toString());
        rc.setVisibility(repo.getVisibility());
        rc.setDescription(repo.getDescription());
        List<String> excludes = new ArrayList<>(defaultExcludes);
        excludes.addAll(List.of("**/dist/**", "**/vendor/**", "**/.venv/**", "**/__pycache__/**", "**/*.min.js"));
        rc.setExcludePatterns(excludes);
        return rc;
    }

    private static String abbrev(String sha) {
        return sha == null ? "none" : sha.substring(0, Math.min(8, sha.length()));
    }

    // -------------------------------------------------------------------------
    // Webhooks
    // -------------------------------------------------------------------------

    /**
     * Verifies a GitHub webhook's {@code X-Hub-Signature-256} against the configured
     * secret. Returns false when no secret is configured, so webhooks are off by default.
     */
    @Override
    public boolean verifySignature(byte[] body, String signatureHeader) {
        String secret = System.getenv(config.getWebhookSecretEnv());
        if (secret == null || secret.isBlank() || signatureHeader == null
                || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Handles a verified webhook. A push to a tracked repo queues its index job; a
     * repository event (created, renamed, made public or private, deleted) triggers a
     * full sync so the registry and visibility catch up.
     */
    @Override
    public String handleWebhook(String event, byte[] body) {
        try {
            JsonNode payload = mapper.readTree(body);
            String owner = payload.path("repository").path("owner").path("login").asText("");
            String name = payload.path("repository").path("name").asText("");
            switch (event == null ? "" : event) {
                case "ping" -> { return "pong"; }
                case "push" -> {
                    String id = repoId(owner, name);
                    if (indexer.getRepository(id).isEmpty()) {
                        CompletableFuture.runAsync(this::syncAllQuietly);
                        return "unknown repo " + id + ": full sync queued";
                    }
                    pendingPush.put(id, Optional.of(Instant.now()));
                    enqueue(id);
                    return "queued " + id;
                }
                case "repository", "public", "member" -> {
                    CompletableFuture.runAsync(this::syncAllQuietly);
                    return "full sync queued";
                }
                default -> { return "ignored " + event; }
            }
        } catch (Exception e) {
            return "bad payload: " + e.getMessage();
        }
    }
}
