package com.codegraph.app.knowledge;

import com.codegraph.core.knowledge.*;
import com.codegraph.core.model.CodeElement;
import com.codegraph.core.model.Repository.IndexingStatus;
import com.codegraph.core.model.Repository;
import com.codegraph.core.model.Visibility;
import com.codegraph.deps.graph.DependencyGraph;
import com.codegraph.indexer.IndexerService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The concept layer. Repo cards come from the repo-atlas dossiers (imported whenever
 * the atlas repo is indexed); overviews, concept drawings and concepts come from
 * Claude sessions through the work queue; architecture comes from the code graph.
 *
 * <p>Everything is stored as repo facts, so it carries the visibility of its repo.
 */
public class KnowledgeServiceImpl implements KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeServiceImpl.class);

    static final String CARD = "card";
    static final String KNOWLEDGE = "knowledge";
    static final String FILEGRAPH = "filegraph";
    static final String SYNTHESIS = "synthesis";
    static final Duration LEASE = Duration.ofMinutes(30);

    static final int MAX_OVERVIEW = 20_000;
    static final int MAX_DIAGRAM = 12_000;
    static final int MAX_CONCEPTS = 40;
    private static final Pattern MERMAID_START = Pattern.compile(
            "^\\s*(%%.*\\n\\s*)*(graph|flowchart|sequenceDiagram|classDiagram|stateDiagram(-v2)?|erDiagram|journey|"
                    + "gantt|mindmap|timeline|C4Context|C4Container|C4Component|block-beta|architecture-beta)\\b");

    private final IndexerService indexer;
    private final Supplier<DependencyGraph> deps;
    private final String atlasRepoId;
    private final Path atlasDir;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Map<String, RepoCard> cards = new ConcurrentHashMap<>();
    private final Map<String, RepoKnowledge> knowledge = new ConcurrentHashMap<>();
    private volatile Map<String, RepoCard> atlasCards = Map.of();
    private volatile Map<String, String> synthesis = Map.of();
    private volatile List<ConceptIndexEntry> conceptIndex = List.of();
    private final Map<String, Lease> leases = new ConcurrentHashMap<>();
    private final Map<String, FileGraph> graphCache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, FileGraph> e) {
            return size() > 48;
        }
    });

    /** Repos whose file graph must be rebuilt: re-indexed, or a dependency was (new cross-repo links). */
    private final Set<String> stale = ConcurrentHashMap.newKeySet();

    private record Lease(String who, Instant until) {}

    /**
     * @param atlasRepoId the repo id of the repo-atlas checkout in the registry (indexed like any repo)
     * @param atlasDir    an explicit repo-atlas directory, used when the atlas isn't a registered repo
     */
    public KnowledgeServiceImpl(IndexerService indexer, Supplier<DependencyGraph> deps, String atlasRepoId, Path atlasDir) {
        this.indexer = indexer;
        this.deps = deps;
        this.atlasRepoId = atlasRepoId;
        this.atlasDir = atlasDir;
    }

    // ---------------------------------------------------------------- lifecycle

    public synchronized void load() {
        indexer.listRepoFacts(CARD).forEach((repo, json) -> read(json, RepoCard.class).ifPresent(c -> cards.put(repo, c)));
        indexer.listRepoFacts(KNOWLEDGE).forEach((repo, json) -> read(json, RepoKnowledge.class).ifPresent(k -> knowledge.put(repo, k)));
        if (atlasRepoId != null) {
            indexer.getRepoFact(atlasRepoId, SYNTHESIS).flatMap(j -> read(j, new TypeReference<Map<String, String>>() {}))
                    .ifPresent(m -> synthesis = m);
        }
        Path dir = atlasPath();
        if (dir != null) importAtlas(dir);
        rebuildConceptIndex();
        log.info("Knowledge: {} repo cards, {} repos with session knowledge, {} concepts",
                cards.size(), knowledge.size(), conceptIndex.size());
    }

    /** Index listener: re-import the atlas when it changes; drop cached structure of the repo and its dependents. */
    public void onRepoIndexed(String repoId) {
        stale.add(repoId);
        try {
            stale.addAll(deps.get().closure(repoId, 1, true, true, r -> true).keySet());
        } catch (Exception e) {
            log.debug("No dependents for {}: {}", repoId, e.getMessage());
        }
        if (repoId.equals(atlasRepoId)) {
            Path dir = atlasPath();
            if (dir != null) importAtlas(dir);
        } else if (!cards.containsKey(repoId) && atlasCards.containsKey(repoId)) {
            saveCard(repoId, atlasCards.get(repoId));
        }
        rebuildConceptIndex();
    }

    /** Builds missing file graphs in the background so the first visit to a repo is fast. */
    public void warmUp() {
        Thread t = new Thread(() -> {
            long start = System.currentTimeMillis();
            int built = 0;
            for (Repository r : indexer.listRepositories()) {
                if (r.getStatus() != IndexingStatus.READY) continue;
                try {
                    if (indexer.getRepoFact(r.getId(), FILEGRAPH).isEmpty()) {
                        fileGraph(r);
                        built++;
                    }
                } catch (Exception e) {
                    log.warn("Architecture for {} failed: {}", r.getId(), e.getMessage());
                }
            }
            log.info("Architecture warm-up: built {} file graphs in {} s", built, (System.currentTimeMillis() - start) / 1000);
        }, "knowledge-warmup");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private Path atlasPath() {
        if (atlasDir != null && AtlasImporter.looksLikeAtlas(atlasDir)) return atlasDir;
        if (atlasRepoId == null) return null;
        return indexer.getRepository(atlasRepoId).map(Repository::getRootPath).map(Path::of)
                .filter(AtlasImporter::looksLikeAtlas).orElse(null);
    }

    synchronized void importAtlas(Path dir) {
        Map<String, RepoCard> read = AtlasImporter.readDossiers(dir);
        atlasCards = read;
        int saved = 0;
        for (Repository r : indexer.listRepositories()) {
            RepoCard c = read.get(r.getId());
            if (c == null) c = read.get(r.getName());
            if (c == null) continue;
            RepoCard card = new RepoCard(r.getId(), c.family(), c.category(), c.status(), c.maturity(), c.purpose(),
                    c.languages(), c.tech(), c.tags(), c.related(), c.supersededBy(), c.analyzed(), c.analyzedCommit(),
                    c.sections(), c.source());
            if (!card.equals(cards.get(r.getId()))) {
                saveCard(r.getId(), card);
                saved++;
            }
        }
        Map<String, String> syn = AtlasImporter.readSynthesis(dir);
        if (!syn.isEmpty() && atlasRepoId != null) {
            synthesis = syn;
            write(atlasRepoId, SYNTHESIS, null, syn);
        }
        log.info("repo-atlas: {} dossiers, {} cards updated, {} synthesis docs", read.size(), saved, syn.size());
        rebuildConceptIndex();
    }

    private void saveCard(String repoId, RepoCard card) {
        cards.put(repoId, card);
        write(repoId, CARD, card.analyzedCommit(), card);
    }

    // ---------------------------------------------------------------- reads

    @Override
    public List<RepoCard> cards(Predicate<String> visible) {
        return cards.values().stream().filter(c -> visible.test(c.repoId()))
                .map(c -> c.visibleTo(visible))
                .sorted(Comparator.comparing(RepoCard::repoId)).toList();
    }

    @Override
    public Optional<RepoCard> card(String repoId) {
        return Optional.ofNullable(cards.get(repoId));
    }

    @Override
    public Optional<RepoKnowledge> knowledge(String repoId) {
        return Optional.ofNullable(knowledge.get(repoId));
    }

    @Override
    public Architecture architecture(String repoId, String path, Predicate<String> visible) {
        Repository repo = indexer.getRepository(repoId).orElseThrow(() -> new NoSuchElementException("Unknown repo: " + repoId));
        return ArchitectureBuilder.fold(fileGraph(repo), path, visible);
    }

    FileGraph fileGraph(Repository repo) {
        String commit = ArchitectureBuilder.commitOf(repo);
        if (!stale.contains(repo.getId())) {
            FileGraph cached = graphCache.get(repo.getId());
            if (cached != null && commit.equals(cached.commit())) return cached;
            Optional<FileGraph> stored = indexer.getRepoFact(repo.getId(), FILEGRAPH).flatMap(j -> read(j, FileGraph.class));
            if (stored.isPresent() && commit.equals(stored.get().commit())) {
                graphCache.put(repo.getId(), stored.get());
                return stored.get();
            }
        }
        stale.remove(repo.getId());
        long start = System.currentTimeMillis();
        FileGraph g = ArchitectureBuilder.build(indexer, repo);
        write(repo.getId(), FILEGRAPH, commit, g);
        graphCache.put(repo.getId(), g);
        log.info("Architecture for {}: {} files, {} edges, {} types in {} ms", repo.getId(), g.files().size(),
                g.edges().size(), g.types().size(), System.currentTimeMillis() - start);
        return g;
    }

    @Override
    public List<ConceptIndexEntry> searchConcepts(String query, String kind, Predicate<String> visible, int limit) {
        String q = query == null ? "" : query.toLowerCase().strip();
        String[] terms = q.isEmpty() ? new String[0] : q.split("\\s+");
        record Scored(ConceptIndexEntry e, double score) {}
        List<Scored> out = new ArrayList<>();
        for (ConceptIndexEntry e : conceptIndex) {
            if (kind != null && !kind.isBlank() && !kind.equalsIgnoreCase(e.kind()) && !kind.equalsIgnoreCase(e.source())) continue;
            ConceptIndexEntry v = scope(e, visible);
            if (v == null) continue;
            double score = v.repos().size() * 0.5 + ("session".equals(v.source()) ? 3 : 0);
            if (terms.length > 0) {
                String name = v.name().toLowerCase();
                String text = name + " " + v.slug() + " " + Objects.toString(v.summary(), "").toLowerCase() + " "
                        + v.repos().stream().map(u -> Objects.toString(u.summary(), "")).reduce("", String::concat).toLowerCase();
                double match = 0;
                for (String t : terms) {
                    if (name.equals(q)) match += 100;
                    else if (name.startsWith(t)) match += 30;
                    else if (name.contains(t)) match += 20;
                    else if (text.contains(t)) match += 5;
                    else { match = 0; break; }
                }
                if (match == 0) continue;
                score += match;
            }
            out.add(new Scored(v, score));
        }
        out.sort(Comparator.comparingDouble(Scored::score).reversed());
        return out.stream().limit(limit <= 0 ? 50 : limit).map(Scored::e).toList();
    }

    @Override
    public Optional<ConceptIndexEntry> concept(String slug, Predicate<String> visible) {
        return conceptIndex.stream().filter(e -> e.slug().equals(slug)).findFirst().map(e -> scope(e, visible));
    }

    private static ConceptIndexEntry scope(ConceptIndexEntry e, Predicate<String> visible) {
        List<ConceptIndexEntry.Usage> repos = e.repos().stream().filter(u -> visible.test(u.repoId())).toList();
        if (repos.isEmpty()) return null;
        return repos.size() == e.repos().size() ? e
                : new ConceptIndexEntry(e.slug(), e.name(), e.kind(), e.source(), e.summary(), repos);
    }

    @Override
    public Map<String, String> synthesis(Predicate<String> visible) {
        if (atlasRepoId == null || !visible.test(atlasRepoId)) return Map.of();
        return synthesis;
    }

    /** Concepts by slug: session-written ones, then repo-atlas tags and families. */
    synchronized void rebuildConceptIndex() {
        Map<String, Builder> bySlug = new TreeMap<>();
        knowledge.values().forEach(k -> {
            if (k.concepts() == null) return;
            for (Concept c : k.concepts()) {
                Builder b = bySlug.computeIfAbsent(c.slug(), s -> new Builder(s, c.name(), c.kind(), "session"));
                b.session = true;
                if (b.summary == null) b.summary = c.summary();
                b.add(new ConceptIndexEntry.Usage(k.repoId(), c.summary(), c.howToUse(), c.anchors()));
            }
        });
        cards.values().forEach(card -> {
            if (card.tags() != null) for (String tag : card.tags()) {
                String slug = Concept.slugOf(tag);
                if (slug.isEmpty()) continue;
                Builder b = bySlug.computeIfAbsent(slug, s -> new Builder(s, tag, "tag", "tag"));
                b.add(new ConceptIndexEntry.Usage(card.repoId(), card.purpose(), null, null));
            }
            if (card.family() != null) {
                String slug = "family-" + Concept.slugOf(card.family());
                Builder b = bySlug.computeIfAbsent(slug, s -> new Builder(s, card.family(), "family", "family"));
                b.add(new ConceptIndexEntry.Usage(card.repoId(), card.purpose(), null, null));
            }
        });
        conceptIndex = bySlug.values().stream().map(Builder::build).toList();
    }

    private static final class Builder {
        final String slug, name, kind, source;
        String summary;
        boolean session;
        final Map<String, ConceptIndexEntry.Usage> repos = new LinkedHashMap<>();

        Builder(String slug, String name, String kind, String source) {
            this.slug = slug;
            this.name = name;
            this.kind = kind;
            this.source = source;
        }

        void add(ConceptIndexEntry.Usage u) {
            repos.merge(u.repoId(), u, (a, b) -> a.anchors() != null || a.howToUse() != null ? a : b);
        }

        ConceptIndexEntry build() {
            return new ConceptIndexEntry(slug, name, kind, session ? "session" : source, summary, List.copyOf(repos.values()));
        }
    }

    // ---------------------------------------------------------------- work queue

    @Override
    public List<WorkItem> pendingWork(Predicate<String> visible, int limit) {
        List<WorkItem> out = new ArrayList<>();
        List<Repository> repos = new ArrayList<>(indexer.listRepositories());
        repos.sort(Comparator.comparingInt(Repository::getElementCount).reversed());
        for (Repository r : repos) {
            if (!visible.test(r.getId()) || r.getStatus() != IndexingStatus.READY || r.getElementCount() == 0) continue;
            if (r.getId().equals(atlasRepoId)) continue;
            Lease l = leases.get(r.getId());
            if (l != null && l.until().isAfter(Instant.now())) continue;
            RepoKnowledge k = knowledge.get(r.getId());
            String commit = ArchitectureBuilder.commitOf(r);
            if (k == null) out.add(new WorkItem(r.getId(), "no concepts yet", commit, null));
            else if (k.commit() != null && !k.commit().equals(commit)) out.add(new WorkItem(r.getId(), "code changed since", commit, k.commit()));
        }
        // undescribed repos first, then stale ones
        out.sort(Comparator.comparing((WorkItem w) -> w.describedCommit() != null));
        return out.stream().limit(limit <= 0 ? 20 : limit).toList();
    }

    @Override
    public Optional<Map<String, Object>> claim(String repoId, String who, Predicate<String> visible) {
        String id = repoId;
        if (id == null || id.isBlank()) {
            List<WorkItem> next = pendingWork(visible, 1);
            if (next.isEmpty()) return Optional.empty();
            id = next.get(0).repoId();
        }
        if (!visible.test(id)) return Optional.empty();
        Repository repo = indexer.getRepository(id).orElse(null);
        if (repo == null) return Optional.empty();
        leases.put(id, new Lease(who, Instant.now().plus(LEASE)));
        return Optional.of(bundle(repo, visible));
    }

    /** Everything a session needs to describe a repo without reading it file by file. */
    Map<String, Object> bundle(Repository repo, Predicate<String> visible) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("repo", Map.of(
                "id", repo.getId(),
                "visibility", repo.getVisibility() == null ? "PRIVATE" : repo.getVisibility().name(),
                "languages", repo.getLanguages() == null ? List.of() : repo.getLanguages(),
                "elements", repo.getElementCount(),
                "files", repo.getFileCount(),
                "url", Objects.toString(repo.getHtmlUrl(), ""),
                "commit", ArchitectureBuilder.commitOf(repo)));
        // Which other repos the write-up may name: a public repo's text is public
        boolean isPublic = repo.getVisibility() == Visibility.PUBLIC;
        Predicate<String> mentionable = isPublic ? r -> indexer.getRepository(r).map(Repository::isPublic).orElse(false) : visible;
        RepoCard card = cards.get(repo.getId());
        if (card != null) b.put("card", card.visibleTo(mentionable));
        Architecture top = architecture(repo.getId(), "", mentionable);
        b.put("architecture", top);
        List<Map<String, Object>> deeper = new ArrayList<>();
        top.groups().stream().filter(Architecture.Group::hasChildren)
                .sorted(Comparator.comparingInt(Architecture.Group::elements).reversed()).limit(4)
                .forEach(g -> {
                    Architecture a = architecture(repo.getId(), g.id(), mentionable);
                    deeper.add(Map.of("path", a.path(), "groups", a.groups().stream().map(Architecture.Group::id).toList(),
                            "keyTypes", a.keyTypes().stream().map(Architecture.KeyType::qualifiedName).toList()));
                });
        b.put("architectureDetail", deeper);
        try {
            DependencyGraph g = deps.get();
            b.put("dependsOn", g.dependenciesOf(repo.getId(), mentionable).stream().map(DependencyGraph.RepoEdge::toRepo).distinct().toList());
            b.put("usedBy", g.dependentsOf(repo.getId(), mentionable).stream().map(DependencyGraph.RepoEdge::fromRepo).distinct().toList());
        } catch (Exception e) {
            log.debug("No dependency info for {}: {}", repo.getId(), e.getMessage());
        }
        readme(repo).ifPresent(r -> b.put("readme", r));
        RepoKnowledge previous = knowledge.get(repo.getId());
        if (previous != null) b.put("previous", previous);
        b.put("instructions", instructions(repo, isPublic));
        return b;
    }

    private Optional<String> readme(Repository repo) {
        if (repo.getRootPath() == null) return Optional.empty();
        for (String name : List.of("README.md", "readme.md", "README.MD", "README")) {
            Path p = Path.of(repo.getRootPath(), name);
            try {
                if (Files.isRegularFile(p)) {
                    String s = Files.readString(p);
                    return Optional.of(s.length() > 8000 ? s.substring(0, 8000) + "\n…(truncated)" : s);
                }
            } catch (Exception e) {
                log.debug("Cannot read {}: {}", p, e.getMessage());
            }
        }
        return Optional.empty();
    }

    static String instructions(Repository repo, boolean isPublic) {
        return """
            Describe this repo for engineers and agents looking for existing infrastructure and techniques.
            Use the bundle first, then the code-graph tools (search_code, get_element, get_callers, repo_architecture)
            for anything you need to confirm. Submit with submit_concepts:
              overview  Markdown, 150-600 words: what the repo is for, its main parts and how they fit, how to
                        use it from another project (or why not to), and notable techniques.
              diagram   One Mermaid diagram (flowchart LR/TB preferred) of the main components and the flows
                        between them, labelled with real module or class names. No styling or click handlers.
              concepts  5-20 items: {name, kind, summary, howToUse?, details?, anchors:[{qualifiedName|path, label?}]}.
                        kind is one of capability, technique, pattern, api, infrastructure, domain.
                        Name concepts the way someone would search for them ("vector search", "JWT auth",
                        "durable scheduler"); reuse an existing concept name when it is the same idea
                        (search_concepts shows them). Anchor each concept to the classes, functions or files
                        that implement it.
            Describe only this repo.%s
            """.formatted(isPublic
                ? " It is public: name other repos only if they appear in this bundle (card.related, dependsOn, usedBy)."
                : "");
    }

    @Override
    public SubmitResult submit(String repoId, Submission s, String who) {
        Repository repo = indexer.getRepository(repoId).orElseThrow(() -> new NoSuchElementException("Unknown repo: " + repoId));
        List<String> warnings = new ArrayList<>();
        String overview = trim(s.overview(), MAX_OVERVIEW, "overview", warnings);
        String diagram = trim(s.diagram(), MAX_DIAGRAM, "diagram", warnings);
        if (diagram != null) {
            diagram = diagram.strip().replaceAll("^```(mermaid)?\\s*", "").replaceAll("\\s*```$", "");
            if (!MERMAID_START.matcher(diagram).find()) {
                throw new IllegalArgumentException("diagram must be Mermaid source starting with a diagram type (e.g. 'flowchart LR')");
            }
        }
        List<Concept> in = s.concepts() == null ? List.of() : s.concepts();
        if (overview == null && diagram == null && in.isEmpty()) {
            throw new IllegalArgumentException("Nothing to submit: give an overview, a diagram or concepts");
        }
        if (in.size() > MAX_CONCEPTS) {
            warnings.add("Only the first " + MAX_CONCEPTS + " concepts were kept");
            in = in.subList(0, MAX_CONCEPTS);
        }
        int resolved = 0, unresolved = 0;
        Map<String, Concept> concepts = new LinkedHashMap<>();
        for (Concept c : in) {
            if (c.name() == null || c.name().isBlank()) {
                warnings.add("Skipped a concept with no name");
                continue;
            }
            String slug = Concept.slugOf(c.name());
            String kind = c.kind() != null && Concept.KINDS.contains(c.kind().toLowerCase()) ? c.kind().toLowerCase() : "capability";
            List<Anchor> anchors = new ArrayList<>();
            if (c.anchors() != null) for (Anchor a : c.anchors()) {
                Anchor r = resolve(repoId, a);
                if (r.elementId() != null) resolved++;
                else unresolved++;
                anchors.add(r);
            }
            concepts.put(slug, new Concept(slug, c.name().strip(), kind, c.summary(), c.details(), c.howToUse(), anchors, c.related()));
        }
        if (repo.getVisibility() == Visibility.PUBLIC) {
            String text = String.join("\n", Objects.toString(overview, ""), Objects.toString(diagram, ""),
                    concepts.values().toString());
            for (Repository other : indexer.listRepositories()) {
                if (other.isPublic() || other.getId().length() < 5) continue;
                if (Pattern.compile("\\b" + Pattern.quote(other.getId()) + "\\b").matcher(text).find()) {
                    warnings.add("This repo is public but the text names private repo '" + other.getId() + "'; resubmit without it");
                }
            }
        }
        RepoKnowledge k = new RepoKnowledge(repoId, overview, diagram, List.copyOf(concepts.values()),
                ArchitectureBuilder.commitOf(repo), who, Instant.now());
        knowledge.put(repoId, k);
        write(repoId, KNOWLEDGE, k.commit(), k);
        leases.remove(repoId);
        rebuildConceptIndex();
        return new SubmitResult(repoId, concepts.size(), resolved, unresolved, warnings);
    }

    private Anchor resolve(String repoId, Anchor a) {
        if (a.elementId() != null) {
            Optional<CodeElement> e = indexer.getElementById(a.elementId()).filter(x -> repoId.equals(x.getRepoId()));
            if (e.isPresent()) return anchorFor(a, e.get());
        }
        if (a.qualifiedName() != null && !a.qualifiedName().isBlank()) {
            List<CodeElement> hits = indexer.getElementsByQualifiedName(repoId, a.qualifiedName().strip());
            if (!hits.isEmpty()) {
                CodeElement best = hits.stream().filter(e -> ArchitectureBuilder.TYPE_KINDS.contains(e.getElementType()))
                        .findFirst().orElse(hits.get(0));
                return anchorFor(a, best);
            }
        }
        if (a.path() != null && !a.path().isBlank()) {
            List<CodeElement> els = indexer.getElementsByFile(repoId, a.path().strip());
            Optional<CodeElement> file = els.stream().filter(e -> e.getElementType().name().equals("FILE")).findFirst();
            if (file.isPresent()) return anchorFor(a, file.get());
        }
        return new Anchor(a.label(), a.qualifiedName(), a.path(), null, null);
    }

    private static Anchor anchorFor(Anchor a, CodeElement e) {
        return new Anchor(a.label(), e.getQualifiedName() != null ? e.getQualifiedName() : a.qualifiedName(),
                e.getFilePath(), e.getId(), e.getElementType().name());
    }

    private static String trim(String s, int max, String what, List<String> warnings) {
        if (s == null || s.isBlank()) return null;
        if (s.length() <= max) return s.strip();
        warnings.add(what + " cut to " + max + " characters");
        return s.substring(0, max);
    }

    // ---------------------------------------------------------------- storage

    private void write(String repoId, String kind, String commit, Object value) {
        try {
            indexer.saveRepoFact(repoId, kind, commit, mapper.writeValueAsString(value));
        } catch (Exception e) {
            log.warn("Cannot store {} for {}: {}", kind, repoId, e.getMessage());
        }
    }

    private <T> Optional<T> read(String json, Class<T> type) {
        try {
            return Optional.of(mapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("Unreadable {}: {}", type.getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    private <T> Optional<T> read(String json, TypeReference<T> type) {
        try {
            return Optional.of(mapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("Unreadable fact: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
