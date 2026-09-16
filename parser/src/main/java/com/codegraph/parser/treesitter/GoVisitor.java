package com.codegraph.parser.treesitter;

import com.codegraph.core.model.*;
import com.codegraph.parser.ParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tree-sitter based parser for Go source files.
 *
 * <p>Extracts packages, functions, methods, structs, interfaces, interface
 * method specs, fields (including embedded), imports, package-level const
 * + var declarations, doc comments, call expressions, and USES_TYPE edges
 * from function/method signatures.
 *
 * <p>Also runs a structural-satisfaction post-pass: for each interface
 * declared in the file, any struct whose methods are a superset of the
 * interface's method spec set gets an {@code IMPLEMENTS} edge to it.
 * Go's typing is structural, so unlike Java there's no {@code implements}
 * keyword to grep for — the graph needs to compute this. Currently
 * limited to intra-file satisfaction; cross-file/cross-package
 * satisfaction requires a repo-wide post-pass which lives outside the
 * per-file parser.
 */
public class GoVisitor extends TreeSitterParser {

    private static final Logger log = LoggerFactory.getLogger(GoVisitor.class);

    /**
     * Go builtin types + language keywords that look like types. Filtered
     * out of USES_TYPE edges so the graph doesn't grow edges to phantom
     * "int" / "string" / "error" nodes. If a codebase genuinely defines
     * a type named the same as a builtin (they can't shadow but they
     * can alias) it just misses the edge — acceptable trade-off.
     */
    private static final Set<String> GO_BUILTINS = Set.of(
        "bool", "byte", "complex64", "complex128", "error", "float32",
        "float64", "int", "int8", "int16", "int32", "int64", "rune",
        "string", "uint", "uint8", "uint16", "uint32", "uint64",
        "uintptr", "any", "comparable",
        // Common stdlib types that show up everywhere and would spam edges
        "T", "E", "K", "V"  // typical generic type parameters
    );

    @Override
    public Language getLanguage() {
        return Language.GO;
    }

    @Override
    protected ParseResult fallbackParse(Path file, String repoId, Path repoRoot,
                                        String relativePath, String[] sourceLines,
                                        ParseResult result) {
        return RegexFallbackParser.parseGo(repoId, relativePath, sourceLines, result);
    }

    @Override
    public ParseResult visitAST(SExprNode root, Path file, String repoId,
                                 Path repoRoot, String[] sourceLines) {
        var relativePath = repoRoot.relativize(file).toString();
        var result = new ParseResult(relativePath);
        var ctx = new GoContext(repoId, relativePath, sourceLines, result);
        ctx.visitSourceFile(root);
        return result;
    }

    // -------------------------------------------------------------------------
    // Visitor context
    // -------------------------------------------------------------------------

    private static class GoContext {

        final String repoId;
        final String filePath;
        final String[] sourceLines;
        final ParseResult result;

        String packageName = "";
        String packageElementId = null;

        // For the structural IMPLEMENTS post-pass. Populated during
        // visitTypeSpec (interface method sets) + visitMethod (struct
        // method sets). After the walk, we intersect these to emit
        // IMPLEMENTS edges from structs to interfaces they satisfy.
        // Value: set of method name+arity signatures ("Read/1", "String/0").
        final Map<String, Set<String>> interfaceMethodSets = new HashMap<>();
        final Map<String, String> interfaceElementIds = new HashMap<>();
        final Map<String, Set<String>> structMethodSets = new HashMap<>();
        final Map<String, String> structElementIds = new HashMap<>();

        GoContext(String repoId, String filePath, String[] sourceLines, ParseResult result) {
            this.repoId = repoId;
            this.filePath = filePath;
            this.sourceLines = sourceLines;
            this.result = result;
        }

        // ---- Helpers --------------------------------------------------------

        CodeElement newElement(ElementType type, String qualifiedName) {
            var el = new CodeElement();
            el.setRepoId(repoId);
            el.setLanguage(Language.GO);
            el.setElementType(type);
            el.setQualifiedName(qualifiedName);
            el.setFilePath(filePath);
            el.setId(CodeElement.generateId(repoId, filePath, type, qualifiedName));
            return el;
        }

        void addContains(String parentId, String childId) {
            result.addEdge(new CodeEdge(parentId, childId, EdgeType.CONTAINS));
        }

        void setPosition(CodeElement el, SExprNode node) {
            el.setLineStart(node.getStartLine() + 1);
            el.setLineEnd(node.getEndLine() + 1);
            el.setColStart(node.getStartCol());
            el.setColEnd(node.getEndCol());
        }

        String nodeText(SExprNode node) {
            return TreeSitterParser.extractText(node, sourceLines);
        }

        String childText(SExprNode parent, String field) {
            return parent.getNamedChild(field)
                    .map(n -> TreeSitterParser.extractText(n, sourceLines))
                    .orElse("");
        }

        /** Find the comment node immediately before the given node (for GoDoc). */
        String findDocComment(SExprNode root, SExprNode declNode) {
            // Look for a comment_line or comment_group ending on declNode.startLine - 1
            int targetEndLine = declNode.getStartLine() - 1;
            var comments = new ArrayList<SExprNode>();
            for (var child : root.getChildren()) {
                if (child.getType().equals("comment") || child.getType().equals("comment_line")) {
                    comments.add(child);
                }
            }
            // Collect contiguous block ending at targetEndLine
            var docLines = new ArrayList<String>();
            for (int i = comments.size() - 1; i >= 0; i--) {
                var c = comments.get(i);
                if (c.getEndLine() == targetEndLine) {
                    docLines.add(0, extractText(c, sourceLines));
                    targetEndLine = c.getStartLine() - 1;
                } else {
                    break;
                }
            }
            return docLines.isEmpty() ? null : String.join("\n", docLines);
        }

        // ---- Entry point ----------------------------------------------------

        void visitSourceFile(SExprNode root) {
            // Two-phase walk so method declarations can find their receiver
            // struct's element id (structs are usually declared before
            // their methods in Go, but not always — e.g., interface-first
            // files). Phase 1: types + top-level declarations. Phase 2:
            // methods, function bodies, IMPLEMENTS post-pass.
            for (var child : root.getChildren()) {
                switch (child.getType()) {
                    case "package_clause"       -> visitPackage(child);
                    case "import_declaration"   -> visitImportDecl(root, child);
                    case "import_spec"          -> visitImportSpec(child);
                    case "type_declaration"     -> visitTypeDecl(root, child);
                    case "const_declaration"    -> visitConstDecl(root, child);
                    case "var_declaration"      -> visitVarDecl(root, child);
                    case "function_declaration" -> visitFunction(root, child);
                    case "comment", "comment_line" -> visitOrphanComment(child);
                    default -> { /* skip */ }
                }
            }
            // Second pass: methods link back to their (now-known) receiver
            // structs. Also lets us build structMethodSets before the
            // structural-implements pass.
            for (var child : root.getChildren()) {
                if (child.getType().equals("method_declaration")) {
                    visitMethod(root, child);
                }
            }
            // Structural interface satisfaction — pure graph inference,
            // no tree-sitter needed.
            emitStructuralImplements();
        }

        void visitPackage(SExprNode node) {
            var name = childText(node, "name");
            if (name.isBlank()) {
                // try identifier child
                name = node.findFirst("package_identifier")
                        .map(n -> nodeText(n)).orElse("");
            }
            packageName = name;
            var el = newElement(ElementType.PACKAGE, packageName);
            el.setName(packageName);
            setPosition(el, node);
            el.setSnippet(nodeText(node));
            result.addElement(el);
            packageElementId = el.getId();
        }

        void visitImportDecl(SExprNode root, SExprNode node) {
            for (var child : node.getChildren()) {
                if (child.getType().equals("import_spec")) visitImportSpec(child);
            }
        }

        void visitImportSpec(SExprNode node) {
            var path = nodeText(node).replaceAll("\"", "").trim();
            var qname = filePath + "#import:" + path;
            var el = newElement(ElementType.IMPORT, qname);
            el.setName(path);
            setPosition(el, node);
            el.setSnippet(nodeText(node));
            result.addElement(el);
        }

        void visitFunction(SExprNode root, SExprNode node) {
            var name = childText(node, "name");
            if (name.isBlank()) name = node.nameText();
            var qname = packageName.isEmpty() ? name : packageName + "." + name;

            var el = newElement(ElementType.FUNCTION, qname);
            el.setName(name);
            setPosition(el, node);
            el.setSnippet(TreeSitterParser.truncate(nodeText(node), MAX_SNIPPET_LINES));

            // Test / benchmark tagging — Go convention: TestXxx(*testing.T),
            // BenchmarkXxx(*testing.B), ExampleXxx(). Cheap heuristic on the
            // name; downstream tools can filter by this metadata field.
            if (name.startsWith("Test")) el.addMetadata("goTestKind", "test");
            else if (name.startsWith("Benchmark")) el.addMetadata("goTestKind", "benchmark");
            else if (name.startsWith("Example")) el.addMetadata("goTestKind", "example");

            // Return type
            node.getNamedChild("result").ifPresent(rt -> el.setReturnType(nodeText(rt)));

            // Parameters + USES_TYPE edges — one edge per distinct type
            // name that appears in the signature (params + return).
            var paramTypes = new ArrayList<String>();
            node.getNamedChild("parameters").ifPresent(params ->
                    params.findAll("parameter_declaration").forEach(p ->
                            p.getNamedChild("type").ifPresent(t -> paramTypes.add(nodeText(t)))));
            el.setParameterTypes(paramTypes);
            emitUsesTypeEdges(el, paramTypes, node.getNamedChild("result").map(this::nodeText).orElse(""));

            // Doc comment
            var doc = findDocComment(root, node);
            if (doc != null) {
                el.setDocComment(doc);
                var docEl = makeDocComment(doc, el, node);
                result.addElement(docEl);
                result.addEdge(new CodeEdge(docEl.getId(), el.getId(), EdgeType.DOCUMENTS));
            }

            result.addElement(el);
            if (packageElementId != null) addContains(packageElementId, el.getId());

            // Calls inside body
            node.getNamedChild("body").ifPresent(body -> extractCalls(body, el));
        }

        void visitMethod(SExprNode root, SExprNode node) {
            var name = childText(node, "name");
            if (name.isBlank()) name = node.nameText();

            // Receiver type — strip pointer/parens: (r *Reader) → "Reader".
            var receiverType = node.getNamedChild("receiver").map(r -> {
                var typeNode = r.findFirst("type_identifier");
                return typeNode.map(t -> nodeText(t)).orElse(nodeText(r).replaceAll("[(*)]", "").trim());
            }).orElse("");

            var qname = packageName.isEmpty()
                    ? receiverType + "." + name
                    : packageName + "." + receiverType + "." + name;

            var el = newElement(ElementType.METHOD, qname);
            el.setName(name);
            setPosition(el, node);
            el.setSnippet(TreeSitterParser.truncate(nodeText(node), MAX_SNIPPET_LINES));
            el.addMetadata("receiverType", receiverType);

            node.getNamedChild("result").ifPresent(rt -> el.setReturnType(nodeText(rt)));

            var paramTypes = new ArrayList<String>();
            node.getNamedChild("parameters").ifPresent(params ->
                    params.findAll("parameter_declaration").forEach(p ->
                            p.getNamedChild("type").ifPresent(t -> paramTypes.add(nodeText(t)))));
            el.setParameterTypes(paramTypes);
            emitUsesTypeEdges(el, paramTypes, node.getNamedChild("result").map(this::nodeText).orElse(""));

            var doc = findDocComment(root, node);
            if (doc != null) {
                el.setDocComment(doc);
                var docEl = makeDocComment(doc, el, node);
                result.addElement(docEl);
                result.addEdge(new CodeEdge(docEl.getId(), el.getId(), EdgeType.DOCUMENTS));
            }

            result.addElement(el);

            // CONTAINS: prefer receiver struct → method. Falls back to
            // package → method when no struct declared (methods on
            // non-struct types, e.g. `type Meters float64; func (m Meters) String() string`).
            // Also feeds the structural-implements post-pass.
            var structQName = packageName.isEmpty() ? receiverType : packageName + "." + receiverType;
            var receiverId = structElementIds.get(structQName);
            if (receiverId != null) {
                addContains(receiverId, el.getId());
                structMethodSets.computeIfAbsent(structQName, k -> new HashSet<>())
                        .add(name + "/" + paramTypes.size());
            } else if (packageElementId != null) {
                addContains(packageElementId, el.getId());
                // Still track the method set so it can satisfy an interface
                // — the struct just wasn't declared in this file (or is a
                // non-struct type receiver).
                structMethodSets.computeIfAbsent(structQName, k -> new HashSet<>())
                        .add(name + "/" + paramTypes.size());
            }

            node.getNamedChild("body").ifPresent(body -> extractCalls(body, el));
        }

        void visitTypeDecl(SExprNode root, SExprNode node) {
            for (var spec : node.getChildren()) {
                if (spec.getType().equals("type_spec")) {
                    visitTypeSpec(root, spec);
                }
            }
        }

        void visitTypeSpec(SExprNode root, SExprNode spec) {
            var name = childText(spec, "name");
            if (name.isBlank()) name = spec.nameText();
            var qname = packageName.isEmpty() ? name : packageName + "." + name;

            // Determine if struct or interface
            var typeBody = spec.getNamedChild("type");
            if (typeBody.isEmpty()) {
                // Try direct child
                typeBody = spec.getChildByType("struct_type")
                        .or(() -> spec.getChildByType("interface_type"));
            }

            ElementType elType = ElementType.STRUCT; // default
            if (typeBody.isPresent()) {
                var bodyType = typeBody.get().getType();
                if (bodyType.equals("interface_type")) elType = ElementType.INTERFACE;
                else if (bodyType.equals("struct_type"))   elType = ElementType.STRUCT;
            }

            var el = newElement(elType, qname);
            el.setName(name);
            setPosition(el, spec);
            el.setSnippet(truncate(nodeText(spec), MAX_SNIPPET_LINES));

            var doc = findDocComment(root, spec);
            if (doc != null) {
                el.setDocComment(doc);
                var docEl = makeDocComment(doc, el, spec);
                result.addElement(docEl);
                result.addEdge(new CodeEdge(docEl.getId(), el.getId(), EdgeType.DOCUMENTS));
            }

            result.addElement(el);
            if (packageElementId != null) addContains(packageElementId, el.getId());

            // Track struct/interface element IDs for post-pass linking
            // (method → receiver struct CONTAINS, IMPLEMENTS edges).
            if (elType == ElementType.STRUCT) {
                structElementIds.put(qname, el.getId());
                structMethodSets.putIfAbsent(qname, new HashSet<>());
            } else if (elType == ElementType.INTERFACE) {
                interfaceElementIds.put(qname, el.getId());
                interfaceMethodSets.put(qname, new HashSet<>());
            }

            // Fields (for structs) — includes embedded types (structural
            // composition). An embedded type appears as a field_declaration
            // with just a type node + no field_identifier — we handle
            // both cases in visitFieldDecl.
            if (elType == ElementType.STRUCT && typeBody.isPresent()) {
                for (var fieldDecl : typeBody.get().findAll("field_declaration")) {
                    visitFieldDecl(fieldDecl, el);
                }
            }

            // Interface method specs — each method spec inside interface_type
            // gets a METHOD element (containing interface = owner) + goes
            // into the interfaceMethodSets for the IMPLEMENTS post-pass.
            // Note: older tree-sitter-go grammars call this "method_spec";
            // newer ones (>= 0.20) call it "method_elem". Try both.
            if (elType == ElementType.INTERFACE && typeBody.isPresent()) {
                var specs = new ArrayList<SExprNode>(typeBody.get().findAll("method_elem"));
                if (specs.isEmpty()) specs.addAll(typeBody.get().findAll("method_spec"));
                for (var methodSpec : specs) {
                    visitInterfaceMethodSpec(methodSpec, el, qname);
                }
                // Also handle embedded interfaces: a type_identifier
                // directly inside interface_type is an embedded interface,
                // whose method set is included in the outer interface.
                // Recorded as a placeholder so post-pass can union.
                for (var child : typeBody.get().getChildren()) {
                    if (child.getType().equals("type_identifier")) {
                        var embedded = nodeText(child);
                        // Union will happen in the post-pass — record as a
                        // marker only. Simplification: assume same-package
                        // embedded interface; cross-package resolution is
                        // out of scope for the per-file parser.
                        interfaceMethodSets.computeIfAbsent(qname, k -> new HashSet<>())
                                .add("__embed__:" + embedded);
                    }
                }
            }
        }

        /**
         * Visits one method_spec inside an interface body. Emits a METHOD
         * element (interface method, no body) and CONTAINS from the
         * interface. Also records the signature for structural IMPLEMENTS.
         */
        void visitInterfaceMethodSpec(SExprNode spec, CodeElement iface, String ifaceQName) {
            var name = spec.getNamedChild("name")
                    .map(this::nodeText).orElse(nodeText(spec).split("\\(")[0].trim());
            if (name.isBlank()) return;
            var qname = iface.getQualifiedName() + "." + name;
            var el = newElement(ElementType.METHOD, qname);
            el.setName(name);
            el.setParentId(iface.getId());
            setPosition(el, spec);
            el.setSnippet(nodeText(spec));

            // Parameters — for arity in the IMPLEMENTS check. Method spec
            // uses "parameters" field just like function_declaration.
            var paramTypes = new ArrayList<String>();
            spec.getNamedChild("parameters").ifPresent(params ->
                    params.findAll("parameter_declaration").forEach(p ->
                            p.getNamedChild("type").ifPresent(t -> paramTypes.add(nodeText(t)))));
            el.setParameterTypes(paramTypes);
            spec.getNamedChild("result").ifPresent(rt -> el.setReturnType(nodeText(rt)));
            el.addMetadata("interfaceMethodSpec", "true");

            result.addElement(el);
            addContains(iface.getId(), el.getId());
            interfaceMethodSets.computeIfAbsent(ifaceQName, k -> new HashSet<>())
                    .add(name + "/" + paramTypes.size());
        }

        void visitFieldDecl(SExprNode node, CodeElement owner) {
            var names = node.findAll("field_identifier");
            var typeText = node.getNamedChild("type")
                    .map(t -> nodeText(t)).orElse("");
            if (!names.isEmpty()) {
                for (var nameNode : names) {
                    var fieldName = nodeText(nameNode);
                    var qname = owner.getQualifiedName() + "." + fieldName;
                    var el = newElement(ElementType.FIELD, qname);
                    el.setName(fieldName);
                    el.setReturnType(typeText);
                    el.setParentId(owner.getId());
                    setPosition(el, nameNode);
                    el.setSnippet(nodeText(node));
                    result.addElement(el);
                    addContains(owner.getId(), el.getId());
                    // USES_TYPE from field type. Enables "who has an X field?"
                    for (var t : extractTypeNames(typeText)) {
                        var tid = CodeElement.generateId(repoId, filePath, ElementType.STRUCT,
                                packageName.isEmpty() ? t : packageName + "." + t);
                        result.addEdge(new CodeEdge(el.getId(), tid, EdgeType.USES_TYPE));
                    }
                }
            } else if (!typeText.isBlank()) {
                // Embedded type: field_declaration with only a type node.
                // Model as a FIELD whose name IS the type — matches how the
                // Go compiler resolves promoted methods. Also emit
                // USES_TYPE so the graph knows this struct composes it.
                var embeddedName = typeText.replaceAll("^\\*", "").trim();
                var qname = owner.getQualifiedName() + "." + embeddedName;
                var el = newElement(ElementType.FIELD, qname);
                el.setName(embeddedName);
                el.setReturnType(typeText);
                el.setParentId(owner.getId());
                el.addMetadata("embedded", "true");
                setPosition(el, node);
                el.setSnippet(nodeText(node));
                result.addElement(el);
                addContains(owner.getId(), el.getId());
                var embeddedId = CodeElement.generateId(repoId, filePath, ElementType.STRUCT,
                        packageName.isEmpty() ? embeddedName : packageName + "." + embeddedName);
                result.addEdge(new CodeEdge(owner.getId(), embeddedId, EdgeType.USES_TYPE));
            }
        }

        void visitConstDecl(SExprNode root, SExprNode node) {
            // `const A = 1` or `const ( A = 1; B = 2 )`.
            visitValueSpecs(root, node, "const_spec", ElementType.FIELD, "const");
        }

        void visitVarDecl(SExprNode root, SExprNode node) {
            // `var x = 42` or `var ( x = 42; y = 43 )`.
            visitValueSpecs(root, node, "var_spec", ElementType.FIELD, "var");
        }

        /**
         * Shared handler for const_declaration + var_declaration. Emits
         * one FIELD element per identifier + a USES_TYPE edge to any
         * declared type. Kind is stored in metadata so downstream tools
         * can distinguish (there's no separate ElementType.CONST yet;
         * FIELD with metadata.goValueKind = const|var keeps the graph
         * simple).
         */
        void visitValueSpecs(SExprNode root, SExprNode node, String specType,
                             ElementType elType, String kind) {
            for (var spec : node.findAll(specType)) {
                var typeText = spec.getNamedChild("type").map(this::nodeText).orElse("");
                for (var nameNode : spec.findAll("identifier")) {
                    var name = nodeText(nameNode);
                    var qname = packageName.isEmpty() ? name : packageName + "." + name;
                    var el = newElement(elType, qname);
                    el.setName(name);
                    el.setReturnType(typeText);
                    el.addMetadata("goValueKind", kind);
                    setPosition(el, nameNode);
                    el.setSnippet(nodeText(spec));

                    var doc = findDocComment(root, spec);
                    if (doc != null) {
                        el.setDocComment(doc);
                        var docEl = makeDocComment(doc, el, spec);
                        result.addElement(docEl);
                        result.addEdge(new CodeEdge(docEl.getId(), el.getId(), EdgeType.DOCUMENTS));
                    }

                    result.addElement(el);
                    if (packageElementId != null) addContains(packageElementId, el.getId());
                    for (var t : extractTypeNames(typeText)) {
                        var tid = CodeElement.generateId(repoId, filePath, ElementType.STRUCT,
                                packageName.isEmpty() ? t : packageName + "." + t);
                        result.addEdge(new CodeEdge(el.getId(), tid, EdgeType.USES_TYPE));
                    }
                }
            }
        }

        /**
         * Extract identifier-shaped type names from a Go type expression.
         * Handles pointers, slices, maps, generics, qualified types. Best
         * effort — this is a lightweight tokeniser, not a Go type parser.
         * Filters out builtins so `USES_TYPE → int` doesn't spam the graph.
         */
        Set<String> extractTypeNames(String typeText) {
            if (typeText == null || typeText.isBlank()) return Set.of();
            var out = new HashSet<String>();
            // Split on any non-identifier char, take capitalised tokens
            // OR PascalCase-ish tokens (Go convention: exported types
            // start with uppercase). Filter out builtins + numerics.
            for (var tok : typeText.split("[^A-Za-z0-9_.]+")) {
                if (tok.isBlank()) continue;
                // Qualified: pkg.Type — take the last segment as the type.
                var lastDot = tok.lastIndexOf('.');
                var t = lastDot >= 0 ? tok.substring(lastDot + 1) : tok;
                if (t.isBlank() || Character.isLowerCase(t.charAt(0))) continue;
                if (GO_BUILTINS.contains(t)) continue;
                out.add(t);
            }
            return out;
        }

        /**
         * Emit one USES_TYPE edge per distinct type name mentioned in the
         * signature. Cheap heuristic — the target id assumes the type is
         * a struct declared in this package (same rule GO reference
         * resolution uses). Cross-package resolution requires a repo-wide
         * post-pass which lives outside the parser.
         */
        void emitUsesTypeEdges(CodeElement fn, List<String> paramTypes, String returnType) {
            var types = new HashSet<String>();
            for (var pt : paramTypes) types.addAll(extractTypeNames(pt));
            types.addAll(extractTypeNames(returnType));
            for (var t : types) {
                var tid = CodeElement.generateId(repoId, filePath, ElementType.STRUCT,
                        packageName.isEmpty() ? t : packageName + "." + t);
                result.addEdge(new CodeEdge(fn.getId(), tid, EdgeType.USES_TYPE));
            }
        }

        /**
         * Structural interface satisfaction pass. For each interface
         * declared in this file, emit an IMPLEMENTS edge from every
         * struct in this file whose method set is a superset of the
         * interface's method set (matched by name + arity — full
         * signature-based matching would need a type resolver).
         *
         * Intra-file only — cross-file / cross-package satisfaction
         * requires a repo-wide post-pass that lives outside the
         * per-file parser. This still covers the very common
         * "small self-contained module where the interface + its
         * implementations live in the same file" case.
         */
        void emitStructuralImplements() {
            // Union embedded interface method sets first.
            var resolved = new HashMap<String, Set<String>>();
            for (var e : interfaceMethodSets.entrySet()) {
                resolved.put(e.getKey(), unionEmbedded(e.getKey(), interfaceMethodSets, new HashSet<>()));
            }
            for (var iface : resolved.entrySet()) {
                var ifaceQName = iface.getKey();
                var required = iface.getValue();
                if (required.isEmpty()) continue;
                var ifaceId = interfaceElementIds.get(ifaceQName);
                if (ifaceId == null) continue;
                for (var s : structMethodSets.entrySet()) {
                    if (s.getValue().containsAll(required)) {
                        var structQName = s.getKey();
                        var structId = structElementIds.get(structQName);
                        if (structId == null) continue;
                        result.addEdge(new CodeEdge(structId, ifaceId, EdgeType.IMPLEMENTS));
                    }
                }
            }
        }

        /**
         * Resolve an interface's method set by unioning in any embedded
         * interfaces (recorded as {@code __embed__:Name} markers by
         * visitTypeSpec). Cycle-safe via the {@code visited} guard.
         */
        Set<String> unionEmbedded(String ifaceQName,
                                  Map<String, Set<String>> pool, Set<String> visited) {
            if (!visited.add(ifaceQName)) return Set.of();
            var raw = pool.getOrDefault(ifaceQName, Set.of());
            var out = new HashSet<String>();
            for (var m : raw) {
                if (m.startsWith("__embed__:")) {
                    var embedded = m.substring("__embed__:".length());
                    var embeddedQName = packageName.isEmpty() ? embedded : packageName + "." + embedded;
                    out.addAll(unionEmbedded(embeddedQName, pool, visited));
                } else {
                    out.add(m);
                }
            }
            return out;
        }

        void extractCalls(SExprNode body, CodeElement caller) {
            for (var call : body.findAll("call_expression")) {
                var funcNode = call.getNamedChild("function").orElse(null);
                if (funcNode == null) continue;
                var funcText = nodeText(funcNode);
                var targetQName = funcText;
                var targetId = CodeElement.generateId(repoId, filePath, ElementType.FUNCTION, targetQName);
                result.addEdge(new CodeEdge(caller.getId(), targetId, EdgeType.CALLS));
            }
        }

        void visitOrphanComment(SExprNode node) {
            var text = nodeText(node);
            var qname = filePath + "#comment@" + (node.getStartLine() + 1);
            var type = text.startsWith("//") ? ElementType.COMMENT_LINE : ElementType.COMMENT_BLOCK;
            var el = newElement(type, qname);
            setPosition(el, node);
            el.setSnippet(text);
            result.addElement(el);
        }

        CodeElement makeDocComment(String doc, CodeElement target, SExprNode near) {
            var docEl = newElement(ElementType.COMMENT_DOC,
                    target.getQualifiedName() + "#godoc");
            docEl.setSnippet(doc);
            docEl.setLineStart(near.getStartLine()); // approx
            docEl.setParentId(target.getId());
            return docEl;
        }
    }
}
