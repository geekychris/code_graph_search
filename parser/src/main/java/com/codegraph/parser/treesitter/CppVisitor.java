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

/**
 * Tree-sitter based parser for C++ source and header files.
 *
 * <p>Extends {@link CVisitor} with C++-specific constructs: namespaces, classes,
 * templates, constructors, destructors, and abstract (pure-virtual) interfaces.
 */
public class CppVisitor extends TreeSitterParser {

    private static final Logger log = LoggerFactory.getLogger(CppVisitor.class);

    @Override
    public Language getLanguage() {
        return Language.CPP;
    }

    @Override
    protected ParseResult fallbackParse(Path file, String repoId, Path repoRoot,
                                        String relativePath, String[] sourceLines,
                                        ParseResult result) {
        return RegexFallbackParser.parseC(repoId, relativePath, sourceLines, Language.CPP, result);
    }

    @Override
    public ParseResult visitAST(SExprNode root, Path file, String repoId,
                                 Path repoRoot, String[] sourceLines) {
        var relativePath = repoRoot.relativize(file).toString();
        var result = new ParseResult(relativePath);
        new CppContext(repoId, relativePath, sourceLines, result).visitTranslationUnit(root);
        return result;
    }

    // -------------------------------------------------------------------------

    private static class CppContext extends CVisitor.CContext {

        // Stacks for namespace / class scopes
        final Deque<String> scopeStack = new ArrayDeque<>();
        final Deque<String> containerIdStack = new ArrayDeque<>();

        CppContext(String repoId, String filePath, String[] sourceLines, ParseResult result) {
            super(repoId, filePath, sourceLines, result);
        }

        // Override language so elements are tagged CPP
        @Override
        CodeElement newElement(ElementType type, String qualifiedName) {
            var el = super.newElement(type, qualifiedName);
            el.setLanguage(Language.CPP);
            return el;
        }

        String currentScope() {
            var parts = new ArrayList<String>();
            var iter = scopeStack.descendingIterator();
            while (iter.hasNext()) parts.add(iter.next());
            return String.join("::", parts);
        }

        @Override
        String qualify(String name) {
            var scope = currentScope();
            return scope.isEmpty() ? name : scope + "::" + name;
        }

        @Override
        void visitTranslationUnit(SExprNode root) {
            for (var child : root.getChildren()) {
                visitCppTopLevel(root, child);
            }
        }

        void visitCppTopLevel(SExprNode parent, SExprNode node) {
            switch (node.getType()) {
                case "namespace_definition"   -> visitNamespace(parent, node);
                case "class_specifier"        -> visitClass(parent, node, false);
                case "struct_specifier"       -> visitStructOrClass(parent, node);
                case "function_definition"    -> visitFunction(parent, node);
                case "declaration"            -> visitDeclaration(parent, node);
                case "template_declaration"   -> visitTemplate(parent, node);
                case "enum_specifier"         -> visitEnum(parent, node, null);
                case "type_definition"        -> visitTypedef(parent, node);
                case "preproc_include"        -> visitInclude(node);
                case "comment"               -> visitComment(parent, node);
                default -> { /* skip */ }
            }
        }

        void visitNamespace(SExprNode parent, SExprNode node) {
            var name = node.getNamedChild("name").map(n -> nodeText(n))
                    .orElse("<anonymous>");
            var qname = qualify(name);

            var el = newElement(ElementType.NAMESPACE, qname);
            el.setName(name);
            setPosition(el, node);
            el.setSnippet(TreeSitterParser.truncate(nodeText(node), MAX_SNIPPET_LINES));

            var doc = findDocComment(parent, node);
            if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

            result.addElement(el);
            if (!containerIdStack.isEmpty()) addContains(containerIdStack.peek(), el.getId());

            // Recurse into namespace body
            node.getNamedChild("body").ifPresent(body -> {
                scopeStack.push(name);
                containerIdStack.push(el.getId());
                for (var child : body.getChildren()) visitCppTopLevel(body, child);
                containerIdStack.pop();
                scopeStack.pop();
            });
        }

        void visitClass(SExprNode parent, SExprNode node, boolean isStruct) {
            var name = node.getNamedChild("name").map(n -> nodeText(n))
                    .or(() -> node.findFirst("type_identifier").map(n -> nodeText(n)))
                    .orElse("<anonymous>");
            var qname = qualify(name);

            // Determine if this is an interface (has any pure virtual methods)
            boolean hasPureVirtual = !node.findAll("pure_virtual_clause").isEmpty();
            var elType = hasPureVirtual ? ElementType.INTERFACE : ElementType.CLASS;

            var el = newElement(elType, qname);
            el.setName(name);
            setPosition(el, node);
            el.setSnippet(TreeSitterParser.truncate(nodeText(node), MAX_SNIPPET_LINES));
            if (isStruct) el.addMetadata("keyword", "struct");

            // Base classes (inheritance)
            node.getNamedChild("base_class_clause").ifPresent(bc -> {
                for (var baseClass : bc.findAll("type_identifier")) {
                    var baseName = nodeText(baseClass);
                    var baseQName = qualify(baseName);
                    var baseId = CodeElement.generateId(repoId, filePath, ElementType.CLASS, baseQName);
                    result.addEdge(new CodeEdge(el.getId(), baseId, EdgeType.EXTENDS));
                }
            });

            // Template parameters
            var templates = new ArrayList<String>();
            node.getNamedChild("template_parameters").ifPresent(tp ->
                    tp.findAll("type_parameter_declaration").forEach(t -> {
                        var typeParam = t.findFirst("type_identifier")
                                .map(n -> nodeText(n)).orElse("?");
                        templates.add(typeParam);
                    }));
            if (!templates.isEmpty()) el.addMetadata("templateParams", String.join(",", templates));

            var doc = findDocComment(parent, node);
            if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

            result.addElement(el);
            if (!containerIdStack.isEmpty()) addContains(containerIdStack.peek(), el.getId());

            // Members
            node.getNamedChild("body").ifPresent(body -> {
                scopeStack.push(name);
                containerIdStack.push(el.getId());
                visitClassBody(body, el, qname);
                containerIdStack.pop();
                scopeStack.pop();
            });
        }

        void visitStructOrClass(SExprNode parent, SExprNode node) {
            // In C++ a struct can have methods; treat it as a class
            var name = node.getNamedChild("name").map(n -> nodeText(n))
                    .or(() -> node.findFirst("type_identifier").map(n -> nodeText(n)))
                    .orElse(null);
            if (name == null) {
                // Anonymous struct used in typedef — delegate to C handler
                visitStruct(parent, node, null);
                return;
            }
            visitClass(parent, node, true);
        }

        void visitClassBody(SExprNode body, CodeElement owner, String ownerQName) {
            for (var member : body.getChildren()) {
                switch (member.getType()) {
                    case "function_definition"  -> visitMethod(body, member, owner);
                    case "declaration"          -> visitMemberDeclaration(body, member, owner);
                    case "field_declaration"    -> visitFieldDeclaration(member, owner);
                    case "access_specifier"     -> { /* visibility keyword, skip */ }
                    case "comment"              -> visitComment(body, member);
                    default -> {}
                }
            }
        }

        void visitMethod(SExprNode parent, SExprNode node, CodeElement owner) {
            var declarator = node.getNamedChild("declarator").orElse(null);
            if (declarator == null) return;

            var nameNode = findFunctionName(declarator);
            if (nameNode == null) return;
            var name = nodeText(nameNode);

            ElementType elType;
            String text = nodeText(node);
            if (name.startsWith("~")) {
                elType = ElementType.METHOD;
                // destructor modelled as METHOD with ~ prefix
            } else if (name.equals(owner.getName())) {
                elType = ElementType.CONSTRUCTOR;
            } else {
                elType = ElementType.METHOD;
            }

            var paramTypes = extractParamTypes(declarator);
            var sig = name + "(" + String.join(",", paramTypes) + ")";
            var qname = ownerQName(owner) + "::" + sig;

            var el = newElement(elType, qname);
            el.setName(name);
            el.setSignature(sig);
            el.setParameterTypes(paramTypes);
            el.setParentId(owner.getId());
            setPosition(el, node);
            el.setSnippet(TreeSitterParser.truncate(text, MAX_SNIPPET_LINES));

            node.getNamedChild("type").ifPresent(t -> el.setReturnType(nodeText(t)));

            // virtual, static, const
            if (text.contains("virtual")) el.addModifier("virtual");
            if (text.contains("static"))  el.addModifier("static");
            if (text.contains("override")) el.addModifier("override");
            if (text.contains("= 0"))     el.addModifier("pure_virtual");

            var doc = findDocComment(parent, node);
            if (doc != null) { el.setDocComment(doc); addDocElement(doc, el); }

            result.addElement(el);
            addContains(owner.getId(), el.getId());

            // Template parameters on method
            node.getNamedChild("template_parameters").ifPresent(tp -> {
                var templates = new ArrayList<String>();
                tp.findAll("type_parameter_declaration").forEach(t -> {
                    var typeParam = t.findFirst("type_identifier")
                            .map(n -> nodeText(n)).orElse("?");
                    templates.add(typeParam);
                });
                if (!templates.isEmpty()) el.addMetadata("templateParams", String.join(",", templates));
            });
        }

        void visitMemberDeclaration(SExprNode parent, SExprNode node, CodeElement owner) {
            // Pure virtual declarations, friend declarations, or field declarations
            var typeText = node.getNamedChild("type").map(t -> nodeText(t)).orElse("");
            node.findAll("field_identifier").forEach(fi -> {
                var fieldName = nodeText(fi);
                var qname = ownerQName(owner) + "::" + fieldName;
                var el = newElement(ElementType.FIELD, qname);
                el.setName(fieldName);
                el.setReturnType(typeText);
                el.setParentId(owner.getId());
                setPosition(el, fi);
                el.setSnippet(nodeText(node));
                result.addElement(el);
                addContains(owner.getId(), el.getId());
            });
        }

        void visitTemplate(SExprNode parent, SExprNode node) {
            // Template wraps a class, struct, or function — delegate to the inner declaration
            for (var child : node.getChildren()) {
                switch (child.getType()) {
                    case "class_specifier"     -> visitClass(parent, child, false);
                    case "struct_specifier"    -> visitStructOrClass(parent, child);
                    case "function_definition" -> visitFunction(parent, child);
                    default -> {}
                }
            }
        }

        private String ownerQName(CodeElement owner) {
            return owner.getQualifiedName();
        }
    }
}
