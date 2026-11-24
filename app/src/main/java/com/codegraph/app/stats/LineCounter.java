package com.codegraph.app.stats;

import com.codegraph.app.stats.FileClassifier.Comments;

/**
 * Counts code, comment and blank lines. A line is a comment when, trimmed, it
 * starts with the language's comment marker or lies inside a block comment
 * that started at the beginning of a line; a line with code and a trailing
 * comment counts as code. Good enough for size and ratios; not a parser
 * (docs/STATS.md, "How lines are counted").
 */
public final class LineCounter {

    private LineCounter() {}

    /** code, comment and blank line counts. */
    public record Lines(long code, long comment, long blank) {
        public long total() { return code + comment + blank; }
    }

    public static Lines count(CharSequence text, Comments style) {
        long code = 0, comment = 0, blank = 0;
        String blockEnd = null;
        int n = text.length(), start = 0;
        while (start <= n) {
            int end = start;
            while (end < n && text.charAt(end) != '\n') end++;
            if (start == n && end == n) break; // no partial line after a final newline
            String line = text.subSequence(start, end).toString();
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            String t = line.strip();
            start = end + 1;

            if (blockEnd != null) {
                comment++;
                if (t.contains(blockEnd)) blockEnd = null;
                continue;
            }
            if (t.isEmpty()) { blank++; continue; }
            String opened = blockStart(t, style);
            if (opened != null) {
                comment++;
                String close = closer(opened);
                // closes on the same line (after the opener)?
                if (!t.substring(opened.length()).contains(close)) blockEnd = close;
                continue;
            }
            if (isLineComment(t, line, style)) comment++;
            else code++;
        }
        return new Lines(code, comment, blank);
    }

    private static String blockStart(String t, Comments s) {
        return switch (s) {
            case C, SQL -> t.startsWith("/*") ? "/*" : null;
            case XML -> t.startsWith("<!--") ? "<!--" : null;
            case LUA -> t.startsWith("--[[") ? "--[[" : null;
            case HASKELL -> t.startsWith("{-") ? "{-" : null;
            case PASCAL -> t.startsWith("(*") ? "(*" : t.startsWith("{") ? "{" : null;
            case ML -> t.startsWith("(*") ? "(*" : null;
            default -> null;
        };
    }

    private static String closer(String opener) {
        return switch (opener) {
            case "/*" -> "*/";
            case "<!--" -> "-->";
            case "--[[" -> "]]";
            case "{-" -> "-}";
            case "(*" -> "*)";
            case "{" -> "}";
            default -> opener;
        };
    }

    private static boolean isLineComment(String t, String raw, Comments s) {
        return switch (s) {
            case C -> t.startsWith("//");
            case HASH -> t.startsWith("#");
            case SQL, LUA, HASKELL -> t.startsWith("--");
            case SEMI -> t.startsWith(";") || t.startsWith("#");
            case ASM -> t.startsWith(";") || raw.startsWith("*") || t.startsWith("#") || t.startsWith("|");
            case PERCENT -> t.startsWith("%");
            case BASIC -> t.startsWith("'") || t.regionMatches(true, 0, "REM", 0, 3)
                    && (t.length() == 3 || !Character.isLetterOrDigit(t.charAt(3)));
            case PASCAL -> t.startsWith("//");
            case BATCH -> t.startsWith("::") || t.regionMatches(true, 0, "REM", 0, 3)
                    && (t.length() == 3 || t.charAt(3) == ' ');
            default -> false;
        };
    }
}
