package com.codegraph.parser.treesitter;

import com.codegraph.core.model.*;
import com.codegraph.parser.ParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Tree-sitter based parser for Rust source files.
 *
 * <p>Extracts modules, structs, enums, traits, functions, methods (inside impl blocks),
 * fields, use declarations, attributes ({@code #[...]}), and doc comments ({@code ///} and block doc comments).
 */
public class RustVisitor extends TreeSitterParser {

    private static final Logger log = LoggerFactory.getLogger(RustVisitor.class);

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

            // Visibility
            node.getNamedChild("visibility_modifier").ifPresent(v -> el.setVisibility(nodeText(v)));

            extractAttributes(parent, node, el);
            var doc = collectDocComment(parent, node);
            if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

            result.addElement(el);
            if (!containerIdStack.isEmpty()) addContains(containerIdStack.peek(), el.getId());

            // Calls
            node.getNamedChild("body").ifPresent(body ->
                    body.findAll("call_expression").forEach(call -> {
                        var funcText = call.getNamedChild("function")
                                .map(f -> nodeText(f)).orElse("");
                        var targetId = CodeElement.generateId(repoId, filePath,
                                ElementType.FUNCTION, funcText);
                        result.addEdge(new CodeEdge(el.getId(), targetId, EdgeType.CALLS));
                    }));
        }

        void visitImpl(SExprNode parent, SExprNode node) {
            // impl TraitName for TypeName  OR  impl TypeName
            var typeName = node.getNamedChild("type").map(t -> nodeText(t)).orElse("");
            var traitName = node.getNamedChild("trait").map(t -> nodeText(t)).orElse(null);

            var typeQName = qualify(typeName);

            // MIXES_IN edge if impl Trait for Type
            if (traitName != null) {
                var typeId = CodeElement.generateId(repoId, filePath, ElementType.STRUCT, typeQName);
                var traitQName = qualify(traitName);
                var traitId = CodeElement.generateId(repoId, filePath, ElementType.TRAIT, traitQName);
                result.addEdge(new CodeEdge(typeId, traitId, EdgeType.MIXES_IN));
            }

            // Visit methods in the impl block as METHOD elements
            node.getNamedChild("body").ifPresent(body -> {
                moduleStack.push(typeName);
                body.findAll("function_item").forEach(fn -> {
                    var fnName = fn.getNamedChild("name").map(n -> nodeText(n))
                            .orElse(fn.nameText());
                    var methodQName = qualify(fnName);

                    // Override element type
                    var el = newElement(ElementType.METHOD, methodQName);
                    el.setName(fnName);
                    setPosition(el, fn);
                    el.setSnippet(truncate(nodeText(fn), MAX_SNIPPET_LINES));

                    fn.getNamedChild("return_type").ifPresent(rt -> el.setReturnType(nodeText(rt)));
                    fn.getNamedChild("visibility_modifier").ifPresent(v -> el.setVisibility(nodeText(v)));

                    var paramTypes = new ArrayList<String>();
                    fn.getNamedChild("parameters").ifPresent(params ->
                            params.findAll("parameter").forEach(p ->
                                    p.getNamedChild("type").ifPresent(t -> paramTypes.add(nodeText(t)))));
                    el.setParameterTypes(paramTypes);

                    extractAttributes(body, fn, el);
                    var doc = collectDocComment(body, fn);
                    if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

                    result.addElement(el);
                    // CONTAINS: the impl'd type → method
                    var typeId = CodeElement.generateId(repoId, filePath, ElementType.STRUCT, typeQName);
                    result.addEdge(new CodeEdge(typeId, el.getId(), EdgeType.CONTAINS));
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
