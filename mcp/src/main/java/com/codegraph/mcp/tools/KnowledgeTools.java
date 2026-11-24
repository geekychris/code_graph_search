package com.codegraph.mcp.tools;

import com.codegraph.core.knowledge.*;
import com.codegraph.core.security.RequestContext;
import com.codegraph.mcp.McpTool;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Tools over the concept layer: what repos are for, what concepts they embody and
 * where, how a repo is structured, and the queue through which Claude sessions write
 * overviews, concept drawings and concepts (the service never calls a model itself).
 */
public class KnowledgeTools {

    private KnowledgeTools() {}

    public record Context(KnowledgeService knowledge, Predicate<String> visible, ObjectMapper mapper) {}

    private static Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", props);
        s.put("required", required);
        return s;
    }

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null || v.toString().isBlank() ? null : v.toString();
    }

    private static int num(Map<String, Object> args, String key, int dflt) {
        Object v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        try { return v == null ? dflt : Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return dflt; }
    }

    private static String unknown(String repo) {
        return "Unknown repository '" + repo + "'. Check the id with list_repos.";
    }

    // -------------------------------------------------------------------------

    public static class RepoOverview implements McpTool {
        private final Context ctx;
        public RepoOverview(Context ctx) { this.ctx = ctx; }

        @Override public String getName() { return "repo_overview"; }

        @Override
        public String getDescription() {
            return "High-level picture of one repository: purpose, family, status, tech and tags (from the repo atlas), "
                    + "the session-written overview and concept drawing (Mermaid), its concepts with code anchors, "
                    + "and its design, shortcomings and opportunities. Start here before reading a repo's code.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return schema(Map.of("repo", prop("string", "Repository id")), List.of("repo"));
        }

        @Override
        public Object execute(Map<String, Object> args) {
            String repo = str(args, "repo");
            if (repo == null || !ctx.visible().test(repo)) return unknown(repo);
            Optional<RepoCard> card = ctx.knowledge().card(repo);
            Optional<RepoKnowledge> k = ctx.knowledge().knowledge(repo);
            if (card.isEmpty() && k.isEmpty()) {
                return "No overview for '" + repo + "' yet. Use repo_architecture for its structure, or "
                        + "claim_concept_work to write one.";
            }
            StringBuilder sb = new StringBuilder("# ").append(repo).append("\n\n");
            card.ifPresent(c -> {
                if (c.purpose() != null) sb.append(c.purpose()).append("\n\n");
                sb.append("Family: ").append(c.family()).append(" · category: ").append(c.category())
                        .append(" · status: ").append(c.status());
                if (c.maturity() != null) sb.append(" · maturity ").append(c.maturity()).append("/5");
                sb.append("\n");
                if (c.tech() != null && !c.tech().isEmpty()) sb.append("Tech: ").append(String.join(", ", c.tech())).append("\n");
                if (c.tags() != null && !c.tags().isEmpty()) sb.append("Tags: ").append(String.join(", ", c.tags())).append("\n");
                List<String> related = c.related() == null ? List.of() : c.related().stream().filter(ctx.visible()).toList();
                if (!related.isEmpty()) sb.append("Related: ").append(String.join(", ", related)).append("\n");
                sb.append("\n");
            });
            k.ifPresent(x -> {
                if (x.overview() != null) sb.append("## Overview\n").append(x.overview()).append("\n\n");
                if (x.diagram() != null) sb.append("## Concept drawing\n```mermaid\n").append(x.diagram()).append("\n```\n\n");
                if (x.concepts() != null && !x.concepts().isEmpty()) {
                    sb.append("## Concepts\n");
                    for (Concept c : x.concepts()) {
                        sb.append("- **").append(c.name()).append("** (").append(c.kind()).append(", `").append(c.slug()).append("`)");
                        if (c.summary() != null) sb.append(": ").append(c.summary());
                        sb.append("\n");
                        if (c.howToUse() != null) sb.append("  - How to use: ").append(c.howToUse()).append("\n");
                        if (c.anchors() != null) for (Anchor a : c.anchors()) {
                            sb.append("  - ").append(a.qualifiedName() != null ? "`" + a.qualifiedName() + "`" : "`" + a.path() + "`");
                            if (a.elementId() != null) sb.append(" (id ").append(a.elementId()).append(")");
                            sb.append("\n");
                        }
                    }
                    sb.append("\n");
                }
                sb.append("_Written by ").append(x.author()).append(" for commit ").append(x.commit()).append("._\n\n");
            });
            card.ifPresent(c -> {
                if (c.sections() == null) return;
                for (String s : List.of("Design", "Technology", "State & maturity", "Shortcomings", "Opportunities")) {
                    String text = c.sections().get(s);
                    if (text != null && !text.isBlank()) sb.append("## ").append(s).append("\n").append(text).append("\n\n");
                }
            });
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------

    public static class RepoArchitecture implements McpTool {
        private final Context ctx;
        public RepoArchitecture(Context ctx) { this.ctx = ctx; }

        @Override public String getName() { return "repo_architecture"; }

        @Override
        public String getDescription() {
            return "The structure of a repository derived from its code graph, one level at a time: the modules, "
                    + "packages or directories under a path, how many references flow between them, which other repos "
                    + "it calls into, and its most-referenced (load-bearing) types. Call again with a group's path to "
                    + "drill down.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("repo", prop("string", "Repository id"));
            p.put("path", prop("string", "Group path to look inside (from a previous result); omit for the top level"));
            return schema(p, List.of("repo"));
        }

        @Override
        public Object execute(Map<String, Object> args) {
            String repo = str(args, "repo");
            if (repo == null || !ctx.visible().test(repo)) return unknown(repo);
            Architecture a = ctx.knowledge().architecture(repo, str(args, "path"), ctx.visible());
            StringBuilder sb = new StringBuilder("## ").append(repo).append(a.path().isEmpty() ? "" : " / " + a.path()).append("\n\n");
            sb.append("### Parts (").append(a.groups().size()).append(")\n");
            a.groups().stream().sorted(Comparator.comparingInt(Architecture.Group::elements).reversed()).forEach(g ->
                    sb.append("- `").append(g.id()).append("`").append(g.hasChildren() ? "/" : "")
                            .append(": ").append(g.files()).append(" files, ").append(g.elements()).append(" elements, ")
                            .append(g.types()).append(" types; refs in ").append(g.inbound()).append(", out ").append(g.outbound())
                            .append("\n"));
            if (!a.edges().isEmpty()) {
                sb.append("\n### Strongest links\n");
                a.edges().stream().sorted(Comparator.comparingInt(Architecture.Edge::weight).reversed()).limit(25).forEach(e ->
                        sb.append("- ").append(e.from()).append(" → ").append(e.to()).append(": ").append(e.weight())
                                .append(" ").append(e.kinds()).append("\n"));
            }
            if (!a.keyTypes().isEmpty()) {
                sb.append("\n### Load-bearing types\n");
                a.keyTypes().forEach(t -> sb.append("- `").append(t.qualifiedName()).append("` (").append(t.kind().toLowerCase())
                        .append(", ").append(t.inbound()).append(" refs in repo")
                        .append(t.crossRepoInbound() > 0 ? ", " + t.crossRepoInbound() + " from other repos" : "")
                        .append(", id ").append(t.id()).append(")\n"));
            }
            if (!a.external().isEmpty()) {
                sb.append("\n### Calls into other repos\n");
                a.external().forEach(x -> sb.append("- **").append(x.repoId()).append("**: ").append(x.weight())
                        .append(" refs, e.g. ").append(String.join(", ", x.targets())).append("\n"));
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------

    public static class SearchConcepts implements McpTool {
        private final Context ctx;
        public SearchConcepts(Context ctx) { this.ctx = ctx; }

        @Override public String getName() { return "search_concepts"; }

        @Override
        public String getDescription() {
            return "Find concepts across all repositories: capabilities, techniques, patterns, APIs and infrastructure "
                    + "(\"vector search\", \"JWT auth\", \"durable scheduler\"), with the repos that implement each. "
                    + "Use it to find existing infrastructure before building something new.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("query", prop("string", "Words to match; omit to list the best-covered concepts"));
            p.put("kind", prop("string", "Filter: capability, technique, pattern, api, infrastructure, domain, tag or family"));
            p.put("limit", prop("integer", "Max results (default 20)"));
            return schema(p, List.of());
        }

        @Override
        public Object execute(Map<String, Object> args) {
            List<ConceptIndexEntry> hits = ctx.knowledge().searchConcepts(str(args, "query"), str(args, "kind"),
                    ctx.visible(), num(args, "limit", 20));
            if (hits.isEmpty()) return "No concepts match. Try search_code for code-level matches.";
            StringBuilder sb = new StringBuilder();
            for (ConceptIndexEntry e : hits) {
                sb.append("- **").append(e.name()).append("** (`").append(e.slug()).append("`, ").append(e.kind())
                        .append(", ").append(e.repos().size()).append(" repo").append(e.repos().size() == 1 ? "" : "s").append(")");
                if (e.summary() != null) sb.append(": ").append(e.summary());
                sb.append("\n  repos: ").append(e.repos().stream().map(ConceptIndexEntry.Usage::repoId).limit(12)
                        .collect(Collectors.joining(", "))).append(e.repos().size() > 12 ? ", …" : "").append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------

    public static class GetConcept implements McpTool {
        private final Context ctx;
        public GetConcept(Context ctx) { this.ctx = ctx; }

        @Override public String getName() { return "get_concept"; }

        @Override
        public String getDescription() {
            return "One concept across the portfolio: each repo that implements it, how, how to use it, and the code "
                    + "that anchors it (element ids you can pass to get_element or get_callers).";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return schema(Map.of("slug", prop("string", "Concept slug from search_concepts")), List.of("slug"));
        }

        @Override
        public Object execute(Map<String, Object> args) {
            String slug = str(args, "slug");
            Optional<ConceptIndexEntry> e = slug == null ? Optional.empty() : ctx.knowledge().concept(slug, ctx.visible());
            if (e.isEmpty()) return "No concept '" + slug + "'. Use search_concepts.";
            ConceptIndexEntry c = e.get();
            StringBuilder sb = new StringBuilder("# ").append(c.name()).append("\n");
            sb.append(c.kind()).append(" · ").append(c.source()).append("\n\n");
            if (c.summary() != null) sb.append(c.summary()).append("\n\n");
            for (ConceptIndexEntry.Usage u : c.repos()) {
                sb.append("## ").append(u.repoId()).append("\n");
                if (u.summary() != null) sb.append(u.summary()).append("\n");
                if (u.howToUse() != null) sb.append("How to use: ").append(u.howToUse()).append("\n");
                if (u.anchors() != null) for (Anchor a : u.anchors()) {
                    sb.append("- ").append(a.qualifiedName() != null ? "`" + a.qualifiedName() + "`" : "`" + a.path() + "`");
                    if (a.elementId() != null) sb.append(" (id ").append(a.elementId()).append(")");
                    sb.append("\n");
                }
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------

    public static class ConceptWorkQueue implements McpTool {
        private final Context ctx;
        public ConceptWorkQueue(Context ctx) { this.ctx = ctx; }

        @Override public String getName() { return "concept_work_queue"; }

        @Override
        public String getDescription() {
            return "Repos waiting for a Claude session to write their overview, concept drawing and concepts: "
                    + "ones never described, then ones whose code changed since. Claim one with claim_concept_work.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return schema(Map.of("limit", prop("integer", "Max items (default 20)")), List.of());
        }

        @Override
        public Object execute(Map<String, Object> args) {
            var items = ctx.knowledge().pendingWork(ctx.visible(), num(args, "limit", 20));
            if (items.isEmpty()) return "Nothing waiting: every visible repo is described at its current commit.";
            return items.stream().map(w -> "- " + w.repoId() + ": " + w.reason()).collect(Collectors.joining("\n"));
        }
    }

    // -------------------------------------------------------------------------

    public static class ClaimConceptWork implements McpTool {
        private final Context ctx;
        public ClaimConceptWork(Context ctx) { this.ctx = ctx; }

        @Override public String getName() { return "claim_concept_work"; }

        @Override
        public String getDescription() {
            return "Claim a repo to describe (the given one, or the next in concept_work_queue) for 30 minutes. Returns "
                    + "a JSON bundle: the repo card, its architecture, key types, dependencies, README, any previous "
                    + "write-up, and instructions. Then submit with submit_concepts. Needs an admin token.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return schema(Map.of("repo", prop("string", "Repository id; omit to take the next pending one")), List.of());
        }

        @Override
        public Object execute(Map<String, Object> args) throws Exception {
            var bundle = ctx.knowledge().claim(str(args, "repo"), RequestContext.current().name(), ctx.visible());
            if (bundle.isEmpty()) return "Nothing to claim.";
            return ctx.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(bundle.get());
        }
    }

    // -------------------------------------------------------------------------

    public static class SubmitConcepts implements McpTool {
        private final Context ctx;
        public SubmitConcepts(Context ctx) { this.ctx = ctx; }

        @Override public String getName() { return "submit_concepts"; }

        @Override
        public String getDescription() {
            return "Store a session's write-up of a repo: a Markdown overview, one Mermaid concept drawing and its "
                    + "concepts with code anchors. Replaces the previous write-up. Anchors given as qualified names "
                    + "or paths are resolved to code elements. Needs an admin token.";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            Map<String, Object> anchor = new LinkedHashMap<>();
            anchor.put("type", "object");
            anchor.put("properties", Map.of(
                    "qualifiedName", prop("string", "e.g. com.hitorro.util.scheduler.DurableScheduler"),
                    "path", prop("string", "File path relative to the repo root"),
                    "label", prop("string", "Optional short label")));
            Map<String, Object> concept = new LinkedHashMap<>();
            concept.put("type", "object");
            Map<String, Object> cp = new LinkedHashMap<>();
            cp.put("name", prop("string", "How someone would search for it"));
            cp.put("kind", prop("string", String.join(" | ", Concept.KINDS)));
            cp.put("summary", prop("string", "One or two sentences"));
            cp.put("howToUse", prop("string", "How another project would use it, or why not"));
            cp.put("details", prop("string", "Optional Markdown"));
            cp.put("anchors", Map.of("type", "array", "items", anchor));
            concept.put("properties", cp);
            concept.put("required", List.of("name", "kind", "summary"));

            Map<String, Object> p = new LinkedHashMap<>();
            p.put("repo", prop("string", "Repository id"));
            p.put("overview", prop("string", "Markdown overview, 150-600 words"));
            p.put("diagram", prop("string", "Mermaid source, e.g. 'flowchart LR ...'"));
            p.put("concepts", Map.of("type", "array", "items", concept));
            return schema(p, List.of("repo"));
        }

        @Override
        public Object execute(Map<String, Object> args) {
            String repo = str(args, "repo");
            if (repo == null || !ctx.visible().test(repo)) return unknown(repo);
            List<Concept> concepts = new ArrayList<>();
            if (args.get("concepts") instanceof List<?> list) {
                for (Object o : list) concepts.add(ctx.mapper().convertValue(o, Concept.class));
            }
            var result = ctx.knowledge().submit(repo,
                    new KnowledgeService.Submission(str(args, "overview"), str(args, "diagram"), concepts),
                    RequestContext.current().name());
            StringBuilder sb = new StringBuilder("Stored ").append(result.concepts()).append(" concepts for ").append(repo)
                    .append("; anchors resolved ").append(result.anchorsResolved()).append(", unresolved ")
                    .append(result.anchorsUnresolved()).append(".");
            result.warnings().forEach(w -> sb.append("\nWarning: ").append(w));
            return sb.toString();
        }
    }
}
