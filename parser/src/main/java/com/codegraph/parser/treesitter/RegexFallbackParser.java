package com.codegraph.parser.treesitter;

import com.codegraph.core.model.*;
import com.codegraph.parser.ParseResult;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based fallback parser for when tree-sitter is unavailable.
 * Extracts top-level declarations (functions, structs, classes, interfaces, etc.)
 * from source files using language-specific regex patterns.
 */
public final class RegexFallbackParser {

    private RegexFallbackParser() {}

    // ---- Go ----------------------------------------------------------------

    private static final Pattern GO_PACKAGE = Pattern.compile("^package\\s+(\\w+)");
    private static final Pattern GO_FUNC = Pattern.compile(
            "^func\\s+(?:\\(\\s*\\w+\\s+\\*?(\\w+)\\s*\\)\\s+)?(\\w+)\\s*\\(([^)]*)\\)(.*)");
    private static final Pattern GO_TYPE = Pattern.compile(
            "^type\\s+(\\w+)\\s+(struct|interface)\\s*\\{");
    private static final Pattern GO_IMPORT = Pattern.compile("^import\\s+\"([^\"]+)\"");

    public static ParseResult parseGo(String repoId, String filePath, String[] lines, ParseResult result) {
        String pkg = "";
        String pkgId = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            Matcher m;

            if ((m = GO_PACKAGE.matcher(line)).find()) {
                pkg = m.group(1);
                var el = makeElement(repoId, filePath, Language.GO, ElementType.PACKAGE, pkg, pkg, i + 1, i + 1, line);
                result.addElement(el);
                pkgId = el.getId();
            } else if ((m = GO_FUNC.matcher(line)).find()) {
                String receiver = m.group(1);
                String name = m.group(2);
                String qname = buildQName(pkg, receiver, name);
                ElementType type = receiver != null ? ElementType.METHOD : ElementType.FUNCTION;
                int end = findBlockEnd(lines, i);
                String snippet = extractSnippet(lines, i, end);
                var el = makeElement(repoId, filePath, Language.GO, type, name, qname, i + 1, end + 1, snippet);
                if (receiver != null) el.addMetadata("receiverType", receiver);
                result.addElement(el);
                if (pkgId != null) result.addEdge(new CodeEdge(pkgId, el.getId(), EdgeType.CONTAINS));
            } else if ((m = GO_TYPE.matcher(line)).find()) {
                String name = m.group(1);
                String kind = m.group(2);
                String qname = pkg.isEmpty() ? name : pkg + "." + name;
                ElementType type = kind.equals("interface") ? ElementType.INTERFACE : ElementType.STRUCT;
                int end = findBlockEnd(lines, i);
                String snippet = extractSnippet(lines, i, end);
                var el = makeElement(repoId, filePath, Language.GO, type, name, qname, i + 1, end + 1, snippet);
                result.addElement(el);
                if (pkgId != null) result.addEdge(new CodeEdge(pkgId, el.getId(), EdgeType.CONTAINS));
            }
        }
        return result;
    }

    // ---- Rust ---------------------------------------------------------------

    private static final Pattern RUST_FN = Pattern.compile(
            "^\\s*(pub\\s+)?fn\\s+(\\w+)\\s*(?:<[^>]*>)?\\s*\\(");
    private static final Pattern RUST_STRUCT = Pattern.compile(
            "^\\s*(pub\\s+)?struct\\s+(\\w+)");
    private static final Pattern RUST_ENUM = Pattern.compile(
            "^\\s*(pub\\s+)?enum\\s+(\\w+)");
    private static final Pattern RUST_TRAIT = Pattern.compile(
            "^\\s*(pub\\s+)?trait\\s+(\\w+)");
    private static final Pattern RUST_IMPL = Pattern.compile(
            "^\\s*impl\\s+(?:<[^>]*>\\s+)?(?:(\\w+)\\s+for\\s+)?(\\w+)");

    public static ParseResult parseRust(String repoId, String filePath, String[] lines, ParseResult result) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m;

            if ((m = RUST_FN.matcher(line)).find()) {
                String vis = m.group(1) != null ? "public" : "private";
                String name = m.group(2);
                int end = findBlockEnd(lines, i);
                var el = makeElement(repoId, filePath, Language.RUST, ElementType.FUNCTION, name, name, i + 1, end + 1, extractSnippet(lines, i, end));
                el.setVisibility(vis);
                result.addElement(el);
            } else if ((m = RUST_STRUCT.matcher(line)).find()) {
                String name = m.group(2);
                int end = findBlockEnd(lines, i);
                var el = makeElement(repoId, filePath, Language.RUST, ElementType.STRUCT, name, name, i + 1, end + 1, extractSnippet(lines, i, end));
                result.addElement(el);
            } else if ((m = RUST_ENUM.matcher(line)).find()) {
                String name = m.group(2);
                int end = findBlockEnd(lines, i);
                var el = makeElement(repoId, filePath, Language.RUST, ElementType.ENUM, name, name, i + 1, end + 1, extractSnippet(lines, i, end));
                result.addElement(el);
            } else if ((m = RUST_TRAIT.matcher(line)).find()) {
                String name = m.group(2);
                int end = findBlockEnd(lines, i);
                var el = makeElement(repoId, filePath, Language.RUST, ElementType.INTERFACE, name, name, i + 1, end + 1, extractSnippet(lines, i, end));
                result.addElement(el);
            } else if ((m = RUST_IMPL.matcher(line)).find()) {
                String traitName = m.group(1);
                String typeName = m.group(2);
                String name = traitName != null ? traitName + " for " + typeName : typeName;
                int end = findBlockEnd(lines, i);
                var el = makeElement(repoId, filePath, Language.RUST, ElementType.CLASS, name, "impl " + name, i + 1, end + 1, extractSnippet(lines, i, end));
                result.addElement(el);
            }
        }
        return result;
    }

    // ---- TypeScript / JavaScript -------------------------------------------

    private static final Pattern TS_CLASS = Pattern.compile(
            "^\\s*(export\\s+)?(abstract\\s+)?class\\s+(\\w+)");
    private static final Pattern TS_INTERFACE = Pattern.compile(
            "^\\s*(export\\s+)?interface\\s+(\\w+)");
    private static final Pattern TS_FUNCTION = Pattern.compile(
            "^\\s*(export\\s+)?(async\\s+)?function\\s+(\\w+)\\s*(?:<[^>]*>)?\\s*\\(");
    private static final Pattern TS_CONST_ARROW = Pattern.compile(
            "^\\s*(export\\s+)?(const|let|var)\\s+(\\w+)\\s*=\\s*(?:async\\s+)?(?:\\([^)]*\\)|\\w+)\\s*=>");
    private static final Pattern TS_TYPE_ALIAS = Pattern.compile(
            "^\\s*(export\\s+)?type\\s+(\\w+)\\s*[=<]");
    private static final Pattern TS_ENUM = Pattern.compile(
            "^\\s*(export\\s+)?enum\\s+(\\w+)");

    public static ParseResult parseTypeScript(String repoId, String filePath, String[] lines,
                                               Language lang, ParseResult result) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m;

            if ((m = TS_CLASS.matcher(line)).find()) {
                String name = m.group(3);
                int end = findBlockEnd(lines, i);
                var el = makeElement(repoId, filePath, lang, ElementType.CLASS, name, name, i + 1, end + 1, extractSnippet(lines, i, end));
                result.addElement(el);
            } else if ((m = TS_INTERFACE.matcher(line)).find()) {
                String name = m.group(2);
                int end = findBlockEnd(lines, i);
                var el = makeElement(repoId, filePath, lang, ElementType.INTERFACE, name, name, i + 1, end + 1, extractSnippet(lines, i, end));
                result.addElement(el);
            } else if ((m = TS_FUNCTION.matcher(line)).find()) {
                String name = m.group(3);
                int end = findBlockEnd(lines, i);
                var el = makeElement(repoId, filePath, lang, ElementType.FUNCTION, name, name, i + 1, end + 1, extractSnippet(lines, i, end));
                result.addElement(el);
            } else if ((m = TS_CONST_ARROW.matcher(line)).find()) {
                String name = m.group(3);
                int end = findBlockEnd(lines, i);
                var el = makeElement(repoId, filePath, lang, ElementType.FUNCTION, name, name, i + 1, end + 1, extractSnippet(lines, i, end));
                result.addElement(el);
            } else if ((m = TS_TYPE_ALIAS.matcher(line)).find()) {
                String name = m.group(2);
                var el = makeElement(repoId, filePath, lang, ElementType.CLASS, name, name, i + 1, i + 1, line.trim());
                result.addElement(el);
            } else if ((m = TS_ENUM.matcher(line)).find()) {
                String name = m.group(2);
                int end = findBlockEnd(lines, i);
                var el = makeElement(repoId, filePath, lang, ElementType.ENUM, name, name, i + 1, end + 1, extractSnippet(lines, i, end));
                result.addElement(el);
            }
        }
        return result;
    }

    // ---- C / C++ -----------------------------------------------------------

    private static final Pattern C_FUNCTION = Pattern.compile(
            "^\\s*(?:static\\s+|extern\\s+|inline\\s+)*(?:\\w+[\\s*]+)+(\\w+)\\s*\\([^;]*\\)\\s*\\{");
    private static final Pattern CPP_CLASS = Pattern.compile(
            "^\\s*(?:template\\s*<[^>]*>\\s+)?class\\s+(\\w+)");
    private static final Pattern C_STRUCT = Pattern.compile(
            "^\\s*(?:typedef\\s+)?struct\\s+(\\w+)?\\s*\\{");
    private static final Pattern C_ENUM = Pattern.compile(
            "^\\s*(?:typedef\\s+)?enum\\s+(\\w+)?\\s*\\{");

    public static ParseResult parseC(String repoId, String filePath, String[] lines,
                                      Language lang, ParseResult result) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m;

            if ((m = CPP_CLASS.matcher(line)).find() && lang == Language.CPP) {
                String name = m.group(1);
                int end = findBlockEnd(lines, i);
                var el = makeElement(repoId, filePath, lang, ElementType.CLASS, name, name, i + 1, end + 1, extractSnippet(lines, i, end));
                result.addElement(el);
            } else if ((m = C_STRUCT.matcher(line)).find()) {
                String name = m.group(1);
                if (name == null || name.isBlank()) continue;
                int end = findBlockEnd(lines, i);
                var el = makeElement(repoId, filePath, lang, ElementType.STRUCT, name, name, i + 1, end + 1, extractSnippet(lines, i, end));
                result.addElement(el);
            } else if ((m = C_ENUM.matcher(line)).find()) {
                String name = m.group(1);
                if (name == null || name.isBlank()) continue;
                int end = findBlockEnd(lines, i);
                var el = makeElement(repoId, filePath, lang, ElementType.ENUM, name, name, i + 1, end + 1, extractSnippet(lines, i, end));
                result.addElement(el);
            } else if ((m = C_FUNCTION.matcher(line)).find()) {
                String name = m.group(1);
                // Skip common false positives
                if (name.equals("if") || name.equals("for") || name.equals("while") ||
                    name.equals("switch") || name.equals("catch") || name.equals("return")) continue;
                int end = findBlockEnd(lines, i);
                var el = makeElement(repoId, filePath, lang, ElementType.FUNCTION, name, name, i + 1, end + 1, extractSnippet(lines, i, end));
                result.addElement(el);
            }
        }
        return result;
    }

    // ---- Shared helpers ----------------------------------------------------

    private static CodeElement makeElement(String repoId, String filePath, Language lang,
                                            ElementType type, String name, String qualifiedName,
                                            int lineStart, int lineEnd, String snippet) {
        var el = new CodeElement();
        el.setRepoId(repoId);
        el.setLanguage(lang);
        el.setElementType(type);
        el.setName(name);
        el.setQualifiedName(qualifiedName);
        el.setFilePath(filePath);
        el.setLineStart(lineStart);
        el.setLineEnd(lineEnd);
        el.setSnippet(TreeSitterParser.truncate(snippet, TreeSitterParser.MAX_SNIPPET_LINES));
        el.setId(CodeElement.generateId(repoId, filePath, type, qualifiedName));
        return el;
    }

    private static String buildQName(String pkg, String receiver, String name) {
        var sb = new StringBuilder();
        if (!pkg.isEmpty()) sb.append(pkg).append(".");
        if (receiver != null) sb.append(receiver).append(".");
        sb.append(name);
        return sb.toString();
    }

    /**
     * Find the line where the brace-delimited block starting at {@code startLine}
     * closes. If no opening brace is found, returns startLine.
     */
    static int findBlockEnd(String[] lines, int startLine) {
        int depth = 0;
        boolean found = false;
        for (int i = startLine; i < lines.length; i++) {
            for (char c : lines[i].toCharArray()) {
                if (c == '{') { depth++; found = true; }
                else if (c == '}') { depth--; }
                if (found && depth == 0) return i;
            }
        }
        return startLine;
    }

    static String extractSnippet(String[] lines, int start, int end) {
        int snippetEnd = Math.min(end, start + TreeSitterParser.MAX_SNIPPET_LINES - 1);
        var sb = new StringBuilder();
        for (int i = start; i <= snippetEnd && i < lines.length; i++) {
            if (i > start) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }
}
