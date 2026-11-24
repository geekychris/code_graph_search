package com.codegraph.tests;

import com.codegraph.core.config.AppConfig;
import com.codegraph.core.config.RepoConfig;
import com.codegraph.core.model.*;
import com.codegraph.indexer.IndexerService;
import com.codegraph.parser.ParseResult;
import com.codegraph.parser.ParserRegistry;
import com.codegraph.parser.python.PythonSourceParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** A small Python package: definitions, imports, inheritance and calls across files. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PythonParserTest {

    private Path tmp;
    private Path repo;
    private IndexerService indexer;

    @BeforeAll
    void setUp() throws IOException {
        tmp = Files.createTempDirectory("pyparser");
        repo = tmp.resolve("pyrepo");
        write("src/shop/__init__.py", "\"\"\"The shop package.\"\"\"\n");
        write("src/shop/models.py", """
            '''Domain models.'''
            from dataclasses import dataclass


            class Base:
                registry = {}  # class attribute

                def validate(self):
                    return True


            @dataclass
            class User(Base):
                \"\"\"A customer.

                Has a name (and a "nickname").
                \"\"\"
                name: str = "anon"

                def __init__(self, name):
                    self.name = name
                    self.email: str = None

                def save(self, db=None) -> bool:
                    s = "not a call( # nor a comment"
                    if self.validate():
                        return helper(s)
                    return False

                @staticmethod
                def make(n): return User(n)


            def helper(value):
                def inner(x):
                    return x
                return inner(value)
            """);
        write("src/shop/util.py", """
            import json, os.path

            async def fetch(url: str,
                            timeout: int = 5) -> dict:
                return json.loads(url); os.path.join("a", "b")

            class Retry(object): pass
            """);
        write("src/shop/service.py", """
            from .models import User, helper as h
            from . import util as u
            import shop.util


            class Service:
                def run(self, name):
                    user = User(name)
                    user.save()
                    h(name)
                    u.Retry()
                    return shop.util.fetch(name)
            """);
        write("scripts/tool.py", """
            from shop.service import Service

            if __name__ == "__main__":
                Service().run("x")
            """);

        AppConfig.IndexerConfig ic = new AppConfig.IndexerConfig();
        ic.setDataDir(tmp.resolve("data").toString());
        ic.setIndexingThreads(2);
        indexer = new IndexerService(ic);
        RepoConfig rc = new RepoConfig();
        rc.setId("pyrepo");
        rc.setPath(repo.toString());
        indexer.indexRepository(rc, ParserRegistry.withDefaults());
    }

    @AfterAll
    void tearDown() {
        indexer.close();
    }

    @Test
    void namesModulesByPackagePath() {
        assertThat(PythonSourceParser.class).isNotNull();
        assertThat(qn("shop.models", ElementType.MODULE)).isPresent();
        assertThat(qn("shop", ElementType.MODULE)).isPresent()
                .get().satisfies(m -> assertThat(m.getDocComment()).isEqualTo("The shop package."));
        // scripts/ has no __init__.py: the module is named after the file
        assertThat(qn("tool", ElementType.MODULE)).isPresent();
    }

    @Test
    void extractsClassesMethodsFunctionsAndFields() {
        CodeElement user = qn("shop.models.User", ElementType.CLASS).orElseThrow();
        assertThat(user.getDocComment()).startsWith("A customer.").contains("\"nickname\"");
        assertThat(user.getModifiers()).contains("@dataclass");
        assertThat(user.getSignature()).isEqualTo("class User(Base)");

        CodeElement save = qn("shop.models.User.save", ElementType.METHOD).orElseThrow();
        assertThat(save.getReturnType()).isEqualTo("bool");
        assertThat(save.getParameterTypes()).containsExactly("db");
        assertThat(save.getLineEnd()).isGreaterThan(save.getLineStart());
        assertThat(save.getParentId()).isEqualTo(user.getId());

        assertThat(qn("shop.models.User.__init__", ElementType.CONSTRUCTOR)).isPresent();
        assertThat(qn("shop.models.User.make", ElementType.METHOD)).get()
                .satisfies(m -> assertThat(m.getModifiers()).contains("staticmethod", "static"));
        assertThat(qn("shop.models.helper", ElementType.FUNCTION)).isPresent();
        assertThat(qn("shop.models.helper.inner", ElementType.FUNCTION)).isPresent();
        assertThat(qn("shop.models.User.name", ElementType.FIELD)).isPresent();
        assertThat(qn("shop.models.User.email", ElementType.FIELD)).isPresent();
        assertThat(qn("shop.models.Base.registry", ElementType.FIELD)).isPresent();

        CodeElement fetch = qn("shop.util.fetch", ElementType.FUNCTION).orElseThrow();
        assertThat(fetch.getModifiers()).contains("async");
        assertThat(fetch.getParameterTypes()).containsExactly("url", "timeout");
        assertThat(qn("shop.util.Retry", ElementType.CLASS)).isPresent();
    }

    @Test
    void linksInheritanceCallsAndImportsAcrossFiles() {
        CodeElement base = qn("shop.models.Base", ElementType.CLASS).orElseThrow();
        CodeElement user = qn("shop.models.User", ElementType.CLASS).orElseThrow();
        assertThat(indexer.getSubclasses(base.getId())).extracting(CodeElement::getId).contains(user.getId());

        CodeElement validate = qn("shop.models.Base.validate", ElementType.METHOD).orElseThrow();
        CodeElement save = qn("shop.models.User.save", ElementType.METHOD).orElseThrow();
        CodeElement helper = qn("shop.models.helper", ElementType.FUNCTION).orElseThrow();
        // self.validate() points at User.validate (inherited, so unresolved here); helper() resolves
        assertThat(indexer.getCallers(helper.getId())).extracting(CodeElement::getId).contains(save.getId());

        CodeElement run = qn("shop.service.Service.run", ElementType.METHOD).orElseThrow();
        // relative import with alias: h(name) -> shop.models.helper
        assertThat(indexer.getCallers(helper.getId())).extracting(CodeElement::getId).contains(run.getId());
        // User(name) instantiates the class; shop.util.fetch through a plain import
        assertThat(indexer.getEdgesTo(user.getId(), EdgeType.INSTANTIATES)).extracting(CodeEdge::getFromId).contains(run.getId());
        CodeElement fetch = qn("shop.util.fetch", ElementType.FUNCTION).orElseThrow();
        assertThat(indexer.getCallers(fetch.getId())).extracting(CodeElement::getId).contains(run.getId());
        CodeElement retry = qn("shop.util.Retry", ElementType.CLASS).orElseThrow();
        assertThat(indexer.getEdgesTo(retry.getId(), EdgeType.INSTANTIATES)).isNotEmpty();

        // module imports
        CodeElement models = qn("shop.models", ElementType.MODULE).orElseThrow();
        CodeElement serviceMod = qn("shop.service", ElementType.MODULE).orElseThrow();
        assertThat(indexer.getEdgesTo(models.getId(), EdgeType.IMPORTS)).extracting(CodeEdge::getFromId).contains(serviceMod.getId());
        // the string "not a call(" is not a call
        assertThat(indexer.getEdgesFrom(save.getId(), EdgeType.CALLS)).allSatisfy(e ->
                assertThat(e.getMetadata().get(CodeEdge.META_TARGET_QNAME)).doesNotContain("call"));
        assertThat(validate).isNotNull();
    }

    @Test
    void edgesCarryTargetsForCrossRepoResolution() {
        CodeElement tool = qn("tool", ElementType.MODULE).orElseThrow();
        assertThat(indexer.getEdgesFrom(tool.getId(), EdgeType.INSTANTIATES)).anySatisfy(e -> {
            assertThat(e.getMetadata()).containsEntry(CodeEdge.META_TARGET_QNAME, "shop.service.Service");
            assertThat(e.getMetadata()).containsEntry(CodeEdge.META_TARGET_TYPE, "CLASS");
        });
    }

    @Test
    void logicalLinesHandleStringsBracketsAndContinuations() {
        ParseResult r = new PythonSourceParser().parse(repo.resolve("src/shop/util.py"), "pyrepo", repo);
        assertThat(r.getElements()).extracting(CodeElement::getQualifiedName)
                .contains("shop.util", "shop.util.fetch", "shop.util.Retry");
        assertThat(r.getElements()).filteredOn(e -> e.getElementType() == ElementType.IMPORT)
                .extracting(CodeElement::getName).containsExactlyInAnyOrder("json", "os");
    }

    private Optional<CodeElement> qn(String qname, ElementType type) {
        return indexer.getElementsByQualifiedName("pyrepo", qname).stream()
                .filter(e -> e.getElementType() == type).findFirst();
    }

    private void write(String rel, String content) throws IOException {
        Path p = repo.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
