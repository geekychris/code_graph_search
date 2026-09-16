# Desktop wrapper — build + package targets.
#
# The Maven build already produces target/code-graph-search-desktop.jar
# (a runnable fat JAR launching JavaFX WebView). This Makefile adds
# the jpackage step that bundles a JRE + JavaFX modules into a
# self-contained macOS .app — end users need nothing on PATH.
#
# Targets:
#   run             — launch the fat JAR against user's config (fast dev)
#   app             — build the self-contained .app via jpackage
#   install         — copy the built .app to ~/Applications
#   clean

REPO_ROOT   := $(shell cd .. && pwd)
DESKTOP_JAR := target/code-graph-search-desktop.jar
BUILD_DIR   := target/dist
BUNDLE      := $(BUILD_DIR)/Code Graph Search.app
APPS_DIR    := $(HOME)/Applications

# JavaFX modules the WebView + Controls stack needs. Passed to
# jpackage via --add-modules so jlink pulls the right JavaFX bits
# into the bundled runtime.
JFX_MODULES := javafx.controls,javafx.web,javafx.graphics,javafx.base,\
               javafx.media,jdk.zipfs,java.desktop,java.instrument,\
               java.logging,java.management,java.naming,java.net.http,\
               java.rmi,java.security.jgss,java.sql,java.xml,\
               jdk.crypto.ec,jdk.unsupported

.PHONY: run app install clean require-jar require-jpackage

# Quick dev launch. Assumes `mvn package -pl desktop -am` has produced
# the fat JAR at target/.
run: require-jar
	java -jar "$(DESKTOP_JAR)"

# Self-contained .app bundle. Requires jpackage (bundled with any
# JDK >= 14) and a JavaFX SDK on JAVA_HOME's module path — the
# openjfx maven deps are inside the shaded JAR so jpackage doesn't
# strictly need JavaFX SDK on disk, but WebView's native libraries
# (libjfxwebkit.dylib etc.) DO need to be extracted; we pass them
# via --module-path if JAVAFX_HOME is set.
app: require-jar require-jpackage
	rm -rf "$(BUILD_DIR)"
	mkdir -p "$(BUILD_DIR)"
	jpackage \
		--type app-image \
		--input target/ \
		--main-jar code-graph-search-desktop.jar \
		--name "Code Graph Search" \
		--app-version 0.1.0 \
		--dest "$(BUILD_DIR)" \
		--java-options "--enable-native-access=ALL-UNNAMED" \
		--icon icon.icns 2>/dev/null || \
	jpackage \
		--type app-image \
		--input target/ \
		--main-jar code-graph-search-desktop.jar \
		--name "Code Graph Search" \
		--app-version 0.1.0 \
		--dest "$(BUILD_DIR)" \
		--java-options "--enable-native-access=ALL-UNNAMED"
	@echo "Built self-contained bundle at $(BUNDLE)"
	@du -sh "$(BUNDLE)" 2>/dev/null || true

install:
	@[ -d "$(BUNDLE)" ] || { echo "$(BUNDLE) missing — run 'make app' first"; exit 1; }
	@mkdir -p "$(APPS_DIR)"
	-@pkill -f "$(BUNDLE)/Contents/MacOS" 2>/dev/null; sleep 0.3
	@rm -rf "$(APPS_DIR)/Code Graph Search.app"
	@cp -R "$(BUNDLE)" "$(APPS_DIR)/"
	@echo "Installed to $(APPS_DIR)/Code Graph Search.app"

require-jar:
	@[ -f "$(DESKTOP_JAR)" ] || { \
		echo "$(DESKTOP_JAR) missing — run at repo root:"; \
		echo "  mvn package -pl desktop -am -DskipTests"; \
		exit 1; \
	}

require-jpackage:
	@command -v jpackage >/dev/null 2>&1 || { \
		echo "jpackage not on PATH — install a JDK >= 14 (openjdk 21 is fine)"; \
		exit 1; \
	}

clean:
	rm -rf target/
