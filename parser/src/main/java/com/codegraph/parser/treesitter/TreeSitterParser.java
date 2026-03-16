package com.codegraph.parser.treesitter;

import com.codegraph.core.model.Language;
import com.codegraph.parser.LanguageParser;
import com.codegraph.parser.ParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for tree-sitter based language parsers.
 *
 * <p>Subclasses implement {@link #visitAST} to walk the parsed {@link SExprNode} tree
 * and populate a {@link ParseResult}. They may also override {@link #fallbackParse}
 * to provide a regex-based fallback when tree-sitter is not installed.
 *
 * <p>Tree-sitter positions are 0-indexed; implementations should add 1 before storing
 * values in {@link com.codegraph.core.model.CodeElement}.
 */
public abstract class TreeSitterParser implements LanguageParser {

    private static final Logger log = LoggerFactory.getLogger(TreeSitterParser.class);

    /** Path to the tree-sitter CLI binary. Defaults to {@code "tree-sitter"}. */
    private String treeSitterBinary = "tree-sitter";

    /** Maximum seconds to wait for tree-sitter to complete. */
    private int timeoutSeconds = 30;

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    public void setTreeSitterBinary(String binary) { this.treeSitterBinary = binary; }
    public String getTreeSitterBinary()             { return treeSitterBinary; }

    public void setTimeoutSeconds(int seconds)      { this.timeoutSeconds = seconds; }
    public int getTimeoutSeconds()                  { return timeoutSeconds; }

    // -------------------------------------------------------------------------
    // LanguageParser contract
    // -------------------------------------------------------------------------

    @Override
    public boolean canParse(Path file) {
        var name = file.getFileName().toString();
        return getLanguage().extensions.stream().anyMatch(name::endsWith);
    }

    @Override
    public ParseResult parse(Path file, String repoId, Path repoRoot) {
        var relativePath = repoRoot.relativize(file).toString();
        var result = new ParseResult(relativePath);

        String[] sourceLines;
        try {
            sourceLines = Files.readAllLines(file, StandardCharsets.UTF_8).toArray(String[]::new);
        } catch (IOException e) {
            log.error("Cannot read {}: {}", relativePath, e.getMessage());
            result.setError("IO error: " + e.getMessage());
            return result;
        }

        var root = runTreeSitter(file, sourceLines);
        if (root == null) {
            log.debug("tree-sitter unavailable for {}, using fallback", relativePath);
            return fallbackParse(file, repoId, repoRoot, relativePath, sourceLines, result);
        }

        try {
            return visitAST(root, file, repoId, repoRoot, sourceLines);
        } catch (Exception e) {
            log.error("AST visitor failed on {}: {}", relativePath, e.getMessage(), e);
            result.setError("AST visit error: " + e.getMessage());
            return result;
        }
    }

    // -------------------------------------------------------------------------
    // Tree-sitter execution
    // -------------------------------------------------------------------------

    /**
     * Runs {@code tree-sitter parse <file>}, captures stdout, and parses the
     * S-expression into a node tree.
     *
     * @return the root {@link SExprNode}, or {@code null} if tree-sitter is not
     *         available or execution fails.
     */
    public SExprNode runTreeSitter(Path file, String[] sourceLines) {
        try {
            var pb = new ProcessBuilder(treeSitterBinary, "parse", file.toAbsolutePath().toString());
            pb.redirectErrorStream(false);
            var process = pb.start();

            byte[] stdout;
            try (var in = process.getInputStream()) {
                stdout = in.readAllBytes();
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("tree-sitter timed out on {}", file);
                return null;
            }

            int exitCode = process.exitValue();
            // tree-sitter exits 0 even with ERROR nodes; non-zero usually means binary error
            if (exitCode != 0 && stdout.length == 0) {
                log.debug("tree-sitter exited {} with no output for {}", exitCode, file);
                return null;
            }

            var sexp = new String(stdout, StandardCharsets.UTF_8);
            return new SExprParser().parse(sexp, sourceLines);

        } catch (IOException e) {
            // Binary not found or not executable — silently fall back
            log.debug("tree-sitter not available: {}", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Unexpected error running tree-sitter on {}: {}", file, e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Abstract/overridable methods
    // -------------------------------------------------------------------------

    /**
     * Walk the tree-sitter AST and produce a {@link ParseResult}.
     *
     * @param root        root node of the parsed S-expression tree
     * @param file        absolute path to the source file
     * @param repoId      repository identifier
     * @param repoRoot    repository root used to compute relative paths
     * @param sourceLines raw lines of the source file (0-indexed)
     */
    public abstract ParseResult visitAST(SExprNode root, Path file, String repoId,
                                          Path repoRoot, String[] sourceLines);

    /**
     * Called when tree-sitter is unavailable. Default implementation returns an empty
     * (but successful) {@link ParseResult}. Subclasses may override with a regex-based
     * extractor.
     */
    protected ParseResult fallbackParse(Path file, String repoId, Path repoRoot,
                                        String relativePath, String[] sourceLines,
                                        ParseResult result) {
        // By default: nothing extracted, not an error (tree-sitter optional)
        return result;
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * Extracts the source text covered by a node's position range.
     * Tree-sitter positions are 0-indexed; this method uses them as-is.
     */
    public static String extractText(SExprNode node, String[] sourceLines) {
        if (node == null || sourceLines == null) return "";
        // Prefer the pre-computed text if already set
        if (node.getText() != null) return node.getText();
        return SExprParser.extractSpan(node.getStartLine(), node.getStartCol(),
                node.getEndLine(), node.getEndCol(), sourceLines);
    }

    /**
     * Truncates text so it contains at most {@code maxLines} lines.
     */
    public static String truncate(String text, int maxLines) {
        if (text == null) return null;
        var lines = text.split("\n", -1);
        if (lines.length <= maxLines) return text;
        return String.join("\n", Arrays.copyOf(lines, maxLines)) + "\n// ... (truncated)";
    }

    public static final int MAX_SNIPPET_LINES = 100;
}
