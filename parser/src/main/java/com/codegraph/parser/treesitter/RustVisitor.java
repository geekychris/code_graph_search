package com.codegraph.parser.treesitter;

import com.codegraph.core.model.*;
import com.codegraph.parser.ParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tree-sitter based parser for Rust source files.
 *
 * <p>Extracts modules, structs, enums, traits, functions, methods (inside impl blocks),
 * fields, use declarations, attributes ({@code #[...]}), and doc comments ({@code ///} and block doc comments).
 */
public class RustVisitor extends TreeSitterParser {

    private static final Logger log = LoggerFactory.getLogger(RustVisitor.class);

    /**
     * Rust primitives + language reserved-word types + short generic
     * parameters. Filtered out of USES_TYPE edges so the graph doesn't
     * grow phantom "i32" / "String" / "Result" nodes for every function
     * signature. Keeps the graph focused on user-defined types.
     */
    private static final Set<String> RUST_BUILTINS = Set.of(
        "bool", "char", "str", "String", "i8", "i16", "i32", "i64", "i128",
        "isize", "u8", "u16", "u32", "u64", "u128", "usize", "f32", "f64",
        "Self", "Result", "Option", "Vec", "Box", "Arc", "Rc", "RefCell",
        "Cell", "Mutex", "RwLock", "HashMap", "HashSet", "BTreeMap",
        "BTreeSet", "VecDeque", "Cow", "Path", "PathBuf", "OsStr",
        "OsString", "CStr", "CString", "Range", "RangeInclusive",
        // Typical short generic type params
        "T", "U", "V", "K", "E", "A", "B", "R"
    );

    @Override
    public Language getLanguage() {
        return Language.RUST;
    }

    @Override
    protected ParseResult fallbackParse(Path file, String repoId, Path repoRoot,
                                        String relativePath, String[] sourceLines,
                                        ParseResult result) {
        return RegexFallbackParser.parseRust(repoId, relativePath, sourceLines, result);
    }

    @Override
    public ParseResult visitAST(SExprNode root, Path file, String repoId,
                                 Path repoRoot, String[] sourceLines) {
        var relativePath = repoRoot.relativize(file).toString();
        var result = new ParseResult(relativePath);
        new RustContext(repoId, relativePath, sourceLines, result).visitSourceFile(root);
        return result;
    }

    // -------------------------------------------------------------------------

    private static class RustContext {

        final String repoId;
        final String filePath;
        final String[] sourceLines;
        final ParseResult result;

        // Module path stack, e.g. ["crate", "module_a"]
        final Deque<String> moduleStack = new ArrayDeque<>();
        // Element id stack for CONTAINS edges
        final Deque<String> containerIdStack = new ArrayDeque<>();
        // IDs of elements declared during this parse — lets visitImpl
        // decide whether to attach methods to a STRUCT or an ENUM.
        final Set<String> declaredElementIds = new HashSet<>();

        RustContext(String repoId, String filePath, String[] sourceLines, ParseResult result) {
            this.repoId = repoId;
            this.filePath = filePath;
            this.sourceLines = sourceLines;
            this.result = result;
            moduleStack.push("crate");
        }

        // ---- Helpers --------------------------------------------------------

        CodeElement newElement(ElementType type, String qualifiedName) {
            var el = new CodeElement();
            el.setRepoId(repoId);
            el.setLanguage(Language.RUST);
            el.setElementType(type);
            el.setQualifiedName(qualifiedName);
            el.setFilePath(filePath);
            el.setId(CodeElement.generateId(repoId, filePath, type, qualifiedName));
            // Track for visitImpl's STRUCT-vs-ENUM disambiguation.
            declaredElementIds.add(el.getId());
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

        String nodeText(SExprNode node) { return TreeSitterParser.extractText(node, sourceLines); }

        String currentModule() {
            // Build path from stack (reversed)
            var parts = new ArrayList<String>();
            var iter = moduleStack.descendingIterator();
            while (iter.hasNext()) parts.add(iter.next());
            return String.join("::", parts);
        }

        String qualify(String name) {
            var mod = currentModule();
            return mod.isEmpty() ? name : mod + "::" + name;
        }

        /** Collect doc comments (/// lines) immediately before the given node. */
        String collectDocComment(SExprNode parent, SExprNode declNode) {
            int targetLine = declNode.getStartLine() - 1;
            var lines = new ArrayList<String>();
            var siblings = parent.getChildren();
            for (int i = siblings.size() - 1; i >= 0; i--) {
                var sib = siblings.get(i);
                if (!sib.getType().equals("line_comment") && !sib.getType().equals("block_comment")) continue;
                var text = nodeText(sib);
                if (sib.getEndLine() == targetLine && text.startsWith("///")) {
                    lines.add(0, text);
                    targetLine = sib.getStartLine() - 1;
                } else {
                    break;
                }
            }
            return lines.isEmpty() ? null : String.join("\n", lines);
        }

        // ---- Entry points ---------------------------------------------------

        void visitSourceFile(SExprNode root) {
            visitChildren(root, root);
        }

        void visitChildren(SExprNode parent, SExprNode scope) {
            for (var child : scope.getChildren()) {
                visitItem(parent, child);
            }
        }

        void visitItem(SExprNode parent, SExprNode node) {
            switch (node.getType()) {
                case "mod_item"        -> visitMod(parent, node);
                case "struct_item"     -> visitStruct(parent, node);
                case "enum_item"       -> visitEnum(parent, node);
                case "trait_item"      -> visitTrait(parent, node);
                case "function_item"   -> visitFunction(parent, node);
                case "impl_item"       -> visitImpl(parent, node);
                case "use_declaration" -> visitUse(node);
                case "const_item"      -> visitConst(parent, node);
                case "static_item"     -> visitStatic(parent, node);
                case "type_item"       -> visitTypeAlias(parent, node);
                case "attribute_item"  -> visitAttribute(node);
                case "line_comment"    -> maybeLineComment(node);
                case "block_comment"   -> maybeBlockComment(node);
                default -> { /* skip */ }
            }
        }

        void visitMod(SExprNode parent, SExprNode node) {
            var name = node.getNamedChild("name").map(n -> nodeText(n))
                    .orElse(node.nameText());
            var qname = qualify(name);
            var el = newElement(ElementType.MODULE, qname);
            el.setName(name);
            setPosition(el, node);
            el.setSnippet(TreeSitterParser.truncate(nodeText(node), MAX_SNIPPET_LINES));

            extractAttributes(parent, node, el);
            var doc = collectDocComment(parent, node);
            if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

            result.addElement(el);
            if (!containerIdStack.isEmpty()) addContains(containerIdStack.peek(), el.getId());

            // Recurse into inline module body
            node.getNamedChild("body").ifPresent(body -> {
                moduleStack.push(name);
                containerIdStack.push(el.getId());
                for (var child : body.getChildren()) visitItem(body, child);
                containerIdStack.pop();
                moduleStack.pop();
            });
        }

        void visitStruct(SExprNode parent, SExprNode node) {
            var name = node.getNamedChild("name").map(n -> nodeText(n))
                    .orElse(node.nameText());
            var qname = qualify(name);
            var el = newElement(ElementType.STRUCT, qname);
            el.setName(name);
            setPosition(el, node);
            el.setSnippet(TreeSitterParser.truncate(nodeText(node), MAX_SNIPPET_LINES));

            extractAttributes(parent, node, el);
            var doc = collectDocComment(parent, node);
            if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

            result.addElement(el);
            if (!containerIdStack.isEmpty()) addContains(containerIdStack.peek(), el.getId());

            // Fields
            node.findAll("field_declaration").forEach(fd -> {
                var fieldName = fd.getNamedChild("name").map(n -> nodeText(n))
                        .orElse(nodeText(fd).split(":")[0].trim());
                var fieldType = fd.getNamedChild("type").map(t -> nodeText(t)).orElse("");
                var fieldQName = qname + "::" + fieldName;
                var fieldEl = newElement(ElementType.FIELD, fieldQName);
                fieldEl.setName(fieldName);
                fieldEl.setReturnType(fieldType);
                fieldEl.setParentId(el.getId());
                setPosition(fieldEl, fd);
                fieldEl.setSnippet(nodeText(fd));
                result.addElement(fieldEl);
                addContains(el.getId(), fieldEl.getId());
                // USES_TYPE from the field type — enables "who has an X field?"
                for (var t : extractTypeNames(fieldType)) {
                    var tid = CodeElement.generateId(repoId, filePath,
                            ElementType.STRUCT, qualify(t));
                    result.addEdge(new CodeEdge(fieldEl.getId(), tid, EdgeType.USES_TYPE));
                }
            });
        }

        void visitEnum(SExprNode parent, SExprNode node) {
            var name = node.getNamedChild("name").map(n -> nodeText(n))
                    .orElse(node.nameText());
            var qname = qualify(name);
            var el = newElement(ElementType.ENUM, qname);
            el.setName(name);
            setPosition(el, node);
            el.setSnippet(TreeSitterParser.truncate(nodeText(node), MAX_SNIPPET_LINES));

            extractAttributes(parent, node, el);
            var doc = collectDocComment(parent, node);
            if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

            result.addElement(el);
            if (!containerIdStack.isEmpty()) addContains(containerIdStack.peek(), el.getId());

            // Enum variants
            node.findAll("enum_variant").forEach(v -> {
                var variantName = v.getNamedChild("name").map(n -> nodeText(n))
                        .orElse(v.nameText());
                var variantQName = qname + "::" + variantName;
                var varEl = newElement(ElementType.ENUM_CONSTANT, variantQName);
                varEl.setName(variantName);
                varEl.setParentId(el.getId());
                setPosition(varEl, v);
                varEl.setSnippet(nodeText(v));
                result.addElement(varEl);
                addContains(el.getId(), varEl.getId());
            });
        }

        void visitTrait(SExprNode parent, SExprNode node) {
            var name = node.getNamedChild("name").map(n -> nodeText(n))
                    .orElse(node.nameText());
            var qname = qualify(name);
            var el = newElement(ElementType.TRAIT, qname);
            el.setName(name);
            setPosition(el, node);
            el.setSnippet(TreeSitterParser.truncate(nodeText(node), MAX_SNIPPET_LINES));

            extractAttributes(parent, node, el);
            var doc = collectDocComment(parent, node);
            if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

            result.addElement(el);
            if (!containerIdStack.isEmpty()) addContains(containerIdStack.peek(), el.getId());

            // Methods inside the trait
            containerIdStack.push(el.getId());
            node.findAll("function_item").forEach(fn -> visitFunction(node, fn));
            containerIdStack.pop();
        }

        void visitFunction(SExprNode parent, SExprNode node) {
            var name = node.getNamedChild("name").map(n -> nodeText(n))
                    .orElse(node.nameText());
            var qname = qualify(name);

            // Determine if this is inside an impl (METHOD vs FUNCTION)
            ElementType elType = containerIdStack.isEmpty() ? ElementType.FUNCTION : ElementType.FUNCTION;
            // Will be overridden by visitImpl for methods
            var el = newElement(elType, qname);
            el.setName(name);
            setPosition(el, node);
            el.setSnippet(TreeSitterParser.truncate(nodeText(node), MAX_SNIPPET_LINES));

            // Return type
            node.getNamedChild("return_type").ifPresent(rt -> el.setReturnType(nodeText(rt)));

            // Parameters
            var paramTypes = new ArrayList<String>();
            node.getNamedChild("parameters").ifPresent(params ->
                    params.findAll("parameter").forEach(p ->
                            p.getNamedChild("type").ifPresent(t -> paramTypes.add(nodeText(t)))));
            el.setParameterTypes(paramTypes);
            emitUsesTypeEdges(el, paramTypes,
                    node.getNamedChild("return_type").map(this::nodeText).orElse(""));

            // Visibility
            node.getNamedChild("visibility_modifier").ifPresent(v -> el.setVisibility(nodeText(v)));

            extractAttributes(parent, node, el);
            var doc = collectDocComment(parent, node);
            if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

            result.addElement(el);
            if (!containerIdStack.isEmpty()) addContains(containerIdStack.peek(), el.getId());

            // Calls
            node.getNamedChild("body").ifPresent(body -> extractRustCalls(body, el));
        }

        /**
         * Extract call-expression targets from a function body. Mirrors
         * the GoVisitor cross-file fix: resolve the target qname so it
         * matches the (repo, type, qname)-hashed id of the actual
         * declaration, regardless of which file the call lives in.
         *
         * Three shapes:
         *   1. `bar()`               — unqualified. Prepend current
         *                              module path so `crate::foo::bar`.
         *   2. `Type::method(...)`   — associated function / trait method
         *                              on a locally-known type. Qname =
         *                              `<module>::Type::method`, type =
         *                              METHOD.
         *   3. `foo::bar::baz()`     — cross-module call. Use as-is (best
         *                              effort — full path resolution needs
         *                              an import-aware symbol table).
         */
        void extractRustCalls(SExprNode body, CodeElement caller) {
            for (var call : body.findAll("call_expression")) {
                var funcText = call.getNamedChild("function")
                        .map(f -> nodeText(f).trim()).orElse("");
                if (funcText.isEmpty()) continue;

                var targetQName = funcText;
                var targetType = ElementType.FUNCTION;
                var lastSep = funcText.lastIndexOf("::");
                if (lastSep < 0) {
                    // Unqualified call: assume same module.
                    var mod = currentModule();
                    if (!mod.isEmpty()) targetQName = mod + "::" + funcText;
                } else {
                    var prefix = funcText.substring(0, lastSep);
                    var name = funcText.substring(lastSep + 2);
                    // If the prefix is a locally-declared type, treat it
                    // as a method call on that type. Try STRUCT, ENUM,
                    // TRAIT ids — same "which declared kind is this?"
                    // check visitImpl uses.
                    var typeQName = qualify(prefix);
                    var structId = CodeElement.generateId(repoId, filePath, ElementType.STRUCT, typeQName);
                    var enumId   = CodeElement.generateId(repoId, filePath, ElementType.ENUM, typeQName);
                    var traitId  = CodeElement.generateId(repoId, filePath, ElementType.TRAIT, typeQName);
                    if (declaredElementIds.contains(structId)
                            || declaredElementIds.contains(enumId)
                            || declaredElementIds.contains(traitId)) {
                        targetQName = typeQName + "::" + name;
                        targetType = ElementType.METHOD;
                    }
                    // else: cross-module — leave targetQName = funcText.
                }
                var targetId = CodeElement.generateId(repoId, filePath, targetType, targetQName);
                result.addEdge(new CodeEdge(caller.getId(), targetId, EdgeType.CALLS));
            }
        }

        void visitImpl(SExprNode parent, SExprNode node) {
            // impl TraitName for TypeName  OR  impl TypeName
            var typeName = node.getNamedChild("type").map(t -> nodeText(t)).orElse("");
            var traitName = node.getNamedChild("trait").map(t -> nodeText(t)).orElse(null);

            var typeQName = qualify(typeName);

            // Type ID could be a STRUCT or an ENUM — impl blocks work on
            // both. Try STRUCT first (more common); the same qualified
            // name over an ENUM element would just miss and land on a
            // dangling id, which is fine for search but wrong for
            // CONTAINS traversal. Rely on the caller to have declared
            // the type first (same file, prior item).
            var typeId = CodeElement.generateId(repoId, filePath, ElementType.STRUCT, typeQName);
            // If a same-name ENUM was declared, prefer that id.
            var enumId = CodeElement.generateId(repoId, filePath, ElementType.ENUM, typeQName);
            var effectiveTypeId = declaredElementIds.contains(enumId) ? enumId : typeId;

            // IMPLEMENTS edge (was MIXES_IN — MIXES_IN is for mixin-style
            // inclusion, e.g. Ruby modules; concrete-type-implements-
            // trait is IMPLEMENTS in the EdgeType enum's docs).
            if (traitName != null) {
                var traitQName = qualify(traitName);
                var traitId = CodeElement.generateId(repoId, filePath, ElementType.TRAIT, traitQName);
                result.addEdge(new CodeEdge(effectiveTypeId, traitId, EdgeType.IMPLEMENTS));
            }

            // Visit methods in the impl block as METHOD elements
            node.getNamedChild("body").ifPresent(body -> {
                moduleStack.push(typeName);
                body.findAll("function_item").forEach(fn -> {
                    var fnName = fn.getNamedChild("name").map(n -> nodeText(n))
                            .orElse(fn.nameText());
                    var methodQName = qualify(fnName);

                    var el = newElement(ElementType.METHOD, methodQName);
                    el.setName(fnName);
                    el.addMetadata("receiverType", typeName);
                    if (traitName != null) el.addMetadata("traitImpl", traitName);
                    setPosition(el, fn);
                    el.setSnippet(truncate(nodeText(fn), MAX_SNIPPET_LINES));

                    fn.getNamedChild("return_type").ifPresent(rt -> el.setReturnType(nodeText(rt)));
                    fn.getNamedChild("visibility_modifier").ifPresent(v -> el.setVisibility(nodeText(v)));

                    var paramTypes = new ArrayList<String>();
                    fn.getNamedChild("parameters").ifPresent(params ->
                            params.findAll("parameter").forEach(p ->
                                    p.getNamedChild("type").ifPresent(t -> paramTypes.add(nodeText(t)))));
                    el.setParameterTypes(paramTypes);
                    emitUsesTypeEdges(el, paramTypes,
                            fn.getNamedChild("return_type").map(this::nodeText).orElse(""));

                    extractAttributes(body, fn, el);
                    var doc = collectDocComment(body, fn);
                    if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

                    result.addElement(el);
                    // CONTAINS: the impl'd type → method. Uses whichever
                    // element type we detected above.
                    result.addEdge(new CodeEdge(effectiveTypeId, el.getId(), EdgeType.CONTAINS));

                    // OVERRIDES edge if this method comes from a trait impl.
                    // Target id is speculative (assumes trait method has same
                    // qname under the trait); post-pass could refine.
                    if (traitName != null) {
                        var traitMethodQName = qualify(traitName) + "::" + fnName;
                        var traitMethodId = CodeElement.generateId(repoId, filePath,
                                ElementType.METHOD, traitMethodQName);
                        result.addEdge(new CodeEdge(el.getId(), traitMethodId, EdgeType.OVERRIDES));
                    }
                });
                moduleStack.pop();
            });
        }

        void visitUse(SExprNode node) {
            var text = nodeText(node);
            var qname = filePath + "#use:" + text.replace("use ", "").replace(";", "").trim();
            var el = newElement(ElementType.USE_DECLARATION, qname);
            el.setName(text.trim());
            setPosition(el, node);
            el.setSnippet(text);
            result.addElement(el);

            // Also emit IMPORTS edges to each imported path. Rust's
            // `use foo::bar::{Baz, Qux};` fans out to two imports:
            //   foo::bar::Baz  and  foo::bar::Qux.
            // The target ids assume same-crate resolution; cross-crate
            // resolution needs a repo-wide post-pass which is out of
            // scope for the per-file parser.
            for (var importedPath : expandUsePath(node)) {
                var importQName = qualify(importedPath);
                // Try TRAIT first (traits are often the target of `use`),
                // then STRUCT. Both ids are speculative — real resolution
                // would need a symbol table.
                var traitTarget = CodeElement.generateId(repoId, filePath, ElementType.TRAIT, importQName);
                result.addEdge(new CodeEdge(el.getId(), traitTarget, EdgeType.IMPORTS));
            }
        }

        /**
         * Expand a use_declaration into the individual imported paths.
         * Handles the common forms:
         *   - use foo::bar::Baz;              → ["foo::bar::Baz"]
         *   - use foo::bar::*;                → ["foo::bar"]
         *   - use foo::bar::{Baz, Qux};       → ["foo::bar::Baz", "foo::bar::Qux"]
         *   - use foo::bar::Baz as B;         → ["foo::bar::Baz"]
         * Falls back to the raw text if the shape is unfamiliar.
         */
        List<String> expandUsePath(SExprNode useNode) {
            var out = new ArrayList<String>();
            // The interesting child is a scoped_use_list, use_list, or
            // scoped_identifier. Best effort — probe for named children
            // that look right.
            var argument = useNode.getNamedChild("argument")
                    .or(() -> useNode.findFirst("scoped_use_list"))
                    .or(() -> useNode.findFirst("scoped_identifier"))
                    .or(() -> useNode.findFirst("use_list"));
            if (argument.isEmpty()) return out;
            var arg = argument.get();
            if (arg.getType().equals("scoped_use_list")) {
                // path :: { list of leaves }
                var path = arg.getNamedChild("path").map(this::nodeText).orElse("");
                var list = arg.getNamedChild("list").orElse(arg);
                for (var child : list.getChildren()) {
                    var t = child.getType();
                    if (t.equals("identifier") || t.equals("self")) {
                        var leaf = nodeText(child);
                        if (leaf.equals("self")) {
                            out.add(path);
                        } else {
                            out.add(path.isEmpty() ? leaf : path + "::" + leaf);
                        }
                    }
                }
            } else {
                out.add(nodeText(arg).replaceAll("\\s+as\\s+\\w+", ""));
            }
            return out;
        }

        void visitConst(SExprNode parent, SExprNode node) {
            visitValueItem(parent, node, "const");
        }

        void visitStatic(SExprNode parent, SExprNode node) {
            visitValueItem(parent, node, "static");
        }

        /**
         * Shared handler for const_item + static_item. Emits a FIELD
         * element with metadata.rustValueKind = const|static so downstream
         * tools can distinguish (there's no separate ElementType.CONST yet).
         */
        void visitValueItem(SExprNode parent, SExprNode node, String kind) {
            var name = node.getNamedChild("name").map(this::nodeText).orElse(node.nameText());
            if (name.isBlank()) return;
            var typeText = node.getNamedChild("type").map(this::nodeText).orElse("");
            var qname = qualify(name);
            var el = newElement(ElementType.FIELD, qname);
            el.setName(name);
            el.setReturnType(typeText);
            el.addMetadata("rustValueKind", kind);
            setPosition(el, node);
            el.setSnippet(nodeText(node));

            extractAttributes(parent, node, el);
            var doc = collectDocComment(parent, node);
            if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

            result.addElement(el);
            if (!containerIdStack.isEmpty()) addContains(containerIdStack.peek(), el.getId());

            // USES_TYPE from the declared type.
            for (var t : extractTypeNames(typeText)) {
                var tid = CodeElement.generateId(repoId, filePath, ElementType.STRUCT, qualify(t));
                result.addEdge(new CodeEdge(el.getId(), tid, EdgeType.USES_TYPE));
            }
        }

        void visitTypeAlias(SExprNode parent, SExprNode node) {
            var name = node.getNamedChild("name").map(this::nodeText).orElse(node.nameText());
            if (name.isBlank()) return;
            var qname = qualify(name);
            var el = newElement(ElementType.TYPE_ALIAS, qname);
            el.setName(name);
            var aliasedType = node.getNamedChild("type").map(this::nodeText).orElse("");
            el.setReturnType(aliasedType);
            setPosition(el, node);
            el.setSnippet(nodeText(node));

            extractAttributes(parent, node, el);
            var doc = collectDocComment(parent, node);
            if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

            result.addElement(el);
            if (!containerIdStack.isEmpty()) addContains(containerIdStack.peek(), el.getId());
            for (var t : extractTypeNames(aliasedType)) {
                var tid = CodeElement.generateId(repoId, filePath, ElementType.STRUCT, qualify(t));
                result.addEdge(new CodeEdge(el.getId(), tid, EdgeType.USES_TYPE));
            }
        }

        /**
         * Emit one USES_TYPE edge per distinct type name mentioned in
         * the signature. Filters out primitives and short generic
         * parameters. Same-crate ids only.
         */
        void emitUsesTypeEdges(CodeElement fn, List<String> paramTypes, String returnType) {
            var types = new HashSet<String>();
            for (var pt : paramTypes) types.addAll(extractTypeNames(pt));
            types.addAll(extractTypeNames(returnType));
            for (var t : types) {
                var tid = CodeElement.generateId(repoId, filePath, ElementType.STRUCT, qualify(t));
                result.addEdge(new CodeEdge(fn.getId(), tid, EdgeType.USES_TYPE));
            }
        }

        /**
         * Extract identifier-shaped type names from a Rust type
         * expression. Handles references, generics, paths, tuples,
         * arrays. Best effort — this is a tokeniser, not a Rust type
         * parser. Filters out builtins so the graph doesn't get spammed
         * with edges to phantom "i32" / "String" / "Result" nodes.
         */
        Set<String> extractTypeNames(String typeText) {
            if (typeText == null || typeText.isBlank()) return Set.of();
            var out = new HashSet<String>();
            for (var tok : typeText.split("[^A-Za-z0-9_:]+")) {
                if (tok.isBlank()) continue;
                // Path: mod::sub::Type — take the last segment.
                var lastSep = tok.lastIndexOf("::");
                var t = lastSep >= 0 ? tok.substring(lastSep + 2) : tok;
                if (t.isBlank() || Character.isLowerCase(t.charAt(0))) continue;
                if (RUST_BUILTINS.contains(t)) continue;
                out.add(t);
            }
            return out;
        }

        void visitAttribute(SExprNode node) {
            var text = nodeText(node);
            var qname = filePath + "#attr@" + (node.getStartLine() + 1) + ":" + text;
            var el = newElement(ElementType.ATTRIBUTE, qname);
            el.setName(text.trim());
            setPosition(el, node);
            el.setSnippet(text);
            result.addElement(el);
        }

        void maybeLineComment(SExprNode node) {
            var text = nodeText(node);
            // If it's a doc comment it will be attached to the next item; skip orphan
            if (text.startsWith("///")) return; // doc comments handled with their element
            var qname = filePath + "#comment@" + (node.getStartLine() + 1);
            var el = newElement(ElementType.COMMENT_LINE, qname);
            setPosition(el, node);
            el.setSnippet(text);
            result.addElement(el);
        }

        void maybeBlockComment(SExprNode node) {
            var qname = filePath + "#blockcomment@" + (node.getStartLine() + 1);
            var el = newElement(ElementType.COMMENT_BLOCK, qname);
            setPosition(el, node);
            el.setSnippet(nodeText(node));
            result.addElement(el);
        }

        void extractAttributes(SExprNode parent, SExprNode declNode, CodeElement target) {
            var declStart = declNode.getStartLine();
            for (var sib : parent.getChildren()) {
                if (sib.getType().equals("attribute_item") && sib.getEndLine() < declStart) {
                    var text = nodeText(sib);
                    var attrQName = target.getQualifiedName() + "#attr:" + text;
                    var attrEl = newElement(ElementType.ATTRIBUTE, attrQName);
                    setPosition(attrEl, sib);
                    attrEl.setSnippet(text);
                    attrEl.setParentId(target.getId());
                    result.addElement(attrEl);
                    result.addEdge(new CodeEdge(attrEl.getId(), target.getId(), EdgeType.ANNOTATES));
                }
            }
        }

        void addDocElement(String docText, CodeElement target) {
            var docEl = newElement(ElementType.COMMENT_DOC,
                    target.getQualifiedName() + "#rustdoc");
            docEl.setSnippet(docText);
            docEl.setParentId(target.getId());
            result.addElement(docEl);
            result.addEdge(new CodeEdge(docEl.getId(), target.getId(), EdgeType.DOCUMENTS));
        }
    }
}
