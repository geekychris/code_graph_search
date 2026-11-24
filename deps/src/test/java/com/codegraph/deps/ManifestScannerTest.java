package com.codegraph.deps;

import com.codegraph.deps.model.Dependency;
import com.codegraph.deps.model.ModuleManifest;
import com.codegraph.deps.model.RepoManifests;
import com.codegraph.deps.parse.ManifestScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestScannerTest {

    @TempDir Path repo;

    @Test
    void mavenInheritsPropertiesManagementAndBoms() throws IOException {
        write("pom.xml", """
            <project>
              <groupId>com.acme</groupId><artifactId>acme-parent</artifactId><version>2.1.0</version>
              <packaging>pom</packaging>
              <modules><module>lib</module><module>app</module></modules>
              <properties><guava.version>33.0</guava.version></properties>
              <dependencyManagement><dependencies>
                <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>${guava.version}</version></dependency>
                <dependency><groupId>com.acme</groupId><artifactId>acme-bom</artifactId><version>1.0</version><type>pom</type><scope>import</scope></dependency>
              </dependencies></dependencyManagement>
            </project>""");
        write("bom/pom.xml", """
            <project>
              <groupId>com.acme</groupId><artifactId>acme-bom</artifactId><version>1.0</version>
              <dependencyManagement><dependencies>
                <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.9</version></dependency>
              </dependencies></dependencyManagement>
            </project>""");
        write("lib/pom.xml", """
            <project>
              <parent><groupId>com.acme</groupId><artifactId>acme-parent</artifactId><version>2.1.0</version></parent>
              <artifactId>acme-lib</artifactId>
              <dependencies>
                <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId></dependency>
                <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></dependency>
                <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13</version><scope>test</scope></dependency>
              </dependencies>
            </project>""");
        write("app/pom.xml", """
            <project>
              <parent><groupId>com.acme</groupId><artifactId>acme-parent</artifactId><version>2.1.0</version></parent>
              <artifactId>acme-app</artifactId>
              <dependencies>
                <dependency><groupId>com.acme</groupId><artifactId>acme-lib</artifactId><version>${project.version}</version></dependency>
              </dependencies>
            </project>""");

        RepoManifests m = ManifestScanner.scan(repo);
        ModuleManifest lib = module(m, "lib/pom.xml");
        assertThat(lib.produces().key()).isEqualTo("maven:com.acme:acme-lib");
        assertThat(lib.version()).isEqualTo("2.1.0");
        assertThat(lib.parent().key()).isEqualTo("maven:com.acme:acme-parent");
        assertThat(dep(lib, "maven:com.google.guava:guava").resolvedVersion()).isEqualTo("33.0");
        assertThat(dep(lib, "maven:com.google.guava:guava").managed()).isTrue();
        assertThat(dep(lib, "maven:org.slf4j:slf4j-api").resolvedVersion()).isEqualTo("2.0.9");
        assertThat(dep(lib, "maven:junit:junit").scope()).isEqualTo("test");
        assertThat(dep(module(m, "app/pom.xml"), "maven:com.acme:acme-lib").resolvedVersion()).isEqualTo("2.1.0");
        assertThat(module(m, "pom.xml").submodules()).containsExactly("lib", "app");
        assertThat(dep(module(m, "pom.xml"), "maven:com.acme:acme-bom").scope()).isEqualTo("import");
    }

    @Test
    void malformedPomIsAWarningNotAFailure() throws IOException {
        write("pom.xml", "<project><artifactId>broken");
        RepoManifests m = ManifestScanner.scan(repo);
        assertThat(m.modules()).isEmpty();
        assertThat(m.warnings()).singleElement().asString().contains("pom.xml");
    }

    @Test
    void goModRequireReplaceAndSiblings() throws IOException {
        write("go.mod", """
            module github.com/geekychris/cloud-stack

            go 1.26.0

            require (
                github.com/geekychris/agent-runtime v0.0.0
                github.com/jackc/pgx/v5 v5.7.1
                golang.org/x/text v0.20.0 // indirect
            )

            replace github.com/geekychris/agent-runtime => ../agent-runtime
            replace github.com/geekychris/faas => ../faas
            """);
        ModuleManifest go = module(ManifestScanner.scan(repo), "go.mod");
        assertThat(go.produces().key()).isEqualTo("go:github.com/geekychris/cloud-stack");
        assertThat(go.extra()).containsEntry("go", "1.26.0");
        assertThat(dep(go, "go:github.com/geekychris/agent-runtime").localPath()).isEqualTo("../agent-runtime");
        assertThat(dep(go, "go:github.com/jackc/pgx/v5").resolvedVersion()).isEqualTo("v5.7.1");
        assertThat(dep(go, "go:golang.org/x/text").scope()).isEqualTo("indirect");
        assertThat(dep(go, "go:github.com/geekychris/faas").scope()).isEqualTo("replace");
    }

    @Test
    void npmWorkspacesAndLocalReferences() throws IOException {
        write("package.json", """
            {"name":"root","private":true,"workspaces":["packages/*"],
             "devDependencies":{"typescript":"^5.4.0"}}""");
        write("packages/ui/package.json", """
            {"name":"@geekychris/workflow-ui","version":"0.3.1",
             "dependencies":{"react":"^18.3.1","shared":"file:../shared"},
             "peerDependencies":{"react-dom":">=18"}}""");
        write("packages/shared/package.json", "{\"name\":\"shared\",\"version\":\"1.0.0\"}");
        RepoManifests m = ManifestScanner.scan(repo);
        assertThat(module(m, "package.json").submodules()).containsExactly("packages/shared", "packages/ui");
        ModuleManifest ui = module(m, "packages/ui/package.json");
        assertThat(ui.produces().key()).isEqualTo("npm:@geekychris/workflow-ui");
        assertThat(dep(ui, "npm:react").resolvedVersion()).isEqualTo("18.3.1");
        assertThat(dep(ui, "npm:shared").localPath()).isEqualTo("packages/shared");
        assertThat(dep(ui, "npm:react-dom").scope()).isEqualTo("peer");
        assertThat(dep(ui, "npm:react-dom").resolvedVersion()).isNull();
    }

    @Test
    void gradleKotlinWithCatalogAndProjects() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"portal\"\ninclude(\":backend\", \":shared\")\n");
        write("gradle.properties", "group=io.devportal\nversion=0.1.0\n");
        write("gradle/libs.versions.toml", """
            [versions]
            jgit = "7.1.0"
            [libraries]
            jgit = { module = "org.eclipse.jgit:org.eclipse.jgit", version.ref = "jgit" }
            picocli = "info.picocli:picocli:4.7.6"
            """);
        write("backend/build.gradle.kts", """
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter-web")
                implementation(libs.jgit)
                implementation(libs.picocli)
                implementation(platform("org.springframework.boot:spring-boot-dependencies:3.4.5"))
                implementation(project(":shared"))
                testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
                // implementation("com.example:commented-out:1.0")
            }""");
        write("shared/build.gradle", """
            dependencies {
                api group: 'com.fasterxml.jackson.core', name: 'jackson-databind', version: '2.17.1'
            }""");
        RepoManifests m = ManifestScanner.scan(repo);
        ModuleManifest backend = module(m, "backend/build.gradle.kts");
        assertThat(backend.produces().key()).isEqualTo("maven:io.devportal:backend");
        assertThat(backend.version()).isEqualTo("0.1.0");
        assertThat(dep(backend, "maven:org.eclipse.jgit:org.eclipse.jgit").resolvedVersion()).isEqualTo("7.1.0");
        assertThat(dep(backend, "maven:info.picocli:picocli").resolvedVersion()).isEqualTo("4.7.6");
        assertThat(dep(backend, "maven:org.springframework.boot:spring-boot-dependencies").scope()).isEqualTo("import");
        assertThat(dep(backend, "maven:io.devportal:shared").localPath()).isEqualTo("shared");
        assertThat(dep(backend, "maven:org.junit.jupiter:junit-jupiter").scope()).isEqualTo("test");
        assertThat(backend.dependencies()).noneMatch(d -> d.coordinate().name().contains("commented-out"));
        ModuleManifest shared = module(m, "shared/build.gradle");
        assertThat(dep(shared, "maven:com.fasterxml.jackson.core:jackson-databind").resolvedVersion()).isEqualTo("2.17.1");
    }

    @Test
    void cargoWorkspaceInheritance() throws IOException {
        write("Cargo.toml", """
            [workspace]
            members = ["crates/*"]

            [workspace.dependencies]
            serde = { version = "1.0.200", features = ["derive"] }
            """);
        write("crates/core/Cargo.toml", """
            [package]
            name = "amiforge-core"
            version = "0.4.0"

            [dependencies]
            serde = { workspace = true }
            anyhow = "1.0.86"
            util = { path = "../util" }

            [dev-dependencies]
            proptest = "1"
            """);
        RepoManifests m = ManifestScanner.scan(repo);
        assertThat(module(m, "Cargo.toml").submodules()).containsExactly("crates/core");
        ModuleManifest core = module(m, "crates/core/Cargo.toml");
        assertThat(core.produces().key()).isEqualTo("cargo:amiforge-core");
        assertThat(dep(core, "cargo:serde").resolvedVersion()).isEqualTo("1.0.200");
        assertThat(dep(core, "cargo:anyhow").resolvedVersion()).isEqualTo("1.0.86");
        assertThat(dep(core, "cargo:util").localPath()).isEqualTo("crates/util");
        assertThat(dep(core, "cargo:proptest").scope()).isEqualTo("dev");
    }

    @Test
    void pythonPyprojectPoetryAndRequirements() throws IOException {
        write("pyproject.toml", """
            [project]
            name = "Kokoro_Runtime"
            version = "0.2.0"
            dependencies = ["fastapi>=0.110", "numpy==1.26.4", "uvicorn[standard]"]

            [tool.poetry.group.dev.dependencies]
            pytest = "^8.0"
            """);
        write("requirements-dev.txt", "# tools\nruff==0.5.0\n-r requirements.txt\n");
        RepoManifests m = ManifestScanner.scan(repo);
        ModuleManifest py = module(m, "pyproject.toml");
        assertThat(py.produces().key()).isEqualTo("pypi:kokoro-runtime");
        assertThat(dep(py, "pypi:numpy").resolvedVersion()).isEqualTo("1.26.4");
        assertThat(dep(py, "pypi:fastapi").declaredVersion()).isEqualTo(">=0.110");
        assertThat(dep(py, "pypi:uvicorn")).isNotNull();
        assertThat(dep(py, "pypi:pytest").scope()).isEqualTo("dev");
        ModuleManifest req = module(m, "requirements-dev.txt");
        assertThat(dep(req, "pypi:ruff").scope()).isEqualTo("dev");
        assertThat(req.dependencies()).hasSize(1);
    }

    @Test
    void skipsVendoredDirectories() throws IOException {
        write("node_modules/left-pad/package.json", "{\"name\":\"left-pad\"}");
        write("target/classes/pom.xml", "<project/>");
        assertThat(ManifestScanner.scan(repo).modules()).isEmpty();
    }

    // ---- helpers ----

    private void write(String rel, String content) throws IOException {
        Path p = repo.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static ModuleManifest module(RepoManifests m, String path) {
        return m.modules().stream().filter(x -> x.manifestPath().equals(path)).findFirst()
                .orElseThrow(() -> new AssertionError("no module " + path + " in " + m.modules()));
    }

    private static Dependency dep(ModuleManifest m, String key) {
        return m.dependencies().stream().filter(d -> d.coordinate().key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no dependency " + key + " in " + m.dependencies()));
    }
}
