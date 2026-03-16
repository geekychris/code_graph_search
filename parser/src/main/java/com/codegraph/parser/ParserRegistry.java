package com.codegraph.parser;

import com.codegraph.core.model.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Registry of all language parsers. Parsers are tried in registration order;
 * the first one whose {@code canParse()} returns {@code true} wins.
 */
public class ParserRegistry {

    private static final Logger log = LoggerFactory.getLogger(ParserRegistry.class);

    private final List<LanguageParser> parsers = new ArrayList<>();

    public ParserRegistry() {}

    /** Register a parser. Later registrations take lower priority. */
    public void register(LanguageParser parser) {
        parsers.add(parser);
        log.debug("Registered parser for language {}: {}", parser.getLanguage(), parser.getClass().getSimpleName());
    }

    /**
     * Find the first registered parser that can handle the given file.
     */
    public Optional<LanguageParser> getParser(Path file) {
        return parsers.stream()
                .filter(p -> p.canParse(file))
                .findFirst();
    }

    /**
     * Parse the given file by delegating to the appropriate registered parser.
     * Returns a failed {@link ParseResult} if no parser is found.
     */
    public ParseResult parse(Path file, String repoId, Path repoRoot) {
        var relativePath = repoRoot.relativize(file).toString();
        return getParser(file)
                .map(parser -> {
                    log.debug("Parsing {} with {}", relativePath, parser.getClass().getSimpleName());
                    try {
                        return parser.parse(file, repoId, repoRoot);
                    } catch (Exception e) {
                        log.error("Parser {} threw unexpected exception on {}: {}",
                                parser.getClass().getSimpleName(), relativePath, e.getMessage(), e);
                        var result = new ParseResult(relativePath);
                        result.setError("Unexpected parser error: " + e.getMessage());
                        return result;
                    }
                })
                .orElseGet(() -> {
                    log.debug("No parser found for {}", relativePath);
                    var result = new ParseResult(relativePath);
                    result.setError("No parser registered for file: " + file.getFileName());
                    return result;
                });
    }

    /** Returns an unmodifiable view of registered parsers. */
    public List<LanguageParser> getParsers() {
        return List.copyOf(parsers);
    }

    /** Returns how many parsers are registered. */
    public int size() {
        return parsers.size();
    }

    /**
     * Convenience factory that creates a registry pre-loaded with all built-in parsers.
     * Tree-sitter based parsers are included but degrade gracefully if the binary is absent.
     */
    public static ParserRegistry withDefaults() {
        var registry = new ParserRegistry();
        // Import lazily to avoid circular dependency issues at class-load time
        try {
            registry.register((LanguageParser) Class.forName("com.codegraph.parser.java.JavaSourceParser")
                    .getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            log.warn("Could not register JavaSourceParser: {}", e.getMessage());
        }
        try {
            registry.register((LanguageParser) Class.forName("com.codegraph.parser.treesitter.GoVisitor")
                    .getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            log.warn("Could not register GoVisitor: {}", e.getMessage());
        }
        try {
            registry.register((LanguageParser) Class.forName("com.codegraph.parser.treesitter.RustVisitor")
                    .getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            log.warn("Could not register RustVisitor: {}", e.getMessage());
        }
        try {
            registry.register((LanguageParser) Class.forName("com.codegraph.parser.treesitter.TypeScriptVisitor")
                    .getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            log.warn("Could not register TypeScriptVisitor: {}", e.getMessage());
        }
        try {
            registry.register((LanguageParser) Class.forName("com.codegraph.parser.treesitter.CppVisitor")
                    .getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            log.warn("Could not register CppVisitor: {}", e.getMessage());
        }
        try {
            registry.register((LanguageParser) Class.forName("com.codegraph.parser.treesitter.CVisitor")
                    .getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            log.warn("Could not register CVisitor: {}", e.getMessage());
        }
        try {
            registry.register((LanguageParser) Class.forName("com.codegraph.parser.markdown.MarkdownFileParser")
                    .getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            log.warn("Could not register MarkdownFileParser: {}", e.getMessage());
        }
        try {
            registry.register((LanguageParser) Class.forName("com.codegraph.parser.config.ConfigFileParser")
                    .getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            log.warn("Could not register ConfigFileParser: {}", e.getMessage());
        }
        return registry;
    }
}
