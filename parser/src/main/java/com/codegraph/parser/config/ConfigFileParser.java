package com.codegraph.parser.config;

import com.codegraph.core.model.*;
import com.codegraph.parser.LanguageParser;
import com.codegraph.parser.ParseResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Parses YAML and JSON configuration files.
 *
 * <p>Creates:
 * <ul>
 *   <li>{@code CONFIG_FILE} element for the file</li>
 *   <li>{@code CONFIG_KEY} elements for each key (dot-notation for nested keys)</li>
 *   <li>{@code CONTAINS} edges from the file element to each key element</li>
 *   <li>{@code REFERENCES_CLASS} edges when a string value looks like a FQCN</li>
 * </ul>
 */
public class ConfigFileParser implements LanguageParser {

    private static final Logger log = LoggerFactory.getLogger(ConfigFileParser.class);

    /**
     * Pattern matching fully qualified Java/JVM class names:
     * at least two dot-separated segments starting with a lower-case package component,
     * ending with an upper-case class name.
     */
    private static final Pattern FQCN_PATTERN = Pattern.compile(
            "^[a-z][a-zA-Z0-9_]*(\\.[a-z][a-zA-Z0-9_]*)*\\.[A-Z][a-zA-Z0-9_$]*$");

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Override
    public Language getLanguage() {
        return Language.YAML; // primary; also handles JSON
    }

    @Override
    public boolean canParse(Path file) {
        var name = file.getFileName().toString();
        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
    }

    @Override
    public ParseResult parse(Path file, String repoId, Path repoRoot) {
        var relativePath = repoRoot.relativize(file).toString();
        var result = new ParseResult(relativePath);

        var fileName = file.getFileName().toString();
        boolean isYaml = fileName.endsWith(".yaml") || fileName.endsWith(".yml");

        // CONFIG_FILE element
        var fileEl = newElement(repoId, relativePath, ElementType.CONFIG_FILE, relativePath,
                isYaml ? Language.YAML : Language.JSON);
        fileEl.setName(fileName);
        fileEl.setLineStart(1);
        result.addElement(fileEl);

        JsonNode root;
        try {
            var mapper = isYaml ? yamlMapper : jsonMapper;
            root = mapper.readTree(file.toFile());
        } catch (IOException e) {
            log.error("Cannot read {}: {}", relativePath, e.getMessage());
            result.setError("IO error: " + e.getMessage());
            return result;
        } catch (Exception e) {
            log.error("Failed to parse {}: {}", relativePath, e.getMessage());
            result.setError("Parse error: " + e.getMessage());
            return result;
        }

        if (root == null || root.isNull()) {
            return result;
        }

        try {
            walkNode(root, "", repoId, relativePath, fileEl, isYaml, result);
        } catch (Exception e) {
            log.error("Error walking config tree {}: {}", relativePath, e.getMessage(), e);
        }

        return result;
    }

    // -------------------------------------------------------------------------

    /**
     * Recursively walk a JSON/YAML node tree, emitting CONFIG_KEY elements for
     * every leaf or object key encountered.
     *
     * @param node          current JSON node
     * @param keyPath       dot-notation path of the current node (empty string = root)
     * @param repoId        repository identifier
     * @param filePath      relative file path
     * @param parentEl      containing element (FILE or parent KEY)
     * @param isYaml        whether the source format is YAML
     * @param result        result accumulator
     */
    private void walkNode(JsonNode node, String keyPath, String repoId,
                          String filePath, CodeElement parentEl, boolean isYaml,
                          ParseResult result) {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                var childPath = keyPath.isEmpty() ? entry.getKey()
                        : keyPath + "." + entry.getKey();
                walkNode(entry.getValue(), childPath, repoId, filePath, parentEl, isYaml, result);
            }
        } else if (node.isArray()) {
            // For arrays, emit entries with index notation: key[0], key[1], ...
            int idx = 0;
            for (var element : node) {
                var childPath = keyPath + "[" + idx + "]";
                walkNode(element, childPath, repoId, filePath, parentEl, isYaml, result);
                idx++;
            }
        } else {
            // Leaf node — emit a CONFIG_KEY element
            if (keyPath.isBlank()) return;

            var value = node.asText();
            var language = isYaml ? Language.YAML : Language.JSON;
            var keyEl = newElement(repoId, filePath, ElementType.CONFIG_KEY, filePath + "#" + keyPath, language);
            keyEl.setName(keyPath);
            keyEl.setSnippet(value);
            keyEl.setParentId(parentEl.getId());
            result.addElement(keyEl);
            result.addEdge(new CodeEdge(parentEl.getId(), keyEl.getId(), EdgeType.CONTAINS));

            // Detect FQCN references in string values
            if (node.isTextual() && FQCN_PATTERN.matcher(value.trim()).matches()) {
                var classId = CodeElement.generateId(repoId, filePath, ElementType.CLASS, value.trim());
                result.addEdge(new CodeEdge(keyEl.getId(), classId, EdgeType.REFERENCES_CLASS));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static CodeElement newElement(String repoId, String filePath,
                                          ElementType type, String qualifiedName,
                                          Language language) {
        var el = new CodeElement();
        el.setRepoId(repoId);
        el.setLanguage(language);
        el.setElementType(type);
        el.setQualifiedName(qualifiedName);
        el.setFilePath(filePath);
        el.setId(CodeElement.generateId(repoId, filePath, type, qualifiedName));
        return el;
    }
}
