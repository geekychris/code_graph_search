package com.codegraph.parser.treesitter;

import com.codegraph.core.model.*;
import com.codegraph.parser.ParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tree-sitter based parser for C source and header files (.c, .h).
 *
 * <p>Extracts functions, structs, enums, fields, typedefs, preprocessor includes,
 * and comments.
 */
public class CVisitor extends TreeSitterParser {

    private static final Logger log = LoggerFactory.getLogger(CVisitor.class);

    @Override
    public Language getLanguage() {
        return Language.C;
    }

    @Override
    protected ParseResult fallbackParse(Path file, String repoId, Path repoRoot,
                                        String relativePath, String[] sourceLines,
                                        ParseResult result) {
        return RegexFallbackParser.parseC(repoId, relativePath, sourceLines, Language.C, result);
    }

    @Override
    public ParseResult visitAST(SExprNode root, Path file, String repoId,
                                 Path repoRoot, String[] sourceLines) {
        var relativePath = repoRoot.relativize(file).toString();
        var result = new ParseResult(relativePath);
        new CContext(repoId, relativePath, sourceLines, result).visitTranslationUnit(root);
        return result;
    }

    // -------------------------------------------------------------------------

    static class CContext {

        final String repoId;
        final String filePath;
        final String[] sourceLines;
        final ParseResult result;

        /** Optional namespace/class prefix for C++ subclasses. */
        String scopePrefix = "";

        CContext(String repoId, String filePath, String[] sourceLines, ParseResult result) {
            this.repoId = repoId;
            this.filePath = filePath;
            this.sourceLines = sourceLines;
            this.result = result;
        }

        // ---- Helpers --------------------------------------------------------

        CodeElement newElement(ElementType type, String qualifiedName) {
            var el = new CodeElement();
            el.setRepoId(repoId);
            el.setLanguage(Language.C);
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

        String qualify(String name) {
            return scopePrefix.isEmpty() ? name : scopePrefix + "::" + name;
        }

        /** Find comment immediately preceding the declaration (doc comment heuristic). */
        String findDocComment(SExprNode parent, SExprNode node) {
            var siblings = parent.getChildren();
            int idx = siblings.indexOf(node);
            if (idx <= 0) return null;
            var prev = siblings.get(idx - 1);
            if (prev.getType().equals("comment")) {
                var text = nodeText(prev);
                if (text.startsWith("/**") || text.startsWith("/*!") || text.startsWith("///")) {
                    return text;
                }
            }
            return null;
        }

        // ---- Entry point ----------------------------------------------------

        void visitTranslationUnit(SExprNode root) {
            for (var child : root.getChildren()) {
                visitTopLevel(root, child);
            }
        }

        void visitTopLevel(SExprNode parent, SExprNode node) {
            switch (node.getType()) {
                case "function_definition"  -> visitFunction(parent, node);
                case "declaration"          -> visitDeclaration(parent, node);
                case "struct_specifier",
                     "union_specifier"      -> visitStruct(parent, node, null);
                case "enum_specifier"       -> visitEnum(parent, node, null);
                case "type_definition"      -> visitTypedef(parent, node);
                case "preproc_include"      -> visitInclude(node);
                case "preproc_def",
                     "preproc_function_def" -> visitPreprocDef(node);
                case "comment"              -> visitComment(parent, node);
                default -> { /* skip */ }
            }
        }

        void visitFunction(SExprNode parent, SExprNode node) {
            // Get declarator → function name
            var declarator = node.getNamedChild("declarator").orElse(null);
            if (declarator == null) return;

            // Name may be nested in pointer_declarator → function_declarator
            var nameNode = findFunctionName(declarator);
            if (nameNode == null) return;

            var name = nodeText(nameNode);
            var qname = qualify(name);

            var el = newElement(ElementType.FUNCTION, qname);
            el.setName(name);
            setPosition(el, node);
            el.setSnippet(TreeSitterParser.truncate(nodeText(node), MAX_SNIPPET_LINES));

            // Return type from type specifier
            node.getNamedChild("type").ifPresent(t -> el.setReturnType(nodeText(t)));

            // Parameters
            var paramTypes = extractParamTypes(declarator);
            el.setParameterTypes(paramTypes);

            var doc = findDocComment(parent, node);
            if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

            result.addElement(el);

            // Calls
            node.getNamedChild("body").ifPresent(body ->
                    body.findAll("call_expression").forEach(call -> {
                        var funcNode = call.getNamedChild("function").orElse(null);
                        if (funcNode == null) return;
                        var funcName = qualify(nodeText(funcNode));
                        var targetId = CodeElement.generateId(repoId, filePath,
                                ElementType.FUNCTION, funcName);
                        result.addEdge(new CodeEdge(el.getId(), targetId, EdgeType.CALLS));
                    }));
        }

        SExprNode findFunctionName(SExprNode declarator) {
            // function_declarator has a declarator child which is the identifier
            return switch (declarator.getType()) {
                case "function_declarator" -> declarator.getNamedChild("declarator")
                        .orElse(null);
                case "pointer_declarator"  -> {
                    var inner = declarator.getNamedChild("declarator").orElse(null);
                    yield inner != null ? findFunctionName(inner) : null;
                }
                case "identifier"          -> declarator;
                default -> declarator.findFirst("identifier").orElse(null);
            };
        }

        List<String> extractParamTypes(SExprNode declarator) {
            var params = new ArrayList<String>();
            // Find parameter_list inside function_declarator
            var funcDecl = declarator.getType().equals("function_declarator")
                    ? declarator
                    : declarator.findFirst("function_declarator").orElse(null);
            if (funcDecl == null) return params;
            funcDecl.getNamedChild("parameters").ifPresent(pl -> {
                for (var param : pl.getChildren()) {
                    if (param.getType().equals("parameter_declaration")) {
                        param.getNamedChild("type").ifPresent(t -> params.add(nodeText(t)));
                    }
                }
            });
            return params;
        }

        void visitDeclaration(SExprNode parent, SExprNode node) {
            // Could be a struct/enum typedef or a global variable declaration
            // Check if it has a struct/enum specifier child
            node.getChildByType("struct_specifier").ifPresent(s -> visitStruct(parent, s, node));
            node.getChildByType("union_specifier").ifPresent(s -> visitStruct(parent, s, node));
            node.getChildByType("enum_specifier").ifPresent(e -> visitEnum(parent, e, node));
        }

        void visitStruct(SExprNode parent, SExprNode node, SExprNode wrapperDecl) {
            // Name can be in a "name" child or an identifier child
            var name = node.getNamedChild("name").map(n -> nodeText(n))
                    .or(() -> node.findFirst("type_identifier").map(n -> nodeText(n)))
                    .orElse("<anonymous>");

            var qname = qualify(name);
            var isUnion = node.getType().equals("union_specifier");

            var el = newElement(ElementType.STRUCT, qname);
            el.setName(name);
            if (isUnion) el.addMetadata("kind", "union");
            setPosition(el, node);
            el.setSnippet(TreeSitterParser.truncate(nodeText(node), MAX_SNIPPET_LINES));

            var doc = findDocComment(parent, wrapperDecl != null ? wrapperDecl : node);
            if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

            result.addElement(el);

            // Fields
            node.getNamedChild("body").ifPresent(body -> {
                for (var field : body.getChildren()) {
                    if (field.getType().equals("field_declaration")) {
                        visitFieldDeclaration(field, el);
                    }
                }
            });
        }

        void visitFieldDeclaration(SExprNode node, CodeElement owner) {
            var typeText = node.getNamedChild("type").map(t -> nodeText(t)).orElse("");
            // Each declarator is a field name
            for (var child : node.getChildren()) {
                if (child.getType().equals("field_identifier") || child.getType().equals("identifier")) {
                    var fieldName = nodeText(child);
                    var qname = owner.getQualifiedName() + "." + fieldName;
                    var el = newElement(ElementType.FIELD, qname);
                    el.setName(fieldName);
                    el.setReturnType(typeText);
                    el.setParentId(owner.getId());
                    setPosition(el, child);
                    el.setSnippet(nodeText(node));
                    result.addElement(el);
                    addContains(owner.getId(), el.getId());
                }
            }
        }

        void visitEnum(SExprNode parent, SExprNode node, SExprNode wrapperDecl) {
            var name = node.getNamedChild("name").map(n -> nodeText(n))
                    .or(() -> node.findFirst("type_identifier").map(n -> nodeText(n)))
                    .orElse("<anonymous_enum>");

            var qname = qualify(name);
            var el = newElement(ElementType.ENUM, qname);
            el.setName(name);
            setPosition(el, node);
            el.setSnippet(TreeSitterParser.truncate(nodeText(node), MAX_SNIPPET_LINES));

            var doc = findDocComment(parent, wrapperDecl != null ? wrapperDecl : node);
            if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

            result.addElement(el);

            // Enum values
            node.getNamedChild("body").ifPresent(body ->
                    body.findAll("enumerator").forEach(enumerator -> {
                        var valueName = enumerator.getNamedChild("name")
                                .map(n -> nodeText(n)).orElse(nodeText(enumerator));
                        var valueQName = qname + "." + valueName;
                        var valueEl = newElement(ElementType.ENUM_CONSTANT, valueQName);
                        valueEl.setName(valueName);
                        valueEl.setParentId(el.getId());
                        setPosition(valueEl, enumerator);
                        valueEl.setSnippet(nodeText(enumerator));
                        result.addElement(valueEl);
                        addContains(el.getId(), valueEl.getId());
                    }));
        }

        void visitTypedef(SExprNode parent, SExprNode node) {
            // typedef struct/enum handled above; here handle plain typedefs
            var hasStruct = node.getChildByType("struct_specifier").isPresent()
                    || node.getChildByType("union_specifier").isPresent();
            var hasEnum = node.getChildByType("enum_specifier").isPresent();
            if (hasStruct || hasEnum) return; // handled by visitDeclaration chain

            // Plain typedef: typedef int MyInt;
            var typeDecl = node.findFirst("type_identifier").orElse(null);
            if (typeDecl == null) return;
            var name = nodeText(typeDecl);
            var qname = qualify(name);
            var el = newElement(ElementType.TYPE_ALIAS, qname);
            el.setName(name);
            setPosition(el, node);
            el.setSnippet(nodeText(node));
            result.addElement(el);
        }

        void visitInclude(SExprNode node) {
            var path = nodeText(node).replace("#include", "").trim();
            var qname = filePath + "#include:" + path;
            var el = newElement(ElementType.IMPORT, qname);
            el.setName(path);
            setPosition(el, node);
            el.setSnippet(nodeText(node));
            result.addElement(el);
        }

        void visitPreprocDef(SExprNode node) {
            // #define — treat as a constant/alias for now, skip
        }

        void visitComment(SExprNode parent, SExprNode node) {
            var text = nodeText(node);
            ElementType type;
            if (text.startsWith("/**") || text.startsWith("/*!") || text.startsWith("///")) {
                type = ElementType.COMMENT_DOC;
            } else if (text.startsWith("/*")) {
                type = ElementType.COMMENT_BLOCK;
            } else {
                type = ElementType.COMMENT_LINE;
            }
            var qname = filePath + "#comment@" + (node.getStartLine() + 1);
            var el = newElement(type, qname);
            setPosition(el, node);
            el.setSnippet(text);
            result.addElement(el);
        }

        void addDocElement(String docText, CodeElement target) {
            var docEl = newElement(ElementType.COMMENT_DOC, target.getQualifiedName() + "#doc");
            docEl.setSnippet(docText);
            docEl.setParentId(target.getId());
            result.addElement(docEl);
            result.addEdge(new CodeEdge(docEl.getId(), target.getId(), EdgeType.DOCUMENTS));
        }
    }
}
