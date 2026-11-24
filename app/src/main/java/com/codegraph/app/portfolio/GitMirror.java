package com.codegraph.app.portfolio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a working checkout of each repository and reports what changed between commits.
 *
 * <p>Uses the {@code git} CLI. Clones are partial ({@code --filter=blob:none}): all commits
 * and trees, with file contents fetched on checkout, so diffs between any two indexed
 * commits work without downloading every historical blob. A GitHub token, when given,
 * is passed through git's environment-based config (never on the command line or in
 * {@code .git/config}).
 */
public class GitMirror {

    private static final Logger log = LoggerFactory.getLogger(GitMirror.class);

    private final Path mirrorRoot;
    private final String token;
    private final int timeoutSeconds;

    /** Files to re-parse and files to drop, relative to the repo root. */
    public record ChangeSet(List<String> changed, List<String> deleted) {
        public boolean isEmpty() { return changed.isEmpty() && deleted.isEmpty(); }
    }

    public GitMirror(Path mirrorRoot, String token, int timeoutSeconds) {
        this.mirrorRoot = mirrorRoot;
        this.token = token;
        this.timeoutSeconds = timeoutSeconds;
    }

    public Path dirFor(String repoId) {
        return mirrorRoot.resolve(repoId);
    }

    /**
     * Clones or fast-forwards the checkout of {@code url} at {@code branch} (null = the
     * remote's default branch) and returns the checked-out commit, or null for an
     * empty repository.
     */
    public String sync(String repoId, String url, String branch) throws IOException {
        Path dir = dirFor(repoId);
        if (!Files.isDirectory(dir.resolve(".git"))) {
            Files.createDirectories(mirrorRoot);
            List<String> clone = new ArrayList<>(List.of("clone", "--filter=blob:none", "--no-tags", "--single-branch"));
            if (branch != null && !branch.isBlank()) clone.addAll(List.of("--branch", branch));
            clone.addAll(List.of(url, dir.toString()));
            run(mirrorRoot, clone);
        } else {
            String ref = branch != null && !branch.isBlank() ? branch : "HEAD";
            run(dir, List.of("fetch", "--filter=blob:none", "--no-tags", "origin", ref));
            run(dir, List.of("reset", "--hard", "--quiet", "FETCH_HEAD"));
        }
        return head(dir);
    }

    /** The commit checked out in {@code dir}, or null if it has none (empty or not a repo). */
    public String head(Path dir) {
        try {
            return run(dir, List.of("rev-parse", "--verify", "--quiet", "HEAD")).trim();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Files that differ between two commits, or null when {@code from} is not in the
     * local history (force-push, fresh clone), in which case the caller re-indexes fully.
     */
    public ChangeSet diff(Path dir, String from, String to) {
        try {
            run(dir, List.of("cat-file", "-e", from + "^{commit}"));
        } catch (IOException e) {
            return null;
        }
        try {
            String out = run(dir, List.of("diff", "--name-status", "--no-renames", "-z", from, to));
            return parseNameStatus(out);
        } catch (IOException e) {
            log.warn("git diff {}..{} failed in {}: {}", from, to, dir, e.getMessage());
            return null;
        }
    }

    /**
     * Uncommitted changes in a local working tree (modified, added, untracked, deleted),
     * for local repos indexed from a developer's checkout.
     */
    public ChangeSet workingTreeChanges(Path dir) {
        try {
            String out = run(dir, List.of("status", "--porcelain=v1", "-z", "--untracked-files=all", "--no-renames"));
            List<String> changed = new ArrayList<>();
            List<String> deleted = new ArrayList<>();
            for (String entry : out.split("\0")) {
                if (entry.length() < 4) continue;
                String status = entry.substring(0, 2);
                String path = entry.substring(3);
                if (status.contains("D")) deleted.add(path); else changed.add(path);
            }
            return new ChangeSet(changed, deleted);
        } catch (IOException e) {
            return new ChangeSet(List.of(), List.of());
        }
    }

    static ChangeSet parseNameStatus(String zOutput) {
        List<String> changed = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        String[] parts = zOutput.split("\0");
        for (int i = 0; i + 1 < parts.length; i += 2) {
            String status = parts[i].trim();
            String path = parts[i + 1];
            if (status.startsWith("D")) deleted.add(path); else changed.add(path);
        }
        return new ChangeSet(changed, deleted);
    }

    /** True if {@code dir} is inside a git work tree. */
    public boolean isGitRepo(Path dir) {
        return Files.exists(dir.resolve(".git"));
    }

    private String run(Path dir, List<String> args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        Map<String, String> env = pb.environment();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_ASKPASS", "echo");
        if (token != null && !token.isBlank()) {
            String basic = Base64.getEncoder().encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
            env.put("GIT_CONFIG_COUNT", "1");
            env.put("GIT_CONFIG_KEY_0", "http.https://github.com/.extraheader");
            env.put("GIT_CONFIG_VALUE_0", "AUTHORIZATION: basic " + basic);
        }
        Process p = pb.start();
        CompletableFuture<String> stdout = readAsync(p.getInputStream());
        CompletableFuture<String> stderr = readAsync(p.getErrorStream());
        try {
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("git " + args.get(0) + " timed out after " + timeoutSeconds + "s in " + dir);
            }
            if (p.exitValue() != 0) {
                throw new IOException("git " + String.join(" ", redact(args)) + " exited " + p.exitValue()
                        + ": " + stderr.join().trim());
            }
            return stdout.join();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted running git", e);
        }
    }

    private static CompletableFuture<String> readAsync(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            catch (IOException e) { return ""; }
        });
    }

    /** Strips credentials from URLs before they reach a log or error message. */
    private static List<String> redact(List<String> args) {
        return args.stream().map(a -> a.replaceAll("://[^@/]+@", "://***@")).toList();
    }
}
