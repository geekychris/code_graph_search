package com.codegraph.parser.markdown;

import com.codegraph.core.model.*;
import com.codegraph.parser.LanguageParser;
import com.codegraph.parser.ParseResult;
import com.vladsch.flexmark.ast.*;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses Markdown files using flexmark-java.
 *
 * <p>Creates:
 * <ul>
 *   <li>{@code MARKDOWN_DOCUMENT} element for the file itself</li>
 *   <li>{@code MARKDOWN_HEADING} elements for each H1–H6</li>
 *   <li>{@code MARKDOWN_SECTION} elements for the content between headings</li>
 *   <li>{@code CONTAINS} edges: document→heading, heading→section</li>
 *   <li>{@code SECTION_OF} edges: heading→document</li>
 *   <li>{@code DOCUMENTS_DIR} edge if the file is a README</li>
 * </ul>
 */
public class MarkdownFileParser implements LanguageParser {

    private static final Logger log = LoggerFactory.getLogger(MarkdownFileParser.class);
    private static final Pattern README_PATTERN =
            Pattern.compile("^readme(\\.md|\\.markdown)?$", Pattern.CASE_INSENSITIVE);

    @Override
    public Language getLanguage() {
        return Language.MARKDOWN;
    }

    @Override
    public boolean canParse(Path file) {
        var name = file.getFileName().toString();
        return name.endsWith(".md") || name.endsWith(".markdown");
    }

    @Override
    public ParseResult parse(Path file, String repoId, Path repoRoot) {
        var relativePath = repoRoot.relativize(file).toString();
        var result = new ParseResult(relativePath);

        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Cannot read {}: {}", relativePath, e.getMessage());
            result.setError("IO error: " + e.getMessage());
            return result;
        }

        try {
            parseContent(content, relativePath, repoId, repoRoot, file, result);
        } catch (Exception e) {
            log.error("Failed to parse markdown {}: {}", relativePath, e.getMessage(), e);
            result.setError("Parse error: " + e.getMessage());
        }
        return result;
    }

    // -------------------------------------------------------------------------

    private void parseContent(String content, String relativePath, String repoId,
                               Path repoRoot, Path file, ParseResult result) {
        var options = new MutableDataSet();
        var parser = Parser.builder(options).build();
        var document = parser.parse(content);

        // MARKDOWN_DOCUMENT element
        var docQName = relativePath;
        var docEl = new CodeElement();
        docEl.setRepoId(repoId);
        docEl.setLanguage(Language.MARKDOWN);
        docEl.setElementType(ElementType.MARKDOWN_DOCUMENT);
        docEl.setQualifiedName(docQName);
        docEl.setFilePath(relativePath);
        docEl.setName(file.getFileName().toString());
        docEl.setId(CodeElement.generateId(repoId, relativePath, ElementType.MARKDOWN_DOCUMENT, docQName));
        docEl.setSnippet(content);
        docEl.setLineStart(1);
        docEl.setLineEnd(content.split("\n", -1).length);
        result.addElement(docEl);

        // README → DOCUMENTS_DIR
        var fileName = file.getFileName().toString();
        if (README_PATTERN.matcher(fileName).matches()) {
            var parentDir = file.getParent();
            if (parentDir != null) {
                var dirRelPath = repoRoot.relativize(parentDir).toString();
                var dirQName = dirRelPath.isEmpty() ? "." : dirRelPath;
                var dirId = CodeElement.generateId(repoId, dirRelPath, ElementType.DIRECTORY, dirQName);
                result.addEdge(new CodeEdge(docEl.getId(), dirId, EdgeType.DOCUMENTS_DIR));
            }
        }

        // Walk headings and build sections
        var lines = content.split("\n", -1);
        var sections = collectSections(document, lines, content);

        CodeElement prevHeadingEl = null;

        for (var section : sections) {
            // Heading element
            var headingQName = relativePath + "#heading:" + section.headingText + "@" + section.headingLine;
            var headingEl = new CodeElement();
            headingEl.setRepoId(repoId);
            headingEl.setLanguage(Language.MARKDOWN);
            headingEl.setElementType(ElementType.MARKDOWN_HEADING);
            headingEl.setQualifiedName(headingQName);
            headingEl.setFilePath(relativePath);
            headingEl.setName(section.headingText);
            headingEl.setId(CodeElement.generateId(repoId, relativePath,
                    ElementType.MARKDOWN_HEADING, headingQName));
            headingEl.setLineStart(section.headingLine);
            headingEl.setLineEnd(section.headingLine);
            headingEl.addMetadata("level", String.valueOf(section.level));
            headingEl.setSnippet(section.headingRaw);
            result.addElement(headingEl);

            // CONTAINS: document → heading
            result.addEdge(new CodeEdge(docEl.getId(), headingEl.getId(), EdgeType.CONTAINS));
            // SECTION_OF: heading → document
            result.addEdge(new CodeEdge(headingEl.getId(), docEl.getId(), EdgeType.SECTION_OF));

            // PRECEDES
            if (prevHeadingEl != null) {
                result.addEdge(new CodeEdge(prevHeadingEl.getId(), headingEl.getId(), EdgeType.PRECEDES));
            }
            prevHeadingEl = headingEl;

            // Section element (body text after heading)
            if (!section.bodyText.isBlank()) {
                var sectionQName = relativePath + "#section:" + section.headingText + "@" + section.headingLine;
                var sectionEl = new CodeElement();
                sectionEl.setRepoId(repoId);
                sectionEl.setLanguage(Language.MARKDOWN);
                sectionEl.setElementType(ElementType.MARKDOWN_SECTION);
                sectionEl.setQualifiedName(sectionQName);
                sectionEl.setFilePath(relativePath);
                sectionEl.setName(section.headingText);
                sectionEl.setId(CodeElement.generateId(repoId, relativePath,
                        ElementType.MARKDOWN_SECTION, sectionQName));
                sectionEl.setLineStart(section.bodyStartLine);
                sectionEl.setLineEnd(section.bodyEndLine);
                sectionEl.setSnippet(section.bodyText);
                result.addElement(sectionEl);

                // CONTAINS: heading → section
                result.addEdge(new CodeEdge(headingEl.getId(), sectionEl.getId(), EdgeType.CONTAINS));
            }
        }

        // If there is content before the first heading, emit a preamble section
        if (!sections.isEmpty() && sections.get(0).headingLine > 1) {
            // preamble from line 1 to first heading - 1
            var preamble = extractLines(lines, 1, sections.get(0).headingLine - 1);
            if (!preamble.isBlank()) {
                var preQName = relativePath + "#preamble";
                var preEl = new CodeElement();
                preEl.setRepoId(repoId);
                preEl.setLanguage(Language.MARKDOWN);
                preEl.setElementType(ElementType.MARKDOWN_SECTION);
                preEl.setQualifiedName(preQName);
                preEl.setFilePath(relativePath);
                preEl.setName("(preamble)");
                preEl.setId(CodeElement.generateId(repoId, relativePath,
                        ElementType.MARKDOWN_SECTION, preQName));
                preEl.setLineStart(1);
                preEl.setLineEnd(sections.get(0).headingLine - 1);
                preEl.setSnippet(preamble);
                result.addElement(preEl);
                result.addEdge(new CodeEdge(docEl.getId(), preEl.getId(), EdgeType.CONTAINS));
            }
        } else if (sections.isEmpty() && !content.isBlank()) {
            // No headings: the whole doc is one section
            var preQName = relativePath + "#content";
            var preEl = new CodeElement();
            preEl.setRepoId(repoId);
            preEl.setLanguage(Language.MARKDOWN);
            preEl.setElementType(ElementType.MARKDOWN_SECTION);
            preEl.setQualifiedName(preQName);
            preEl.setFilePath(relativePath);
            preEl.setName("(content)");
            preEl.setId(CodeElement.generateId(repoId, relativePath,
                    ElementType.MARKDOWN_SECTION, preQName));
            preEl.setLineStart(1);
            preEl.setLineEnd(lines.length);
            preEl.setSnippet(content);
            result.addElement(preEl);
            result.addEdge(new CodeEdge(docEl.getId(), preEl.getId(), EdgeType.CONTAINS));
        }
    }

    // -------------------------------------------------------------------------
    // Section extraction
    // -------------------------------------------------------------------------

    private record Section(
            int level,
            String headingText,
            String headingRaw,
            int headingLine,
            String bodyText,
            int bodyStartLine,
            int bodyEndLine
    ) {}

    private List<Section> collectSections(Document document, String[] lines, String fullContent) {
        // Collect headings in order
        var headings = new ArrayList<HeadingInfo>();
        document.getChildIterator().forEachRemaining(node -> collectHeadings(node, lines, headings));

        var sections = new ArrayList<Section>();
        for (int i = 0; i < headings.size(); i++) {
            var h = headings.get(i);
            int bodyStart = h.lineNumber + 1;
            int bodyEnd = (i + 1 < headings.size()) ? headings.get(i + 1).lineNumber - 1 : lines.length;
            var bodyText = extractLines(lines, bodyStart, bodyEnd).stripTrailing();
            sections.add(new Section(h.level, h.text, h.raw, h.lineNumber, bodyText, bodyStart, bodyEnd));
        }
        return sections;
    }

    private record HeadingInfo(int level, String text, String raw, int lineNumber) {}

    private void collectHeadings(Node node, String[] lines, List<HeadingInfo> acc) {
        if (node instanceof Heading heading) {
            int level = heading.getLevel();
            var text = heading.getText().toString().trim();
            int lineNum = heading.getLineNumber();
            String raw = (lineNum >= 1 && lineNum <= lines.length) ? lines[lineNum - 1] : text;
            acc.add(new HeadingInfo(level, text, raw, lineNum));
        }
        for (var child = node.getFirstChild(); child != null; child = child.getNext()) {
            collectHeadings(child, lines, acc);
        }
    }

    /** Extract lines[startLine-1 .. endLine-1] (1-indexed, inclusive). */
    private static String extractLines(String[] lines, int startLine, int endLine) {
        if (startLine > endLine || startLine < 1) return "";
        int from = startLine - 1;
        int to = Math.min(endLine, lines.length);
        if (from >= to) return "";
        var sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }
}
