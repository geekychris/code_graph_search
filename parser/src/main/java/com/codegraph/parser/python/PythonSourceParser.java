package com.codegraph.parser.python;

import com.codegraph.core.model.*;
import com.codegraph.parser.LanguageParser;
import com.codegraph.parser.ParseResult;
import com.codegraph.parser.treesitter.TreeSitterParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Python source, in plain Java (no tree-sitter binary needed).
 *
 * <p>The source is split into logical lines (strings, comments, brackets and line
 * continuations handled) and walked by indentation. It extracts:
 * <ul>
 *   <li>a MODULE per file, named by its package path ({@code pkg/sub/mod.py} is
 *       {@code pkg.sub.mod} when {@code pkg/} and {@code pkg/sub/} hold
 *       {@code __init__.py}; {@code __init__.py} is the package itself)</li>
 *   <li>classes, methods, nested functions and module-level functions, with
 *       signatures, decorators, docstrings and line ranges</li>
 *   <li>class attributes and {@code self.x} assignments in {@code __init__} as fields</li>
 *   <li>imports, as IMPORT elements and IMPORTS edges to the imported module</li>
 *   <li>EXTENDS edges to base classes, and CALLS / INSTANTIATES edges for calls that
 *       resolve through imports, this module's definitions, {@code self} or {@code cls}</li>
 * </ul>
 * Edges to other files carry the target's qualified name and type as metadata, so
 * cross-repo resolution can link them later.
 */
public class PythonSourceParser implements LanguageParser {

    private static final Logger log = LoggerFactory.getLogger(PythonSourceParser.class);

    private static final Set<String> NOT_CALLS = Set.of(
            "if", "elif", "while", "for", "return", "yield", "assert", "not", "and", "or", "in", "is", "lambda",
            "await", "print", "del", "with", "except", "raise", "global", "nonlocal", "import", "from", "async",
            "len", "str", "int", "float", "bool", "list", "dict", "set", "tuple", "range", "enumerate", "zip",
            "isinstance", "issubclass", "getattr", "setattr", "hasattr", "super", "type", "open", "min", "max",
            "sum", "sorted", "reversed", "map", "filter", "any", "all", "repr", "iter", "next", "id", "abs",
            "round", "format", "vars", "dir", "callable", "hash", "bytes", "bytearray", "frozenset", "object",
            "staticmethod", "classmethod", "property", "Exception", "ValueError", "TypeError", "KeyError",
            "RuntimeError", "NotImplementedError", "AttributeError", "IndexError", "OSError", "IOError");

    private static final Pattern DEF = Pattern.compile("^(async\\s+)?def\\s+([A-Za-z_]\\w*)\\s*\\((.*)\\)\\s*(->\\s*(.+?))?\\s*:\\s*(.*)$", Pattern.DOTALL);
    private static final Pattern CLASS = Pattern.compile("^class\\s+([A-Za-z_]\\w*)\\s*(\\((.*)\\))?\\s*:\\s*(.*)$", Pattern.DOTALL);
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(.+)$", Pattern.DOTALL);
    private static final Pattern FROM_IMPORT = Pattern.compile("^from\\s+(\\.*[\\w.]*)\\s+import\\s+(.+)$", Pattern.DOTALL);
    private static final Pattern CALL = Pattern.compile("(?<![\\w.)\\]])([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)\\s*\\(");
    private static final Pattern CLASS_ATTR = Pattern.compile("^([A-Za-z_]\\w*)\\s*(:\\s*[^=]+)?=(?!=)");
    private static final Pattern SELF_ATTR = Pattern.compile("\\bself\\.([A-Za-z_]\\w*)\\s*(:\\s*[^=]+)?=(?!=)");

    @Override
    public Language getLanguage() {
        return Language.PYTHON;
    }

    @Override
    public boolean canParse(Path file) {
        String n = file.getFileName().toString();
        return n.endsWith(".py") || n.endsWith(".pyi");
    }

    @Override
    public ParseResult parse(Path file, String repoId, Path repoRoot) {
        String rel = repoRoot.relativize(file).toString().replace('\\', '/');
        ParseResult result = new ParseResult(rel);
        try {
            String source = read(file);
            new Walker(repoId, rel, moduleName(file, repoRoot), source, result).run();
        } catch (Exception e) {
            log.debug("Python parse failed for {}: {}", rel, e.getMessage());
            result.setError("Python parse failed: " + e.getMessage());
        }
        return result;
    }

    private static String read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    /**
     * The dotted module name: the file's path from the first ancestor directory that
     * isn't a package (has no {@code __init__.py}), so imports and definitions agree.
     */
    static String moduleName(Path file, Path repoRoot) {
        Deque<String> parts = new ArrayDeque<>();
        String name = file.getFileName().toString();
        String stem = name.substring(0, name.lastIndexOf('.'));
        if (!stem.equals("__init__")) parts.push(stem);
        Path dir = file.getParent();
        Path root = repoRoot.toAbsolutePath().normalize();
        while (dir != null && dir.toAbsolutePath().normalize().startsWith(root)
                && !dir.toAbsolutePath().normalize().equals(root)
                && (Files.isRegularFile(dir.resolve("__init__.py")) || Files.isRegularFile(dir.resolve("__init__.pyi")))) {
            parts.push(dir.getFileName().toString());
            dir = dir.getParent();
        }
        if (parts.isEmpty()) parts.push(dir != null && dir.getFileName() != null ? dir.getFileName().toString() : stem);
        return String.join(".", parts);
    }

    // ------------------------------------------------------------------ logical lines

    /** One logical line: its first physical line, indentation, text with strings blanked, and any leading string. */
    record Line(int start, int end, int indent, String code, String docString) {}

    /**
     * Splits source into logical lines. String contents are replaced by spaces in
     * {@code code} (so quotes and brackets inside strings don't confuse the scanner);
     * a line that is just a string literal keeps it as {@code docString}.
     */
    static List<Line> logicalLines(String src) {
        List<Line> out = new ArrayList<>();
        StringBuilder code = new StringBuilder();
        StringBuilder lit = new StringBuilder();
        int line = 1, start = 1, depth = 0, indent = -1;
        boolean onlyString = true, sawString = false;
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (indent < 0) {
                // measure indentation at the start of a logical line
                int col = 0, j = i;
                while (j < n && (src.charAt(j) == ' ' || src.charAt(j) == '\t')) {
                    col += src.charAt(j) == '\t' ? 8 - (col % 8) : 1;
                    j++;
                }
                if (j >= n) break;
                char d = src.charAt(j);
                if (d == '\n' || d == '\r' || d == '#') {      // blank or comment-only line
                    while (j < n && src.charAt(j) != '\n') j++;
                    i = j + 1;
                    line++;
                    continue;
                }
                indent = col;
                start = line;
                i = j;
                continue;
            }
            if (c == '#') {
                while (i < n && src.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '\\' && i + 1 < n && src.charAt(i + 1) == '\n') {
                i += 2;
                line++;
                code.append(' ');
                continue;
            }
            if (c == '\'' || c == '"') {
                // string prefix letters were already appended as code; that's fine
                boolean triple = i + 2 < n && src.charAt(i + 1) == c && src.charAt(i + 2) == c;
                int q = triple ? 3 : 1;
                int j = i + q;
                StringBuilder s = new StringBuilder();
                while (j < n) {
                    char d = src.charAt(j);
                    if (d == '\\' && j + 1 < n) {
                        s.append(d).append(src.charAt(j + 1));
                        if (src.charAt(j + 1) == '\n') line++;
                        j += 2;
                        continue;
                    }
                    if (triple ? (d == c && j + 2 < n + 2 && src.startsWith(String.valueOf(c).repeat(3), j)) : d == c) break;
                    if (d == '\n') {
                        if (!triple) break;        // unterminated single-quoted string: stop at end of line
                        line++;
                    }
                    s.append(d);
                    j++;
                }
                if (!sawString && code.toString().replaceAll("[rRbBuUfF]", "").isBlank()) lit.append(s);
                sawString = true;
                code.append("\"\"");
                i = Math.min(n, j + q);
                continue;
            }
            if (c == '(' || c == '[' || c == '{') depth++;
            if (c == ')' || c == ']' || c == '}') depth = Math.max(0, depth - 1);
            if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && src.charAt(i + 1) == '\n') i++;
                if (depth == 0) {
                    String text = code.toString().strip();
                    onlyString = sawString && text.replaceAll("[rRbBuUfF]", "").equals("\"\"");
                    if (!text.isEmpty()) out.add(new Line(start, line, indent, text, onlyString ? lit.toString() : null));
                    code.setLength(0);
                    lit.setLength(0);
                    sawString = false;
                    indent = -1;
                } else {
                    code.append(' ');
                }
                line++;
                i++;
                continue;
            }
            if (c == ';' && depth == 0) {
                // a; b on one line: split into two logical lines with the same indentation
                String text = code.toString().strip();
                if (!text.isEmpty()) out.add(new Line(start, line, indent, text, null));
                code.setLength(0);
                lit.setLength(0);
                sawString = false;
                i++;
                continue;
            }
            code.append(c);
            i++;
        }
        String text = code.toString().strip();
        if (!text.isEmpty()) {
            boolean only = sawString && text.replaceAll("[rRbBuUfF]", "").equals("\"\"");
            out.add(new Line(start, line, Math.max(indent, 0), text, only ? lit.toString() : null));
        }
        return out;
    }

    // ------------------------------------------------------------------ the walk

    private static final class Scope {
        final CodeElement element;   // null for the module
        final int indent;            // indentation of the scope's header
        final boolean isClass;
        final String qname;
        int bodyIndent = -1;

        Scope(CodeElement element, int indent, boolean isClass, String qname) {
            this.element = element;
            this.indent = indent;
            this.isClass = isClass;
            this.qname = qname;
        }
    }

    private static final class Walker {
        final String repoId, filePath, module, source;
        final String[] physical;
        final ParseResult result;
        final CodeElement moduleEl;
        final Map<String, String> imports = new HashMap<>();        // local name -> qualified name
        final Map<String, ElementType> localDefs = new HashMap<>();  // top-level name -> type
        final Set<String> fields = new HashSet<>();
        final List<String> pendingDecorators = new ArrayList<>();
        final List<Line> lines;
        final Set<String> edgeIds = new HashSet<>();

        Walker(String repoId, String filePath, String module, String source, ParseResult result) {
            this.repoId = repoId;
            this.filePath = filePath;
            this.module = module;
            this.source = source;
            this.physical = source.split("\r?\n", -1);
            this.result = result;
            this.lines = logicalLines(source);
            this.moduleEl = element(ElementType.MODULE, module.substring(module.lastIndexOf('.') + 1), module, 1, physical.length);
            moduleEl.setSnippet(TreeSitterParser.truncate(source, 30));
        }

        void run() {
            // module docstring: the first logical line, if it is a bare string
            if (!lines.isEmpty() && lines.get(0).docString() != null && lines.get(0).indent() == 0) {
                moduleEl.setDocComment(clean(lines.get(0).docString()));
            }
            result.addElement(moduleEl);
            // first pass: top-level names, so calls to later definitions resolve
            for (Line l : lines) {
                if (l.indent() != 0) continue;
                Matcher m;
                if ((m = CLASS.matcher(l.code())).matches()) localDefs.put(m.group(1), ElementType.CLASS);
                else if ((m = DEF.matcher(stripAsync(l.code()))).matches() || (m = DEF.matcher(l.code())).matches()) localDefs.put(m.group(2), ElementType.FUNCTION);
            }

            Deque<Scope> stack = new ArrayDeque<>();
            stack.push(new Scope(null, -1, false, module));
            for (int idx = 0; idx < lines.size(); idx++) {
                Line l = lines.get(idx);
                while (stack.size() > 1 && l.indent() <= stack.peek().indent) close(stack.pop(), l.start() - 1);
                Scope scope = stack.peek();
                if (scope.bodyIndent < 0 && scope.element != null) {
                    scope.bodyIndent = l.indent();
                    // docstring: the first statement of a def or class body
                    if (l.docString() != null) {
                        scope.element.setDocComment(clean(l.docString()));
                        continue;
                    }
                }
                String code = l.code();
                Matcher m;
                if (code.startsWith("@")) {
                    pendingDecorators.add(code.substring(1).replaceAll("\\s+", ""));
                    continue;
                }
                if ((m = CLASS.matcher(code)).matches()) {
                    Scope s = openClass(scope, l, m);
                    stack.push(s);
                    if (!m.group(4).isBlank()) close(stack.pop(), l.end());   // class X: pass
                    continue;
                }
                Matcher d = DEF.matcher(code);
                if (d.matches()) {
                    Scope s = openDef(scope, l, d);
                    stack.push(s);
                    if (!d.group(6).isBlank()) {                               // def f(): return 1
                        calls(s, d.group(6));
                        close(stack.pop(), l.end());
                    }
                    continue;
                }
                pendingDecorators.clear();
                if ((m = FROM_IMPORT.matcher(code)).matches()) {
                    fromImport(scope, l, m.group(1), m.group(2));
                    continue;
                }
                if ((m = IMPORT.matcher(code)).matches()) {
                    plainImport(scope, l, m.group(1));
                    continue;
                }
                if (scope.isClass && l.indent() == scope.bodyIndent) {
                    Matcher a = CLASS_ATTR.matcher(code);
                    if (a.find()) field(scope, a.group(1), l);
                }
                if (scope.element != null && !scope.isClass && "__init__".equals(scope.element.getName())) {
                    Scope owner = ownerClass(stack);
                    if (owner != null) {
                        Matcher a = SELF_ATTR.matcher(code);
                        while (a.find()) field(owner, a.group(1), l);
                    }
                }
                calls(scope, code);
            }
            while (stack.size() > 1) close(stack.pop(), physical.length);
        }

        Scope openClass(Scope parent, Line l, Matcher m) {
            String name = m.group(1);
            String qname = parent.qname + "." + name;
            CodeElement el = element(ElementType.CLASS, name, qname, l.start(), l.end());
            el.setSignature("class " + name + (m.group(2) != null ? "(" + m.group(3).strip() + ")" : ""));
            decorate(el);
            parentOf(parent, el);
            if (m.group(3) != null) {
                for (String base : splitArgs(m.group(3))) {
                    base = base.strip();
                    if (base.isEmpty() || base.contains("=") || base.startsWith("*")) continue;   // metaclass=..., *bases
                    base = base.replaceAll("\\[.*$", "");                                        // Generic[T]
                    String target = resolve(base, null);
                    if (target != null && !target.equals("object")) {
                        edge(el, EdgeType.EXTENDS, ElementType.CLASS, target);
                    }
                }
            }
            result.addElement(el);
            return new Scope(el, l.indent(), true, qname);
        }

        Scope openDef(Scope parent, Line l, Matcher d) {
            String name = d.group(2);
            String qname = parent.qname + "." + name;
            ElementType type = parent.isClass ? (name.equals("__init__") ? ElementType.CONSTRUCTOR : ElementType.METHOD)
                    : ElementType.FUNCTION;
            CodeElement el = element(type, name, qname, l.start(), l.end());
            el.setSignature((d.group(1) != null ? "async " : "") + "def " + name + "(" + d.group(3).strip() + ")"
                    + (d.group(5) != null ? " -> " + d.group(5).strip() : ""));
            if (d.group(5) != null) el.setReturnType(d.group(5).strip());
            List<String> params = new ArrayList<>();
            for (String p : splitArgs(d.group(3))) {
                String pn = p.strip().replaceAll("[:=].*$", "").strip();
                if (!pn.isEmpty() && !pn.equals("self") && !pn.equals("cls") && !pn.equals("/") && !pn.equals("*")) params.add(pn);
            }
            el.setParameterTypes(params);
            if (d.group(1) != null) el.addModifier("async");
            decorate(el);
            if (el.getModifiers() != null && el.getModifiers().contains("staticmethod")) el.addModifier("static");
            parentOf(parent, el);
            result.addElement(el);
            return new Scope(el, l.indent(), false, qname);
        }

        void close(Scope s, int lastLine) {
            CodeElement el = s.element;
            if (el == null) return;
            int end = Math.max(el.getLineStart(), Math.min(lastLine, physical.length));
            // trim trailing blank lines
            while (end > el.getLineStart() && physical[end - 1].isBlank()) end--;
            el.setLineEnd(end);
            el.setSnippet(TreeSitterParser.truncate(String.join("\n",
                    Arrays.asList(physical).subList(el.getLineStart() - 1, end)), TreeSitterParser.MAX_SNIPPET_LINES));
        }

        void decorate(CodeElement el) {
            for (String dec : pendingDecorators) {
                String bare = dec.replaceAll("\\(.*$", "");
                el.addModifier("@" + bare);
                if (bare.equals("staticmethod") || bare.equals("classmethod") || bare.equals("property")) el.addModifier(bare);
                String target = resolve(bare, null);
                if (target != null && target.contains(".")) edge(el, EdgeType.ANNOTATES, ElementType.FUNCTION, target);
            }
            pendingDecorators.clear();
        }

        void field(Scope cls, String name, Line l) {
            String qname = cls.qname + "." + name;
            if (!fields.add(qname)) return;
            CodeElement el = element(ElementType.FIELD, name, qname, l.start(), l.end());
            el.setSnippet(TreeSitterParser.truncate(String.join("\n",
                    Arrays.asList(physical).subList(l.start() - 1, Math.min(l.end(), physical.length))), 5));
            parentOf(cls, el);
            result.addElement(el);
        }

        // ---- imports

        void plainImport(Scope scope, Line l, String spec) {
            for (String part : splitArgs(spec)) {
                String[] as = part.strip().split("\\s+as\\s+");
                String target = as[0].strip();
                if (target.isEmpty()) continue;
                String local = as.length > 1 ? as[1].strip() : target.split("\\.")[0];
                imports.put(local, as.length > 1 ? target : target.split("\\.")[0]);
                importElement(scope, l, target, local);
            }
        }

        void fromImport(Scope scope, Line l, String from, String names) {
            String base = absolute(from);
            if (base == null) return;
            names = names.strip();
            if (names.startsWith("(")) names = names.substring(1, names.lastIndexOf(')') < 0 ? names.length() : names.lastIndexOf(')'));
            for (String part : splitArgs(names)) {
                String[] as = part.strip().split("\\s+as\\s+");
                String name = as[0].strip();
                if (name.isEmpty() || name.equals("*")) continue;
                String local = as.length > 1 ? as[1].strip() : name;
                String target = base.isEmpty() ? name : base + "." + name;
                imports.put(local, target);
                importElement(scope, l, base.isEmpty() ? name : base, local);
            }
        }

        /** {@code .mod} and {@code ..pkg.mod} relative to this module's package. */
        String absolute(String from) {
            int dots = 0;
            while (dots < from.length() && from.charAt(dots) == '.') dots++;
            if (dots == 0) return from;
            String[] parts = module.split("\\.");
            // a package's __init__ is the package itself; a module's package is its parent
            boolean isPackage = filePath.endsWith("__init__.py") || filePath.endsWith("__init__.pyi");
            int keep = parts.length - (isPackage ? dots - 1 : dots);
            if (keep < 0) return null;
            String pkg = String.join(".", Arrays.asList(parts).subList(0, keep));
            String rest = from.substring(dots);
            return pkg.isEmpty() ? rest : rest.isEmpty() ? pkg : pkg + "." + rest;
        }

        void importElement(Scope scope, Line l, String moduleTarget, String local) {
            CodeElement el = element(ElementType.IMPORT, local, module + "#import:" + moduleTarget + ":" + local + "@" + l.start(),
                    l.start(), l.end());
            el.setSnippet(physical[l.start() - 1].strip());
            el.addMetadata("module", moduleTarget);
            result.addElement(el);
            result.addEdge(new CodeEdge(moduleEl.getId(), el.getId(), EdgeType.CONTAINS));
            edge(moduleEl, EdgeType.IMPORTS, ElementType.MODULE, moduleTarget);
        }

        // ---- calls

        void calls(Scope scope, String code) {
            if (scope.isClass) return;
            CodeElement from = scope.element != null ? scope.element : moduleEl;
            Matcher m = CALL.matcher(code);
            while (m.find()) {
                String name = m.group(1);
                String head = name.split("\\.")[0];
                if (NOT_CALLS.contains(name) || (name.indexOf('.') < 0 && NOT_CALLS.contains(head))) continue;
                String target = resolve(name, scope);
                if (target == null) continue;
                String last = target.substring(target.lastIndexOf('.') + 1);
                boolean classLike = !last.isEmpty() && Character.isUpperCase(last.charAt(0));
                if (classLike) {
                    edge(from, EdgeType.INSTANTIATES, ElementType.CLASS, target);
                } else {
                    String owner = target.contains(".") ? target.substring(0, target.lastIndexOf('.')) : "";
                    String ownerLast = owner.substring(owner.lastIndexOf('.') + 1);
                    boolean method = !ownerLast.isEmpty() && Character.isUpperCase(ownerLast.charAt(0));
                    edge(from, EdgeType.CALLS, method ? ElementType.METHOD : ElementType.FUNCTION, target);
                }
            }
        }

        /**
         * The qualified name a dotted reference points at, or null when it can't be
         * known statically (locals, builtins, attributes of values).
         */
        String resolve(String dotted, Scope scope) {
            String[] parts = dotted.split("\\.");
            String head = parts[0];
            String rest = parts.length > 1 ? dotted.substring(head.length() + 1) : "";
            if ((head.equals("self") || head.equals("cls")) && scope != null) {
                if (parts.length != 2) return null;            // self.a.b(): attribute of an attribute
                String cls = enclosingClassQName(scope);
                return cls == null ? null : cls + "." + parts[1];
            }
            if (imports.containsKey(head)) {
                String base = imports.get(head);
                return rest.isEmpty() ? base : base + "." + rest;
            }
            if (localDefs.containsKey(head)) return module + "." + dotted;
            return null;
        }

        String enclosingClassQName(Scope scope) {
            // scope qnames are nested: module.Class.method; the class is the method's parent
            if (scope.isClass) return scope.qname;
            if (scope.element != null && (scope.element.getElementType() == ElementType.METHOD
                    || scope.element.getElementType() == ElementType.CONSTRUCTOR)) {
                return scope.qname.substring(0, scope.qname.lastIndexOf('.'));
            }
            return null;
        }

        Scope ownerClass(Deque<Scope> stack) {
            Iterator<Scope> it = stack.iterator();
            it.next();                                         // the __init__ itself
            if (it.hasNext()) {
                Scope s = it.next();
                return s.isClass ? s : null;
            }
            return null;
        }

        // ---- helpers

        CodeElement element(ElementType type, String name, String qname, int start, int end) {
            CodeElement el = new CodeElement();
            el.setRepoId(repoId);
            el.setLanguage(Language.PYTHON);
            el.setElementType(type);
            el.setName(name);
            el.setQualifiedName(qname);
            el.setFilePath(filePath);
            el.setLineStart(start);
            el.setLineEnd(end);
            el.setVisibility(name.startsWith("__") && name.endsWith("__") ? "public"
                    : name.startsWith("_") ? "private" : "public");
            el.setId(CodeElement.generateId(repoId, filePath, type, qname));
            return el;
        }

        void parentOf(Scope parent, CodeElement child) {
            CodeElement p = parent.element != null ? parent.element : moduleEl;
            child.setParentId(p.getId());
            result.addEdge(new CodeEdge(p.getId(), child.getId(), EdgeType.CONTAINS));
        }

        void edge(CodeElement from, EdgeType type, ElementType targetType, String targetQName) {
            String toId = CodeElement.generateId(repoId, filePath, targetType, targetQName);
            if (toId.equals(from.getId())) return;
            CodeEdge e = new CodeEdge(from.getId(), toId, type);
            if (!edgeIds.add(e.getId())) return;
            Map<String, String> meta = new HashMap<>();
            meta.put(CodeEdge.META_TARGET_QNAME, targetQName);
            meta.put(CodeEdge.META_TARGET_TYPE, targetType.name());
            e.setMetadata(meta);
            result.addEdge(e);
        }

        static String clean(String doc) {
            String[] ls = doc.strip().split("\n");
            int common = Integer.MAX_VALUE;
            for (int i = 1; i < ls.length; i++) {
                if (ls[i].isBlank()) continue;
                common = Math.min(common, ls[i].length() - ls[i].stripLeading().length());
            }
            if (common == Integer.MAX_VALUE) common = 0;
            StringBuilder sb = new StringBuilder(ls[0].strip());
            for (int i = 1; i < ls.length; i++) sb.append('\n').append(ls[i].length() >= common ? ls[i].substring(common) : ls[i].strip());
            return sb.toString().strip();
        }

        static String stripAsync(String code) {
            return code.startsWith("async ") ? code.substring(6).strip() : code;
        }

        /** Splits on top-level commas (ignoring those inside brackets). */
        static List<String> splitArgs(String s) {
            List<String> out = new ArrayList<>();
            int depth = 0, last = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '(' || c == '[' || c == '{') depth++;
                else if (c == ')' || c == ']' || c == '}') depth--;
                else if (c == ',' && depth == 0) {
                    out.add(s.substring(last, i));
                    last = i + 1;
                }
            }
            out.add(s.substring(last));
            return out;
        }
    }
}
