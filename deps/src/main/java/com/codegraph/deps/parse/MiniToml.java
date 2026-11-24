package com.codegraph.deps.parse;

import java.util.*;

/**
 * A small TOML reader, enough for {@code Cargo.toml}, {@code pyproject.toml} and Gradle
 * version catalogs: tables, arrays of tables, dotted keys, basic and literal strings
 * (including multi-line), numbers and booleans (kept as text), arrays and inline tables.
 * Dates and other exotic values are kept as raw text. Never throws on odd input;
 * unreadable lines are skipped.
 */
public final class MiniToml {

    private final String src;
    private int pos;

    private MiniToml(String src) {
        this.src = src;
    }

    /** Parses TOML text into nested maps; arrays become lists. */
    public static Map<String, Object> parse(String text) {
        return new MiniToml(text).document();
    }

    // -------------------------------------------------------------------------

    private Map<String, Object> document() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        while (pos < src.length()) {
            skipWsAndNewlines();
            if (pos >= src.length()) break;
            char c = src.charAt(pos);
            try {
                if (c == '[') {
                    boolean arrayTable = peek(1) == '[';
                    pos += arrayTable ? 2 : 1;
                    List<String> path = keyPath();
                    skipInline();
                    expect(']');
                    if (arrayTable) expect(']');
                    current = arrayTable ? appendTable(root, path) : table(root, path);
                    skipToLineEnd();
                } else {
                    List<String> path = keyPath();
                    skipInline();
                    expect('=');
                    skipInline();
                    Object value = value();
                    Map<String, Object> target = path.size() > 1 ? table(current, path.subList(0, path.size() - 1)) : current;
                    target.put(path.get(path.size() - 1), value);
                    skipToLineEnd();
                }
            } catch (RuntimeException e) {
                skipToLineEnd();
            }
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> table(Map<String, Object> root, List<String> path) {
        Map<String, Object> t = root;
        for (String k : path) {
            Object next = t.get(k);
            if (next instanceof List<?> list && !list.isEmpty() && list.get(list.size() - 1) instanceof Map) {
                t = (Map<String, Object>) list.get(list.size() - 1);
            } else if (next instanceof Map) {
                t = (Map<String, Object>) next;
            } else {
                Map<String, Object> created = new LinkedHashMap<>();
                t.put(k, created);
                t = created;
            }
        }
        return t;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> appendTable(Map<String, Object> root, List<String> path) {
        Map<String, Object> parent = table(root, path.subList(0, path.size() - 1));
        String last = path.get(path.size() - 1);
        Object existing = parent.get(last);
        List<Object> list = existing instanceof List ? (List<Object>) existing : new ArrayList<>();
        parent.put(last, list);
        Map<String, Object> entry = new LinkedHashMap<>();
        list.add(entry);
        return entry;
    }

    private List<String> keyPath() {
        List<String> parts = new ArrayList<>();
        while (true) {
            skipInline();
            parts.add(key());
            skipInline();
            if (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
            } else {
                return parts;
            }
        }
    }

    private String key() {
        char c = src.charAt(pos);
        if (c == '"' || c == '\'') return (String) string();
        int start = pos;
        while (pos < src.length()) {
            char k = src.charAt(pos);
            if (Character.isLetterOrDigit(k) || k == '_' || k == '-') pos++;
            else break;
        }
        if (start == pos) throw new IllegalStateException("bad key");
        return src.substring(start, pos);
    }

    private Object value() {
        char c = src.charAt(pos);
        if (c == '"' || c == '\'') return string();
        if (c == '[') return array();
        if (c == '{') return inlineTable();
        int start = pos;
        while (pos < src.length()) {
            char k = src.charAt(pos);
            if (k == ',' || k == ']' || k == '}' || k == '\n' || k == '#') break;
            pos++;
        }
        return src.substring(start, pos).trim();
    }

    private Object string() {
        char q = src.charAt(pos);
        boolean multi = peek(1) == q && peek(2) == q;
        if (multi) {
            pos += 3;
            if (pos < src.length() && src.charAt(pos) == '\n') pos++;
            String end = String.valueOf(q).repeat(3);
            int close = src.indexOf(end, pos);
            if (close < 0) close = src.length();
            String raw = src.substring(pos, close);
            pos = Math.min(src.length(), close + 3);
            return q == '"' ? unescape(raw) : raw;
        }
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char k = src.charAt(pos++);
            if (k == q) return sb.toString();
            if (k == '\n') break;
            if (k == '\\' && q == '"' && pos < src.length()) {
                char e = src.charAt(pos++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append('\\').append(e);
                }
            } else {
                sb.append(k);
            }
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
    }

    private List<Object> array() {
        pos++;
        List<Object> list = new ArrayList<>();
        while (true) {
            skipWsAndNewlines();
            if (pos >= src.length()) return list;
            if (src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            list.add(value());
            skipWsAndNewlines();
            if (pos < src.length() && src.charAt(pos) == ',') pos++;
        }
    }

    private Map<String, Object> inlineTable() {
        pos++;
        Map<String, Object> t = new LinkedHashMap<>();
        while (true) {
            skipInline();
            if (pos >= src.length()) return t;
            if (src.charAt(pos) == '}') {
                pos++;
                return t;
            }
            List<String> path = keyPath();
            skipInline();
            expect('=');
            skipInline();
            Object v = value();
            Map<String, Object> target = path.size() > 1 ? table(t, path.subList(0, path.size() - 1)) : t;
            target.put(path.get(path.size() - 1), v);
            skipInline();
            if (pos < src.length() && src.charAt(pos) == ',') pos++;
        }
    }

    // -------------------------------------------------------------------------

    private char peek(int ahead) {
        int i = pos + ahead;
        return i < src.length() ? src.charAt(i) : '\0';
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) throw new IllegalStateException("expected " + c);
        pos++;
    }

    private void skipInline() {
        while (pos < src.length() && (src.charAt(pos) == ' ' || src.charAt(pos) == '\t')) pos++;
    }

    /** Skips whitespace, newlines and comments. */
    private void skipWsAndNewlines() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '#') {
                while (pos < src.length() && src.charAt(pos) != '\n') pos++;
            } else if (Character.isWhitespace(c)) {
                pos++;
            } else {
                return;
            }
        }
    }

    private void skipToLineEnd() {
        while (pos < src.length() && src.charAt(pos) != '\n') pos++;
    }

    // ---- typed accessors used by the parsers ----

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    /** Value at a dotted path such as {@code "tool.poetry.name"}. */
    static Object at(Map<String, Object> root, String dotted) {
        Object cur = root;
        for (String k : dotted.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(k);
        }
        return cur;
    }

    static Optional<String> text(Map<String, Object> root, String dotted) {
        return Optional.ofNullable(str(at(root, dotted)));
    }
}
