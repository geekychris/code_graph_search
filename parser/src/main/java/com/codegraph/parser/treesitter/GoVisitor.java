package com.codegraph.parser.treesitter;

import com.codegraph.core.model.*;
import com.codegraph.parser.ParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tree-sitter based parser for Go source files.
 *
 * <p>Extracts packages, functions, methods, structs, interfaces, fields,
 * imports, doc comments, and call expressions.
 */
public class GoVisitor extends TreeSitterParser {

    private static final Logger log = LoggerFactory.getLogger(GoVisitor.class);

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
            for (var child : root.getChildren()) {
                switch (child.getType()) {
                    case "package_clause"     -> visitPackage(child);
                    case "import_declaration" -> visitImportDecl(root, child);
                    case "import_spec"        -> visitImportSpec(child);
                    case "function_declaration" -> visitFunction(root, child);
                    case "method_declaration"   -> visitMethod(root, child);
                    case "type_declaration"     -> visitTypeDecl(root, child);
                    case "comment", "comment_line" -> visitOrphanComment(child);
                    default -> { /* skip */ }
                }
            }
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

            // Return type
            node.getNamedChild("result").ifPresent(rt -> el.setReturnType(nodeText(rt)));

            // Parameters
            var paramTypes = new ArrayList<String>();
            node.getNamedChild("parameters").ifPresent(params ->
                    params.findAll("parameter_declaration").forEach(p ->
                            p.getNamedChild("type").ifPresent(t -> paramTypes.add(nodeText(t)))));
            el.setParameterTypes(paramTypes);

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

            // Receiver type
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

            var doc = findDocComment(root, node);
            if (doc != null) {
                el.setDocComment(doc);
                var docEl = makeDocComment(doc, el, node);
                result.addElement(docEl);
                result.addEdge(new CodeEdge(docEl.getId(), el.getId(), EdgeType.DOCUMENTS));
            }

            result.addElement(el);
            if (packageElementId != null) addContains(packageElementId, el.getId());

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

            // Fields (for structs)
            if (elType == ElementType.STRUCT && typeBody.isPresent()) {
                for (var fieldDecl : typeBody.get().findAll("field_declaration")) {
                    visitFieldDecl(fieldDecl, el);
                }
            }
        }

        void visitFieldDecl(SExprNode node, CodeElement owner) {
            var names = node.findAll("field_identifier");
            var typeText = node.getNamedChild("type")
                    .map(t -> nodeText(t)).orElse("");
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
            }
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
