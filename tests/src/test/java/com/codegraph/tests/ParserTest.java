package com.codegraph.tests;

import com.codegraph.core.model.CodeElement;
import com.codegraph.core.model.ElementType;
import com.codegraph.core.model.Language;
import com.codegraph.core.model.SearchQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests that the parser correctly extracts elements from the code-graph-search
 * project's own Java source files.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParserTest extends BaseIntegrationTest {

    @Test
    void shouldIndexJavaClasses() {
        var q = new SearchQuery();
        q.setElementTypes(List.of(ElementType.CLASS));
        q.setLanguages(List.of(Language.JAVA));
        var classes = graphService.search(q);
        assertThat(classes).isNotEmpty();
        // We know CodeElement class exists
        assertThat(classes).anyMatch(e -> e.getName().equals("CodeElement"));
    }

    @Test
    void shouldIndexJavaMethods() {
        var q = new SearchQuery();
        q.setElementTypes(List.of(ElementType.METHOD));
        q.setLanguages(List.of(Language.JAVA));
        var methods = graphService.search(q);
        assertThat(methods).isNotEmpty();
    }

    @Test
    void shouldIndexJavaInterfaces() {
        var q = new SearchQuery();
        q.setElementTypes(List.of(ElementType.INTERFACE));
        q.setLanguages(List.of(Language.JAVA));
        var interfaces = graphService.search(q);
        assertThat(interfaces)
            .extracting(CodeElement::getName)
            .contains("GraphService", "LanguageParser");
    }

    @Test
    void shouldIndexMarkdownFiles() {
        var q = new SearchQuery();
        q.setElementTypes(List.of(ElementType.MARKDOWN_DOCUMENT));
        var docs = graphService.search(q);
        assertThat(docs).isNotEmpty();
    }

    @Test
    void shouldIndexYamlConfigs() {
        var q = new SearchQuery();
        q.setElementTypes(List.of(ElementType.CONFIG_FILE));
        q.setLanguages(List.of(Language.YAML));
        var configs = graphService.search(q);
        assertThat(configs).isNotEmpty();
    }

    @Test
    void shouldExtractElementSnippets() {
        var q = new SearchQuery("CodeElement");
        q.setElementTypes(List.of(ElementType.CLASS));
        var results = graphService.search(q);
        assertThat(results).isNotEmpty();
        var codeElement = results.stream()
            .filter(e -> e.getName().equals("CodeElement"))
            .findFirst();
        assertThat(codeElement).isPresent();
        assertThat(codeElement.get().getSnippet()).isNotBlank();
    }

    @Test
    void shouldExtractQualifiedNames() {
        var q = new SearchQuery("ElementType");
        q.setElementTypes(List.of(ElementType.ENUM));
        var results = graphService.search(q);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getQualifiedName())
            .contains("ElementType");
    }

    @Test
    void shouldSetCorrectLineNumbers() {
        var q = new SearchQuery("GraphService");
        q.setElementTypes(List.of(ElementType.INTERFACE));
        var results = graphService.search(q);
        assertThat(results).isNotEmpty();
        var gs = results.stream()
            .filter(e -> e.getName().equals("GraphService"))
            .findFirst();
        assertThat(gs).isPresent();
        assertThat(gs.get().getLineStart()).isGreaterThan(0);
        assertThat(gs.get().getLineEnd()).isGreaterThanOrEqualTo(gs.get().getLineStart());
    }

    @Test
    void shouldExtractFields() {
        var q = new SearchQuery();
        q.setElementTypes(List.of(ElementType.FIELD));
        q.setLanguages(List.of(Language.JAVA));
        var fields = graphService.search(q);
        assertThat(fields).isNotEmpty();
    }

    @Test
    void shouldExtractParameters() {
        var q = new SearchQuery();
        q.setElementTypes(List.of(ElementType.PARAMETER));
        q.setLanguages(List.of(Language.JAVA));
        var params = graphService.search(q);
        assertThat(params).isNotEmpty();
    }
}
