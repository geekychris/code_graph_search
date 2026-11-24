package com.codegraph.deps.parse;

import com.codegraph.deps.model.Coordinate;
import com.codegraph.deps.model.Dependency;
import com.codegraph.deps.model.ModuleManifest;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads every {@code pom.xml} in a repository and resolves them together.
 *
 * <p>Resolution happens within the repository only (no remote repositories): parent
 * POMs found by {@code relativePath} or by coordinates, inherited groupId, version and
 * properties, {@code ${...}} interpolation including {@code project.*}, dependency
 * management from parents, and BOM imports whose POM is in the same repo. Anything not
 * resolvable stays with its declared text and a null resolved version.
 */
public final class MavenParser {

    private static final Pattern PROP = Pattern.compile("\\$\\{([^}]+)}");

    /** One POM as written, before inheritance. */
    record Pom(String path, String groupId, String artifactId, String version, String packaging,
               String parentGroupId, String parentArtifactId, String parentVersion, String parentRelativePath,
               Map<String, String> properties, List<RawDep> dependencies, List<RawDep> managed,
               List<String> modules) {
        String dir() {
            int i = path.lastIndexOf('/');
            return i < 0 ? "" : path.substring(0, i);
        }
    }

    record RawDep(String groupId, String artifactId, String version, String scope, String type) {}

    private MavenParser() {}

    /** Reads one POM; returns null (and adds a warning) if it is not well-formed XML. */
    static Pom read(Path file, String relPath, List<String> warnings) {
        try {
            Document doc = builder().parse(file.toFile());
            Element project = doc.getDocumentElement();
            Element parent = child(project, "parent");
            Map<String, String> props = new LinkedHashMap<>();
            Element propsEl = child(project, "properties");
            if (propsEl != null) {
                for (Element p : children(propsEl)) props.put(p.getTagName(), p.getTextContent().trim());
            }
            Element mgmt = child(project, "dependencyManagement");
            List<String> modules = new ArrayList<>();
            Element modulesEl = child(project, "modules");
            if (modulesEl != null) for (Element m : children(modulesEl, "module")) modules.add(m.getTextContent().trim());
            return new Pom(relPath,
                    text(project, "groupId"), text(project, "artifactId"), text(project, "version"),
                    Optional.ofNullable(text(project, "packaging")).orElse("jar"),
                    parent == null ? null : text(parent, "groupId"),
                    parent == null ? null : text(parent, "artifactId"),
                    parent == null ? null : text(parent, "version"),
                    parent == null ? null : text(parent, "relativePath"),
                    props,
                    deps(child(project, "dependencies")),
                    mgmt == null ? List.of() : deps(child(mgmt, "dependencies")),
                    modules);
        } catch (Exception e) {
            warnings.add(relPath + ": unreadable pom (" + e.getMessage() + ")");
            return null;
        }
    }

    /** Resolves all POMs of one repository against each other. */
    static List<ModuleManifest> resolve(List<Pom> poms) {
        Map<String, Pom> byPath = new HashMap<>();
        Map<String, Pom> byGa = new HashMap<>();
        for (Pom p : poms) byPath.put(p.path(), p);
        for (Pom p : poms) {
            String g = p.groupId() != null ? p.groupId() : p.parentGroupId();
            if (g != null && p.artifactId() != null) byGa.putIfAbsent(g + ":" + p.artifactId(), p);
        }
        MavenParser.Resolver r = new Resolver(byPath, byGa);
        List<ModuleManifest> out = new ArrayList<>();
        for (Pom p : poms) out.add(r.manifest(p));
        return out;
    }

    // -------------------------------------------------------------------------

    private static final class Resolver {
        private final Map<String, Pom> byPath;
        private final Map<String, Pom> byGa;

        Resolver(Map<String, Pom> byPath, Map<String, Pom> byGa) {
            this.byPath = byPath;
            this.byGa = byGa;
        }

        /** The in-repo parent of a POM, or null. */
        Pom parentOf(Pom p) {
            if (p.parentArtifactId() == null) return null;
            String rel = p.parentRelativePath() == null ? "../pom.xml" : p.parentRelativePath();
            if (!rel.isBlank()) {
                String candidate = normalize(p.dir(), rel.endsWith(".xml") ? rel : rel + "/pom.xml");
                Pom found = byPath.get(candidate);
                if (found != null && p.parentArtifactId().equals(found.artifactId())) return found;
            }
            return byGa.get(p.parentGroupId() + ":" + p.parentArtifactId());
        }

        List<Pom> chain(Pom p) {
            List<Pom> chain = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Pom cur = p; cur != null && seen.add(cur.path()); cur = parentOf(cur)) chain.add(cur);
            return chain;   // self first, root last
        }

        Map<String, String> properties(Pom p) {
            List<Pom> chain = chain(p);
            Map<String, String> props = new HashMap<>();
            for (int i = chain.size() - 1; i >= 0; i--) props.putAll(chain.get(i).properties());
            String g = groupId(p, chain), v = version(p, chain);
            if (g != null) { props.put("project.groupId", g); props.put("pom.groupId", g); }
            if (v != null) { props.put("project.version", v); props.put("pom.version", v); }
            if (p.artifactId() != null) props.put("project.artifactId", p.artifactId());
            if (p.parentVersion() != null) props.put("project.parent.version", p.parentVersion());
            if (p.parentGroupId() != null) props.put("project.parent.groupId", p.parentGroupId());
            return props;
        }

        String groupId(Pom p, List<Pom> chain) {
            for (Pom c : chain) {
                if (c.groupId() != null) return c.groupId();
                if (c.parentGroupId() != null) return c.parentGroupId();
            }
            return null;
        }

        String version(Pom p, List<Pom> chain) {
            for (Pom c : chain) {
                if (c.version() != null) return c.version();
                if (c.parentVersion() != null) return c.parentVersion();
            }
            return null;
        }

        /** groupId:artifactId → managed version, from the chain's dependencyManagement and in-repo BOMs. */
        Map<String, String> managed(Pom p, Map<String, String> props, Set<String> visiting) {
            Map<String, String> m = new HashMap<>();
            List<Pom> chain = chain(p);
            for (int i = chain.size() - 1; i >= 0; i--) {
                for (RawDep d : chain.get(i).managed()) {
                    String g = interpolate(d.groupId(), props), a = interpolate(d.artifactId(), props);
                    String v = interpolate(d.version(), props);
                    if ("import".equals(d.scope())) {
                        Pom bom = byGa.get(g + ":" + a);
                        if (bom != null && visiting.add(bom.path())) {
                            Map<String, String> bomProps = properties(bom);
                            managed(bom, bomProps, visiting).forEach(m::putIfAbsent);
                        }
                    } else if (v != null) {
                        m.put(g + ":" + a, v);
                    }
                }
            }
            return m;
        }

        ModuleManifest manifest(Pom p) {
            List<Pom> chain = chain(p);
            Map<String, String> props = properties(p);
            String g = interpolate(groupId(p, chain), props);
            String v = interpolate(version(p, chain), props);
            Map<String, String> managed = managed(p, props, new HashSet<>(Set.of(p.path())));

            List<Dependency> deps = new ArrayList<>();
            for (RawDep d : p.dependencies()) deps.add(dependency(d, props, managed));
            // BOM imports are dependencies too (scope import): they tie a repo to the BOM's producer
            for (RawDep d : p.managed()) {
                if ("import".equals(d.scope())) deps.add(dependency(d, props, managed));
            }

            Coordinate parent = p.parentArtifactId() == null ? null
                    : Coordinate.maven(interpolate(p.parentGroupId(), props), p.parentArtifactId());
            List<String> submodules = new ArrayList<>();
            for (String m : p.modules()) submodules.add(normalize(p.dir(), m));
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("packaging", p.packaging());
            if (p.parentVersion() != null) extra.put("parentVersion", interpolate(p.parentVersion(), props));
            Coordinate produces = g == null || p.artifactId() == null ? null
                    : Coordinate.maven(g, interpolate(p.artifactId(), props));
            return new ModuleManifest(p.path(), "maven", produces, v, parent, deps, submodules, extra);
        }

        private Dependency dependency(RawDep d, Map<String, String> props, Map<String, String> managed) {
            String g = interpolate(d.groupId(), props), a = interpolate(d.artifactId(), props);
            String declared = d.version();
            String resolved = interpolate(declared, props);
            boolean viaMgmt = false;
            if (resolved == null) {
                resolved = managed.get(g + ":" + a);
                viaMgmt = resolved != null;
            }
            if (resolved != null && resolved.contains("${")) resolved = null;
            String scope = d.scope() == null ? "compile" : d.scope();
            return new Dependency(Coordinate.maven(g, a), declared, resolved, scope, viaMgmt, null);
        }
    }

    // -------------------------------------------------------------------------

    static String interpolate(String value, Map<String, String> props) {
        if (value == null) return null;
        String cur = value;
        for (int depth = 0; depth < 10 && cur.contains("${"); depth++) {
            Matcher m = PROP.matcher(cur);
            StringBuilder sb = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String rep = props.get(m.group(1));
                if (rep != null) changed = true;
                m.appendReplacement(sb, Matcher.quoteReplacement(rep != null ? rep : m.group(0)));
            }
            m.appendTail(sb);
            cur = sb.toString();
            if (!changed) break;
        }
        return cur;
    }

    /** Joins a repo-relative directory and a relative path; ".." beyond the repo root is kept. */
    static String normalize(String dir, String rel) {
        Deque<String> parts = new ArrayDeque<>();
        if (!dir.isEmpty()) for (String s : dir.split("/")) parts.addLast(s);
        for (String s : rel.replace('\\', '/').split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                // Keep ".." that climbs out of the repo: it names a sibling checkout
                if (!parts.isEmpty() && !parts.peekLast().equals("..")) parts.removeLast();
                else parts.addLast("..");
            } else {
                parts.addLast(s);
            }
        }
        return String.join("/", parts);
    }

    private static List<RawDep> deps(Element depsEl) {
        if (depsEl == null) return List.of();
        List<RawDep> out = new ArrayList<>();
        for (Element d : children(depsEl, "dependency")) {
            String g = text(d, "groupId"), a = text(d, "artifactId");
            if (g == null || a == null) continue;
            out.add(new RawDep(g, a, text(d, "version"), text(d, "scope"), text(d, "type")));
        }
        return out;
    }

    private static DocumentBuilder builder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        // Errors surface as exceptions and become warnings; don't also print them to stderr
        b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
        });
        return b;
    }

    private static Element child(Element parent, String name) {
        if (parent == null) return null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(name)) return e;
        }
        return null;
    }

    private static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) if (nl.item(i) instanceof Element e) out.add(e);
        return out;
    }

    private static List<Element> children(Element parent, String name) {
        return children(parent).stream().filter(e -> e.getTagName().equals(name)).toList();
    }

    private static String text(Element parent, String name) {
        Element c = child(parent, name);
        if (c == null) return null;
        String t = c.getTextContent().trim();
        return t.isEmpty() ? null : t;
    }
}
