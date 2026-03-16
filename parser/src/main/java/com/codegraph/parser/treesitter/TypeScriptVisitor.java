package com.codegraph.parser.treesitter;

import com.codegraph.core.model.*;
import com.codegraph.parser.ParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Tree-sitter based parser for TypeScript and JavaScript source files.
 *
 * <p>Extracts classes, interfaces, enums, methods, functions, fields/properties,
 * constructors, imports, decorators, and doc comments (JSDoc).
 */
public class TypeScriptVisitor extends TreeSitterParser {

    private static final Logger log = LoggerFactory.getLogger(TypeScriptVisitor.class);

    private static final Set<String> PARAM_TYPES =
            Set.of("required_parameter", "optional_parameter", "rest_parameter",
                    "parameter");

    @Override
    public Language getLanguage() {
        return Language.TYPESCRIPT;
    }

    @Override
    public boolean canParse(Path file) {
        var name = file.getFileName().toString();
        return name.endsWith(".ts") || name.endsWith(".tsx")
                || name.endsWith(".js") || name.endsWith(".jsx")
                || name.endsWith(".mjs") || name.endsWith(".cjs");
    }

    @Override
    protected ParseResult fallbackParse(Path file, String repoId, Path repoRoot,
                                        String relativePath, String[] sourceLines,
                                        ParseResult result) {
        Language lang = file.getFileName().toString().endsWith(".ts") || file.getFileName().toString().endsWith(".tsx")
                ? Language.TYPESCRIPT : Language.JAVASCRIPT;
        return RegexFallbackParser.parseTypeScript(repoId, relativePath, sourceLines, lang, result);
    }

    @Override
    public ParseResult visitAST(SExprNode root, Path file, String repoId,
                                 Path repoRoot, String[] sourceLines) {
        var relativePath = repoRoot.relativize(file).toString();
        var result = new ParseResult(relativePath);
        new TSContext(repoId, relativePath, sourceLines, result).visitProgram(root);
        return result;
    }

    // -------------------------------------------------------------------------
    // Static multi-type findAll helper
    // -------------------------------------------------------------------------

    static List<SExprNode> findAllTypes(SExprNode node, Set<String> types) {
        var acc = new ArrayList<SExprNode>();
        collectAllTypes(node, types, acc);
        return acc;
    }

    private static void collectAllTypes(SExprNode node, Set<String> types, List<SExprNode> acc) {
        if (types.contains(node.getType())) acc.add(node);
        for (var child : node.getChildren()) collectAllTypes(child, types, acc);
    }

    // -------------------------------------------------------------------------

    private static class TSContext {

        final String repoId;
        final String filePath;
        final String[] sourceLines;
        final ParseResult result;

        // Stack of containing class qualified names
        final Deque<String> classStack = new ArrayDeque<>();
        final Deque<String> classIdStack = new ArrayDeque<>();

        TSContext(String repoId, String filePath, String[] sourceLines, ParseResult result) {
            this.repoId = repoId;
            this.filePath = filePath;
            this.sourceLines = sourceLines;
            this.result = result;
        }

        // ---- Helpers --------------------------------------------------------

        CodeElement newElement(ElementType type, String qualifiedName) {
            var el = new CodeElement();
            el.setRepoId(repoId);
            el.setLanguage(Language.TYPESCRIPT);
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

        String currentClass() {
            return classStack.isEmpty() ? "" : classStack.peek();
        }

        String qualify(String name) {
            var cls = currentClass();
            return cls.isEmpty() ? name : cls + "." + name;
        }

        /** Find JSDoc comment immediately preceding the node on the parent. */
        String findJsDoc(SExprNode parent, SExprNode node) {
            var siblings = parent.getChildren();
            int idx = siblings.indexOf(node);
            if (idx <= 0) return null;
            for (int i = idx - 1; i >= 0; i--) {
                var sib = siblings.get(i);
                if (sib.getType().equals("comment")) {
                    var text = nodeText(sib);
                    if (text.startsWith("/**")) return text;
                } else if (!sib.getType().isBlank()) {
                    break;
                }
            }
            return null;
        }

        /** Extract parameter types from a parameter list node. */
        List<String> extractParamTypes(SExprNode paramsNode) {
            var paramTypes = new ArrayList<String>();
            findAllTypes(paramsNode, PARAM_TYPES).forEach(p -> {
                var typeAnn = p.getNamedChild("type");
                paramTypes.add(typeAnn.map(t -> nodeText(t)).orElse("any"));
            });
            return paramTypes;
        }

        // ---- Entry point ----------------------------------------------------

        void visitProgram(SExprNode root) {
            visitChildren(root, root);
        }

        void visitChildren(SExprNode parent, SExprNode scope) {
            for (var child : scope.getChildren()) {
                visitStatement(parent, child);
            }
        }

        void visitStatement(SExprNode parent, SExprNode node) {
            switch (node.getType()) {
                case "class_declaration"           -> visitClass(parent, node, false);
                case "abstract_class_declaration"  -> visitClass(parent, node, true);
                case "interface_declaration"       -> visitInterface(parent, node);
                case "enum_declaration"            -> visitEnum(parent, node);
                case "function_declaration"        -> visitFunctionDecl(parent, node);
                case "lexical_declaration",
                     "variable_declaration"        -> visitVariableDecl(parent, node);
                case "import_statement"            -> visitImport(node);
                case "export_statement"            -> {
                    // Unwrap export: visit the inner declaration
                    for (var child : node.getChildren()) {
                        visitStatement(parent, child);
                    }
                }
                case "comment"                     -> visitComment(parent, node);
                default -> { /* skip */ }
            }
        }

        void visitClass(SExprNode parent, SExprNode node, boolean isAbstract) {
            var name = node.getNamedChild("name").map(n -> nodeText(n))
                    .orElse(node.nameText());
            if (name.isBlank()) name = "<anonymous>";
            var qname = qualify(name);

            var el = newElement(ElementType.CLASS, qname);
            el.setName(name);
            setPosition(el, node);
            el.setSnippet(TreeSitterParser.truncate(nodeText(node), MAX_SNIPPET_LINES));
            if (isAbstract) el.addModifier("abstract");

            // Heritage: extends
            node.getNamedChild("superclass").ifPresent(sc -> {
                var superName = nodeText(sc);
                var superId = CodeElement.generateId(repoId, filePath, ElementType.CLASS, superName);
                result.addEdge(new CodeEdge(el.getId(), superId, EdgeType.EXTENDS));
            });
            // Heritage: implements
            node.findAll("class_heritage").forEach(heritage -> {
                var text = nodeText(heritage);
                if (text.startsWith("implements")) {
                    heritage.findAll("type_identifier").forEach(ti -> {
                        var ifaceName = nodeText(ti);
                        var ifaceId = CodeElement.generateId(repoId, filePath,
                                ElementType.INTERFACE, ifaceName);
                        result.addEdge(new CodeEdge(el.getId(), ifaceId, EdgeType.IMPLEMENTS));
                    });
                }
            });

            // JSDoc
            var doc = findJsDoc(parent, node);
            if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

            // Decorators
            visitDecorators(parent, node, el);

            result.addElement(el);
            if (!classIdStack.isEmpty()) addContains(classIdStack.peek(), el.getId());

            classStack.push(qname);
            classIdStack.push(el.getId());

            // Class body
            node.getNamedChild("body").ifPresent(body -> {
                for (var member : body.getChildren()) {
                    visitClassMember(body, member, el);
                }
            });

            classIdStack.pop();
            classStack.pop();
        }

        void visitClassMember(SExprNode parent, SExprNode node, CodeElement owner) {
            switch (node.getType()) {
                case "method_definition",
                     "abstract_method_signature" -> visitMethodDef(parent, node, owner);
                case "public_field_definition",
                     "field_definition"          -> visitFieldDef(parent, node, owner, false);
                case "decorator"                 -> { /* handled via visitDecorators */ }
                default -> { /* skip */ }
            }
        }

        void visitMethodDef(SExprNode parent, SExprNode node, CodeElement owner) {
            var name = node.getNamedChild("name").map(n -> nodeText(n))
                    .orElse(node.nameText());

            var elType = name.equals("constructor") ? ElementType.CONSTRUCTOR : ElementType.METHOD;

            var paramTypes = node.getNamedChild("parameters")
                    .map(this::extractParamTypes)
                    .orElseGet(ArrayList::new);

            var sig = name + "(" + String.join(",", paramTypes) + ")";
            var qname = owner.getQualifiedName() + "." + sig;

            var el = newElement(elType, qname);
            el.setName(name);
            el.setSignature(sig);
            el.setParameterTypes(paramTypes);
            el.setParentId(owner.getId());
            setPosition(el, node);
            el.setSnippet(TreeSitterParser.truncate(nodeText(node), MAX_SNIPPET_LINES));

            // Return type annotation
            node.getNamedChild("return_type").ifPresent(rt -> el.setReturnType(nodeText(rt)));

            // Access modifiers
            node.getChildByType("accessibility_modifier").ifPresent(m -> el.setVisibility(nodeText(m)));
            var fullText = nodeText(node);
            if (fullText.contains("static"))   el.addModifier("static");
            if (fullText.contains("async"))    el.addModifier("async");
            if (fullText.contains("abstract")) el.addModifier("abstract");
            if (fullText.contains("readonly")) el.addModifier("readonly");

            var doc = findJsDoc(parent, node);
            if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

            visitDecorators(parent, node, el);

            result.addElement(el);
            addContains(owner.getId(), el.getId());

            // Calls inside body
            node.getNamedChild("body").ifPresent(body ->
                    body.findAll("call_expression").forEach(call -> {
                        var funcText = call.getNamedChild("function")
                                .map(f -> nodeText(f)).orElse("");
                        var targetId = CodeElement.generateId(repoId, filePath,
                                ElementType.METHOD, funcText);
                        result.addEdge(new CodeEdge(el.getId(), targetId, EdgeType.CALLS));
                    }));
        }

        void visitFieldDef(SExprNode parent, SExprNode node, CodeElement owner, boolean isProperty) {
            var name = node.getNamedChild("name").map(n -> nodeText(n))
                    .orElse(node.nameText());
            var qname = owner.getQualifiedName() + "." + name;
            var elType = isProperty ? ElementType.PROPERTY : ElementType.FIELD;
            var el = newElement(elType, qname);
            el.setName(name);
            el.setParentId(owner.getId());
            setPosition(el, node);
            el.setSnippet(nodeText(node));

            node.getNamedChild("type").ifPresent(t -> el.setReturnType(nodeText(t)));
            node.getChildByType("accessibility_modifier").ifPresent(m -> el.setVisibility(nodeText(m)));
            var fullText = nodeText(node);
            if (fullText.contains("static"))   el.addModifier("static");
            if (fullText.contains("readonly")) el.addModifier("readonly");

            result.addElement(el);
            addContains(owner.getId(), el.getId());
        }

        void visitInterface(SExprNode parent, SExprNode node) {
            var name = node.getNamedChild("name").map(n -> nodeText(n))
                    .orElse(node.nameText());
            var qname = qualify(name);

            var el = newElement(ElementType.INTERFACE, qname);
            el.setName(name);
            setPosition(el, node);
            el.setSnippet(TreeSitterParser.truncate(nodeText(node), MAX_SNIPPET_LINES));

            var doc = findJsDoc(parent, node);
            if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

            result.addElement(el);
            if (!classIdStack.isEmpty()) addContains(classIdStack.peek(), el.getId());

            classStack.push(qname);
            classIdStack.push(el.getId());
            node.getNamedChild("body").ifPresent(body -> {
                for (var member : body.getChildren()) {
                    switch (member.getType()) {
                        case "method_signature"   -> visitMethodDef(body, member, el);
                        case "property_signature" -> visitFieldDef(body, member, el, true);
                        default -> {}
                    }
                }
            });
            classIdStack.pop();
            classStack.pop();
        }

        void visitEnum(SExprNode parent, SExprNode node) {
            var name = node.getNamedChild("name").map(n -> nodeText(n))
                    .orElse(node.nameText());
            var qname = qualify(name);

            var el = newElement(ElementType.ENUM, qname);
            el.setName(name);
            setPosition(el, node);
            el.setSnippet(TreeSitterParser.truncate(nodeText(node), MAX_SNIPPET_LINES));

            result.addElement(el);
            if (!classIdStack.isEmpty()) addContains(classIdStack.peek(), el.getId());

            node.getNamedChild("body").ifPresent(body ->
                    body.findAll("enum_member").forEach(member -> {
                        var memberName = member.getNamedChild("name")
                                .map(n -> nodeText(n)).orElse(nodeText(member));
                        var memberQName = qname + "." + memberName;
                        var memberEl = newElement(ElementType.ENUM_CONSTANT, memberQName);
                        memberEl.setName(memberName);
                        memberEl.setParentId(el.getId());
                        setPosition(memberEl, member);
                        memberEl.setSnippet(nodeText(member));
                        result.addElement(memberEl);
                        addContains(el.getId(), memberEl.getId());
                    }));
        }

        void visitFunctionDecl(SExprNode parent, SExprNode node) {
            var name = node.getNamedChild("name").map(n -> nodeText(n))
                    .orElse(node.nameText());
            var qname = qualify(name);

            var paramTypes = node.getNamedChild("parameters")
                    .map(this::extractParamTypes)
                    .orElseGet(ArrayList::new);

            var el = newElement(ElementType.FUNCTION, qname);
            el.setName(name);
            el.setParameterTypes(paramTypes);
            setPosition(el, node);
            el.setSnippet(TreeSitterParser.truncate(nodeText(node), MAX_SNIPPET_LINES));
            node.getNamedChild("return_type").ifPresent(rt -> el.setReturnType(nodeText(rt)));
            if (nodeText(node).contains("async")) el.addModifier("async");

            var doc = findJsDoc(parent, node);
            if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

            result.addElement(el);
            if (!classIdStack.isEmpty()) addContains(classIdStack.peek(), el.getId());
        }

        void visitVariableDecl(SExprNode parent, SExprNode node) {
            // Detect arrow functions: const foo = () => { ... }
            for (var decl : node.findAll("variable_declarator")) {
                var name = decl.getNamedChild("name").map(n -> nodeText(n)).orElse("");
                if (name.isBlank()) continue;
                var value = decl.getNamedChild("value").orElse(null);
                if (value != null && (value.getType().equals("arrow_function")
                        || value.getType().equals("function"))) {
                    var qname = qualify(name);
                    var paramTypes = value.getNamedChild("parameters")
                            .map(this::extractParamTypes)
                            .orElseGet(ArrayList::new);
                    var el = newElement(ElementType.FUNCTION, qname);
                    el.setName(name);
                    el.setParameterTypes(paramTypes);
                    setPosition(el, decl);
                    el.setSnippet(TreeSitterParser.truncate(nodeText(node), MAX_SNIPPET_LINES));
                    if (nodeText(value).contains("async")) el.addModifier("async");
                    value.getNamedChild("return_type").ifPresent(rt -> el.setReturnType(nodeText(rt)));
                    result.addElement(el);
                }
            }
        }

        void visitImport(SExprNode node) {
            var text = nodeText(node);
            var qname = filePath + "#import:" + text.replaceAll("\\s+", " ").trim();
            var el = newElement(ElementType.IMPORT, qname);
            el.setName(text.trim());
            setPosition(el, node);
            el.setSnippet(text);
            result.addElement(el);
        }

        void visitComment(SExprNode parent, SExprNode node) {
            var text = nodeText(node);
            ElementType type;
            if (text.startsWith("/**"))      type = ElementType.COMMENT_DOC;
            else if (text.startsWith("/*"))  type = ElementType.COMMENT_BLOCK;
            else                             type = ElementType.COMMENT_LINE;
            var qname = filePath + "#comment@" + (node.getStartLine() + 1);
            var el = newElement(type, qname);
            setPosition(el, node);
            el.setSnippet(text);
            result.addElement(el);
        }

        void visitDecorators(SExprNode parent, SExprNode decl, CodeElement target) {
            int declStart = decl.getStartLine();
            for (var sib : parent.getChildren()) {
                if (sib.getType().equals("decorator") && sib.getEndLine() < declStart) {
                    var text = nodeText(sib);
                    var qname = target.getQualifiedName() + "#decorator:" + text;
                    var el = newElement(ElementType.DECORATOR, qname);
                    setPosition(el, sib);
                    el.setSnippet(text);
                    el.setParentId(target.getId());
                    result.addElement(el);
                    result.addEdge(new CodeEdge(el.getId(), target.getId(), EdgeType.ANNOTATES));
                }
            }
        }

        void addDocElement(String docText, CodeElement target) {
            var docEl = newElement(ElementType.COMMENT_DOC, target.getQualifiedName() + "#jsdoc");
            docEl.setSnippet(docText);
            docEl.setParentId(target.getId());
            result.addElement(docEl);
            result.addEdge(new CodeEdge(docEl.getId(), target.getId(), EdgeType.DOCUMENTS));
        }
    }
}
