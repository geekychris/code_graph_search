package com.codegraph.desktop;

import com.codegraph.core.config.AppConfig;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * JavaFX-based native desktop wrapper for Code Graph Search.
 *
 * <p>Same "singular UI surface, no browser tab" pattern as
 * geekychris/history_viewer's hv-app, but implemented in Java so this
 * repo stays a single-language stack. Startup:
 *
 * <ol>
 *   <li>Parse CLI args (same shape as {@link com.codegraph.app.Main}
 *       plus {@code --port} for pinning the loopback port).</li>
 *   <li>Load {@link AppConfig} from {@code --config} (or auto-detect).</li>
 *   <li>Override {@code server.port} with a picked-or-user-supplied
 *       loopback port so the WebView can find the child.</li>
 *   <li>Fork a background thread that runs the existing
 *       {@link com.codegraph.app.Main#main} pipeline against a
 *       temp config with the overridden port.</li>
 *   <li>On the JavaFX thread, open a {@link Stage} with a
 *       {@link WebView} pointing at {@code http://127.0.0.1:<port>/}.
 *       Poll for the port before navigating so the WebView doesn't
 *       show "Connection refused" during Jetty's cold start.</li>
 * </ol>
 *
 * <p>Package with {@code jpackage} (see {@code Makefile}) to produce
 * a self-contained {@code Code Graph Search.app} with a bundled JRE
 * — end users need nothing on PATH.
 */
public class DesktopMain extends Application {

    private static final Logger log = LoggerFactory.getLogger(DesktopMain.class);

    // Args are stashed statically because JavaFX Application.start()
    // doesn't get them directly — Platform launches via Application
    // reflection and we lose the main() argv unless we grab them here.
    private static String[] cliArgs = new String[0];

    // Chosen loopback port for this launch.
    private int port;
    // Path to the effective config we write (mutated copy of the user's).
    private Path effectiveConfig;

    public static void main(String[] args) {
        cliArgs = args;
        // JavaFX launch blocks until the Stage closes.
        launch(args);
    }

    @Override
    public void init() throws Exception {
        // Parse our own flags. --config and --port are recognized;
        // anything else falls through to the underlying Main.
        String configPath = null;
        int userPort = 0;
        for (int i = 0; i < cliArgs.length; i++) {
            switch (cliArgs[i]) {
                case "--config" -> {
                    if (i + 1 < cliArgs.length) configPath = cliArgs[++i];
                }
                case "--port" -> {
                    if (i + 1 < cliArgs.length) userPort = Integer.parseInt(cliArgs[++i]);
                }
                default -> {
                    // Unknown flag — passed to Main below.
                }
            }
        }
        if (configPath == null) configPath = autoDetectConfig();
        port = userPort > 0 ? userPort : pickFreePort();
        effectiveConfig = writeEffectiveConfig(configPath, port);
        log.info("Desktop wrapper: port={}, effective config={}", port, effectiveConfig);

        // Kick off the existing app pipeline in a background daemon
        // thread so JavaFX's main thread stays free for the UI.
        Thread server = new Thread(() -> {
            try {
                com.codegraph.app.Main.main(new String[]{"--config", effectiveConfig.toString()});
            } catch (Throwable t) {
                log.error("Embedded REST server crashed", t);
            }
        }, "cgs-embedded-server");
        server.setDaemon(true);
        server.start();
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("Code Graph Search");
        WebView web = new WebView();
        Label loading = new Label("Loading Code Graph Search…");
        loading.setTextFill(Color.web("#e6e6ea"));
        StackPane root = new StackPane(web, loading);
        root.setStyle("-fx-background-color: #14141a;");
        // Hide the WebView until the server is up so the user doesn't
        // see the "Connection refused" chrome flash.
        web.setVisible(false);

        Scene scene = new Scene(root, 1400, 900);
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(560);
        stage.show();

        // Poll for the port on a background thread; once reachable,
        // hop back to the JavaFX thread to load the URL.
        Thread waiter = new Thread(() -> {
            waitForPort(port, 30_000);
            String url = "http://127.0.0.1:" + port + "/";
            Platform.runLater(() -> {
                web.getEngine().load(url);
                web.getEngine().getLoadWorker().stateProperty().addListener((obs, old, s) -> {
                    if (s == Worker.State.SUCCEEDED) {
                        loading.setVisible(false);
                        web.setVisible(true);
                    }
                });
            });
        }, "cgs-port-waiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    /**
     * Pick a free loopback port by asking the OS. Same trick the Go
     * wrapper uses: bind port 0, read what the kernel handed us, close.
     */
    private static int pickFreePort() {
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            return ss.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("pick free port", e);
        }
    }

    /**
     * Block up to timeoutMs waiting for the loopback port to accept
     * connections. Polls every 200ms.
     */
    private static void waitForPort(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 300);
                return;
            } catch (IOException ignored) {
                try { Thread.sleep(200); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.warn("Port {} never opened within {}ms", port, timeoutMs);
    }

    /**
     * First-existing candidate. Callers get a definite path even when
     * no file exists, so downstream Main.loadOrDefault logs a sensible
     * "using defaults" message with that path.
     */
    private static String autoDetectConfig() {
        String home = System.getProperty("user.home");
        String[] candidates = {
            home + "/.config/code-graph-search.yaml",
            home + "/.code-graph-search.yaml",
            "./config.yaml",
        };
        for (String c : candidates) {
            if (Files.exists(Path.of(c))) return c;
        }
        return candidates[0];
    }

    /**
     * Read the user's config (if it exists), override server.port,
     * write a scratch copy under java.io.tmpdir, return that path.
     * Preserves the user's repo list, dataDir, tree-sitter settings.
     */
    private static Path writeEffectiveConfig(String srcPath, int port) throws IOException {
        // Load the config via the existing AppConfig loader — this
        // gets the same lenient parsing (unknown fields ignored,
        // sensible defaults if the file is missing).
        AppConfig cfg = AppConfig.loadOrDefault(srcPath);
        cfg.getServer().setPort(port);

        // Serialize back via Jackson-YAML. Reusing the same mapper
        // shape ensures the temp file round-trips through the same
        // parser without alignment gotchas.
        com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        String header = "# Generated by code-graph-search-desktop (Java wrapper).\n" +
            "# Derived from: " + srcPath + "\n" +
            "# server.port overridden per-launch so the JavaFX WebView can find the child.\n";
        byte[] body = mapper.writeValueAsBytes(cfg);
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"),
            "code-graph-search-desktop-" + System.getProperty("user.name", "u") + ".yaml");
        Files.write(tmp, concat(header.getBytes(), body));
        return tmp;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
