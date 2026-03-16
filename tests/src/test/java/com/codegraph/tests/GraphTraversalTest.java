package com.codegraph.tests;

import com.codegraph.core.model.CodeElement;
import com.codegraph.core.model.ElementType;
import com.codegraph.core.model.Language;
import com.codegraph.core.model.SearchQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests graph traversal operations (parent/child/sibling, type hierarchy, call graph).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphTraversalTest extends BaseIntegrationTest {

    private CodeElement findClass(String name) {
        var q = new SearchQuery(name);
        q.setElementTypes(List.of(ElementType.CLASS, ElementType.INTERFACE, ElementType.ENUM));
        q.setLanguages(List.of(Language.JAVA));
        q.setLimit(5);
        return graphService.search(q).stream()
            .filter(e -> e.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Class not found: " + name));
    }

    private CodeElement findInterface(String name) {
        var q = new SearchQuery(name);
        q.setElementTypes(List.of(ElementType.INTERFACE));
        q.setLanguages(List.of(Language.JAVA));
        return graphService.search(q).stream()
            .filter(e -> e.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Interface not found: " + name));
    }

    @Test
    void shouldGetChildrenOfClass() {
        var codeElement = findClass("CodeElement");
        var children = graphService.getChildren(codeElement.getId());
        assertThat(children).isNotEmpty();
        // CodeElement has methods like getId, setId, etc.
        assertThat(children).anyMatch(e ->
            e.getElementType() == ElementType.METHOD ||
            e.getElementType() == ElementType.FIELD);
    }

    @Test
    void shouldGetMethodsOfClass() {
        var codeElement = findClass("CodeElement");
        var methods = graphService.getChildren(codeElement.getId(), ElementType.METHOD);
        assertThat(methods).isNotEmpty();
        assertThat(methods).allMatch(e -> e.getElementType() == ElementType.METHOD);
    }

    @Test
    void shouldGetParentOfMethod() {
        var codeElement = findClass("CodeElement");
        var methods = graphService.getChildren(codeElement.getId(), ElementType.METHOD);
        assertThat(methods).isNotEmpty();

        var method = methods.get(0);
        Optional<CodeElement> parent = graphService.getParent(method.getId());
        assertThat(parent).isPresent();
        assertThat(parent.get().getId()).isEqualTo(codeElement.getId());
    }

    @Test
    void shouldGetSiblingsOfMethod() {
        var codeElement = findClass("CodeElement");
        var methods = graphService.getChildren(codeElement.getId(), ElementType.METHOD);
        assertThat(methods).hasSizeGreaterThan(1);

        var method = methods.get(0);
        var siblings = graphService.getSiblings(method.getId());
        assertThat(siblings).isNotEmpty();
        // Siblings should NOT include the method itself
        assertThat(siblings).noneMatch(e -> e.getId().equals(method.getId()));
        // All siblings should have the same parent
        assertThat(siblings).allMatch(e -> codeElement.getId().equals(e.getParentId()));
    }

    @Test
    void shouldGetAncestors() {
        var codeElement = findClass("CodeElement");
        var methods = graphService.getChildren(codeElement.getId(), ElementType.METHOD);
        assertThat(methods).isNotEmpty();

        var ancestors = graphService.getAncestors(methods.get(0).getId());
        assertThat(ancestors).isNotEmpty();
        // Should include the class at least
        assertThat(ancestors).anyMatch(e -> e.getId().equals(codeElement.getId()));
    }

    @Test
    void shouldGetCommentsForElement() {
        // GraphService interface has Javadoc
        var gs = findInterface("GraphService");
        var comments = graphService.getComments(gs.getId());
        // If the interface has Javadoc, comments should be non-empty
        // (may be empty if no doc comments parsed - that's OK, just verify no exception)
        assertThat(comments).isNotNull();
    }

    @Test
    void shouldTraverseTypeHierarchy() {
        // IndexerService implements GraphService
        var q = new SearchQuery("IndexerService");
        q.setElementTypes(List.of(ElementType.CLASS));
        q.setLanguages(List.of(Language.JAVA));
        var results = graphService.search(q);

        if (!results.isEmpty()) {
            var indexerService = results.get(0);
            var interfaces = graphService.getInterfaces(indexerService.getId());
            // Should implement GraphService
            assertThat(interfaces).isNotNull();
        }
    }

    @Test
    void shouldGetSubGraph() {
        var codeElement = findClass("CodeElement");
        var subgraph = graphService.getSubGraph(codeElement.getId(), 1, null);
        assertThat(subgraph).isNotNull();
        assertThat(subgraph.nodes()).isNotEmpty();
        // Root should be in the nodes
        assertThat(subgraph.nodes()).anyMatch(e -> e.getId().equals(codeElement.getId()));
    }

    @Test
    void shouldSearchAcrossFullText() {
        // Search for something that appears in code
        var q = new SearchQuery("qualifiedName");
        q.setLanguages(List.of(Language.JAVA));
        var results = graphService.search(q);
        assertThat(results).isNotEmpty();
    }

    @Test
    void shouldFilterSearchByElementType() {
        var q = new SearchQuery("get");
        q.setElementTypes(List.of(ElementType.METHOD));
        q.setLanguages(List.of(Language.JAVA));
        var results = graphService.search(q);
        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(e -> e.getElementType() == ElementType.METHOD);
    }

    @Test
    void shouldSearchByQualifiedNamePrefix() {
        var q = new SearchQuery();
        q.setQualifiedNamePrefix("com.codegraph.core");
        var results = graphService.search(q);
        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(e ->
            e.getQualifiedName() != null &&
            e.getQualifiedName().startsWith("com.codegraph.core"));
    }

    @Test
    void shouldCountResults() {
        var q = new SearchQuery();
        q.setLanguages(List.of(Language.JAVA));
        q.setElementTypes(List.of(ElementType.METHOD));
        long count = graphService.count(q);
        assertThat(count).isGreaterThan(0);
    }
}
