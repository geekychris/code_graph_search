# Code Graph Search — desktop wrapper (JavaFX WebView)

A Java-native `.app` bundle around the existing REST + web UI. Same
"singular UI surface, no browser tab" pattern as
[geekychris/history_viewer]'s `hv-app`, but implemented in pure Java
(JavaFX WebView) so this repo stays a single-language stack.

## What it does

1. Picks a free loopback port at launch (or accepts `--port N`).
2. Reads your config (`--config` or auto-detected under `~/.config/`),
   overrides `server.port` with the picked port, writes a per-launch
   temp copy.
3. Kicks off the existing `com.codegraph.app.Main` pipeline on a
   background thread against the temp config — same Jetty REST + web
   UI you get from `run.sh`, just embedded.
4. Opens a JavaFX `Stage` with a `WebView` pointing at
   `http://127.0.0.1:<port>/`. Waits for the port to come up before
   navigating so the WebView doesn't flash "connection refused"
   during Jetty cold start.

You get a real native macOS window (menu bar, dock icon, cmd-tab
switching, resizable frame) around the same UI, without touching a
browser.

## Building

### Fast dev iteration

```bash
# From repo root
mvn package -pl desktop -am -DskipTests
cd desktop
make run
```

Runs the fat JAR directly. Requires JavaFX on the module path — the
openjfx maven deps handle that automatically. Just needs JDK 21+.

### Self-contained `.app` via jpackage

```bash
cd desktop
make app        # produces target/dist/Code Graph Search.app (~200MB with bundled JRE)
make install    # copies to ~/Applications
open ~/Applications/Code\ Graph\ Search.app
```

`jpackage` bundles a trimmed JRE with the required JavaFX modules
inside the `.app`. End users need **nothing** on PATH — no Java, no
maven, no npm. Double-click the app; it starts the REST server on a
loopback port and shows the UI in an embedded WebView.

## Flags

```
code-graph-search-desktop [--config <yaml>] [--port <n>]
```

- `--config <yaml>` — override the config path (default: auto-detect
  `~/.config/code-graph-search.yaml`, then `~/.code-graph-search.yaml`,
  then `./config.yaml`).
- `--port <n>` — pin the loopback port instead of picking a free one.
  The temp effective config gets `server.port` set to whatever this is,
  so the WebView + Jetty always agree.

## Using with Chief

Once installed at `~/Applications/Code Graph Search.app`,
[Chief](https://github.com/geekychris/chief)'s codegraph integration
prefers launching the `.app` over the raw JAR (same "prefer .app when
installed" pattern Chief uses for the analyzer + history viewer).
Falls back to the JAR + browser if the `.app` isn't found.

[geekychris/history_viewer]: https://github.com/geekychris/history_viewer
