package com.codegraph.parser.treesitter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the S-expression text produced by {@code tree-sitter parse <file>} into a
 * tree of {@link SExprNode} objects, and fills in each node's {@code text} field by
 * slicing the original source lines.
 *
 * <h3>Grammar of the S-expression format (simplified)</h3>
 * <pre>
 * sexp     ::= '(' nodeType position children* ')'
 * nodeType ::= identifier
 * position ::= '[' line ',' col ']' ' - ' '[' line ',' col ']'
 * children ::= field? sexp | literal
 * field    ::= identifier ':'
 * literal  ::= anything outside parentheses (ignored / treated as text)
 * </pre>
 *
 * <p>Tree-sitter positions are 0-indexed. This class preserves them as-is; callers
 * should add 1 when converting to {@link com.codegraph.core.model.CodeElement} line fields.
 */
public class SExprParser {

    private static final Logger log = LoggerFactory.getLogger(SExprParser.class);

    /**
     * Parse the full S-expression string and return the root {@link SExprNode}.
     *
     * @param sexp        the raw output of {@code tree-sitter parse}
     * @param sourceLines the source file split by newlines (used to fill in {@code text})
     */
    public SExprNode parse(String sexp, String[] sourceLines) {
        if (sexp == null || sexp.isBlank()) return null;
        var ctx = new ParseContext(sexp.toCharArray());
        ctx.skipWhitespace();
        SExprNode root = null;
        try {
            root = parseNode(ctx, null);
        } catch (Exception e) {
            log.warn("S-expression parse error: {}", e.getMessage());
            return null;
        }
        if (root != null) {
            fillText(root, sourceLines);
        }
        return root;
    }

    // -------------------------------------------------------------------------
    // Recursive descent parser
    // -------------------------------------------------------------------------

    private SExprNode parseNode(ParseContext ctx, String fieldName) {
        if (!ctx.hasMore()) return null;
        ctx.skipWhitespace();
        if (!ctx.hasMore() || ctx.current() != '(') return null;
        ctx.advance(); // consume '('

        // node type
        var type = ctx.readIdentifier();
        if (type.isEmpty()) {
            // may be an ERROR node with a quoted token or something unexpected
            ctx.skipUntilClose();
            return null;
        }

        ctx.skipWhitespace();

        // position: [line, col] - [line, col]
        int sl = 0, sc = 0, el = 0, ec = 0;
        if (ctx.hasMore() && ctx.current() == '[') {
            ctx.advance(); // '['
            sl = ctx.readInt();
            ctx.skipExpected(',');
            sc = ctx.readInt();
            ctx.skipExpected(']');
            ctx.skipWhitespace();
            ctx.skipExpected('-');
            ctx.skipWhitespace();
            ctx.skipExpected('[');
            el = ctx.readInt();
            ctx.skipExpected(',');
            ec = ctx.readInt();
            ctx.skipExpected(']');
        }

        var node = new SExprNode(type, fieldName, sl, sc, el, ec);

        // Children (possibly prefixed by "fieldName:")
        while (true) {
            ctx.skipWhitespace();
            if (!ctx.hasMore()) break;
            char c = ctx.current();
            if (c == ')') {
                ctx.advance(); // consume ')'
                break;
            }
            if (c == '(') {
                // unnamed child
                var child = parseNode(ctx, null);
                if (child != null) node.addChild(child);
            } else {
                // Could be "fieldName: (" or end-of-line text / ERROR nodes
                // Try to read an identifier to see if it's a field label
                int saved = ctx.pos;
                var label = ctx.readIdentifier();
                ctx.skipWhitespace();
                if (!label.isEmpty() && ctx.hasMore() && ctx.current() == ':') {
                    ctx.advance(); // consume ':'
                    ctx.skipWhitespace();
                    if (ctx.hasMore() && ctx.current() == '(') {
                        var child = parseNode(ctx, label);
                        if (child != null) node.addChild(child);
                    } else {
                        // field value is a literal string (e.g. in tree-sitter ERROR nodes)
                        // skip to next newline or paren
                        ctx.skipToNextChildOrClose();
                    }
                } else {
                    // Not a field label; skip this token
                    ctx.pos = saved;
                    ctx.skipToNextChildOrClose();
                }
            }
        }

        return node;
    }

    // -------------------------------------------------------------------------
    // Text extraction
    // -------------------------------------------------------------------------

    private void fillText(SExprNode node, String[] sourceLines) {
        if (sourceLines == null || sourceLines.length == 0) return;
        // Fill text for all nodes; for compound nodes this is the full span
        node.setText(extractSpan(node.getStartLine(), node.getStartCol(),
                node.getEndLine(), node.getEndCol(), sourceLines));
        for (var child : node.getChildren()) {
            fillText(child, sourceLines);
        }
    }

    /**
     * Extracts source text from {@code lines} in the range
     * {@code [startLine:startCol, endLine:endCol)} (0-indexed lines, columns).
     */
    static String extractSpan(int startLine, int startCol,
                               int endLine, int endCol,
                               String[] lines) {
        if (startLine >= lines.length) return "";
        if (startLine == endLine) {
            var line = lines[startLine];
            int from = Math.min(startCol, line.length());
            int to   = Math.min(endCol, line.length());
            return from >= to ? "" : line.substring(from, to);
        }
        var sb = new StringBuilder();
        // First line: from startCol to end of line
        var first = lines[startLine];
        sb.append(first.substring(Math.min(startCol, first.length())));
        // Middle lines: full
        for (int i = startLine + 1; i < endLine && i < lines.length; i++) {
            sb.append('\n').append(lines[i]);
        }
        // Last line: from 0 to endCol
        if (endLine < lines.length) {
            var last = lines[endLine];
            sb.append('\n').append(last, 0, Math.min(endCol, last.length()));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // ParseContext
    // -------------------------------------------------------------------------

    private static class ParseContext {
        final char[] chars;
        int pos;

        ParseContext(char[] chars) {
            this.chars = chars;
        }

        boolean hasMore()          { return pos < chars.length; }
        char current()             { return chars[pos]; }
        void advance()             { pos++; }

        void skipWhitespace() {
            while (pos < chars.length && Character.isWhitespace(chars[pos])) pos++;
        }

        void skipExpected(char expected) {
            skipWhitespace();
            if (pos < chars.length && chars[pos] == expected) pos++;
        }

        /** Read a word composed of letters, digits, '_', '.', '-'. */
        String readIdentifier() {
            int start = pos;
            while (pos < chars.length) {
                char c = chars[pos];
                if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') pos++;
                else break;
            }
            return new String(chars, start, pos - start);
        }

        int readInt() {
            skipWhitespace();
            int start = pos;
            while (pos < chars.length && Character.isDigit(chars[pos])) pos++;
            if (start == pos) return 0;
            return Integer.parseInt(new String(chars, start, pos - start));
        }

        /** Skip until we reach the next '(' or ')' or end of input. */
        void skipToNextChildOrClose() {
            while (pos < chars.length) {
                char c = chars[pos];
                if (c == '(' || c == ')') break;
                pos++;
            }
        }

        /** Skip to the matching ')' (assumes we just consumed the '(' already). */
        void skipUntilClose() {
            int depth = 1;
            while (pos < chars.length && depth > 0) {
                char c = chars[pos++];
                if (c == '(') depth++;
                else if (c == ')') depth--;
            }
        }
    }
}
