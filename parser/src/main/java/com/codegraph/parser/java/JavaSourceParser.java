package com.codegraph.parser.java;

import com.codegraph.core.model.*;
import com.codegraph.parser.LanguageParser;
import com.codegraph.parser.ParseResult;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Parses Java source files using JavaParser, extracting code structure elements and
 * relationships (CONTAINS, EXTENDS, IMPLEMENTS, CALLS, DOCUMENTS, ANNOTATES).
 */
public class JavaSourceParser implements LanguageParser {

    private static final Logger log = LoggerFactory.getLogger(JavaSourceParser.class);
    private static final int MAX_SNIPPET_LINES = 100;

    private static final ParserConfiguration PARSER_CONFIG = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    @Override
    public Language getLanguage() {
        return Language.JAVA;
    }

    @Override
    public boolean canParse(Path file) {
        return file.getFileName().toString().endsWith(".java");
    }

    @Override
    public ParseResult parse(Path file, String repoId, Path repoRoot) {
        var relativePath = repoRoot.relativize(file).toString();
        var result = new ParseResult(relativePath);
        try {
            var parser = new JavaParser(PARSER_CONFIG);
            var parseResult = parser.parse(file);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                var problems = parseResult.getProblems();
                var msg = problems.isEmpty() ? "Unknown parse error" : problems.get(0).getMessage();
                log.error("Failed to parse {}: {}", relativePath, msg);
                result.setError("Parse error: " + msg);
                return result;
            }
            var cu = parseResult.getResult().get();
            var visitor = new JavaAstVisitor(repoId, relativePath, result);
            visitor.visit(cu, null);
        } catch (IOException e) {
            log.error("Failed to read {}: {}", relativePath, e.getMessage());
            result.setError("IO error: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to parse {}: {}", relativePath, e.getMessage());
            result.setError("Parse error: " + e.getMessage());
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Internal visitor
    // -------------------------------------------------------------------------

    private static class JavaAstVisitor extends VoidVisitorAdapter<Void> {

        private final String repoId;
        private final String filePath;
        private final ParseResult result;

        // Tracks qualified name of the current type scope (innermost)
        private final Deque<String> typeQNameStack = new ArrayDeque<>();
        // Tracks element id of the current type scope
        private final Deque<String> typeIdStack = new ArrayDeque<>();

        private String packageName = "";
        private String packageElementId = null;

        JavaAstVisitor(String repoId, String filePath, ParseResult result) {
            this.repoId = repoId;
            this.filePath = filePath;
            this.result = result;
        }

        // ---- Helpers --------------------------------------------------------

        private CodeElement newElement(ElementType type, String qualifiedName) {
            var el = new CodeElement();
            el.setRepoId(repoId);
            el.setLanguage(Language.JAVA);
            el.setElementType(type);
            el.setQualifiedName(qualifiedName);
            el.setFilePath(filePath);
            el.setId(CodeElement.generateId(repoId, filePath, type, qualifiedName));
            return el;
        }

        private void addContains(String parentId, String childId) {
            result.addEdge(new CodeEdge(parentId, childId, EdgeType.CONTAINS));
        }

        private static String truncateSnippet(String text) {
            if (text == null) return null;
            var lines = text.split("\n", -1);
            if (lines.length <= MAX_SNIPPET_LINES) return text;
            return Arrays.stream(lines, 0, MAX_SNIPPET_LINES)
                    .collect(Collectors.joining("\n")) + "\n// ... (truncated)";
        }

        private static String visibility(NodeWithModifiers<?> node) {
            var mods = node.getModifiers();
            if (mods.stream().anyMatch(m -> m.getKeyword().asString().equals("public")))    return "public";
            if (mods.stream().anyMatch(m -> m.getKeyword().asString().equals("protected"))) return "protected";
            if (mods.stream().anyMatch(m -> m.getKeyword().asString().equals("private")))   return "private";
            return "package";
        }

        private static List<String> modifiers(NodeWithModifiers<?> node) {
            return node.getModifiers().stream()
                    .map(m -> m.getKeyword().asString())
                    .filter(k -> !k.equals("public") && !k.equals("protected") && !k.equals("private"))
                    .collect(Collectors.toList());
        }

        private void applyPosition(CodeElement el, com.github.javaparser.ast.Node node) {
            node.getRange().ifPresent(r -> {
                el.setLineStart(r.begin.line);
                el.setLineEnd(r.end.line);
                el.setColStart(r.begin.column);
                el.setColEnd(r.end.column);
            });
        }

        private void extractJavadoc(com.github.javaparser.ast.Node node, CodeElement target) {
            node.getComment().ifPresent(comment -> {
                if (comment instanceof JavadocComment jdc) {
                    var docEl = newElement(ElementType.COMMENT_DOC,
                            target.getQualifiedName() + "#javadoc");
                    applyPosition(docEl, jdc);
                    docEl.setSnippet(jdc.getContent());
                    docEl.setParentId(target.getId());
                    result.addElement(docEl);
                    result.addEdge(new CodeEdge(docEl.getId(), target.getId(), EdgeType.DOCUMENTS));
                    target.setDocComment(jdc.getContent().trim());
                }
            });
        }

        private void extractAnnotations(
                com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node,
                CodeElement target) {
            for (var ann : node.getAnnotations()) {
                var annQName = target.getQualifiedName() + "@" + ann.getNameAsString();
                var annEl = newElement(ElementType.ANNOTATION, annQName);
                applyPosition(annEl, ann);
                annEl.setName(ann.getNameAsString());
                annEl.setSnippet(ann.toString());
                annEl.setParentId(target.getId());
                result.addElement(annEl);
                result.addEdge(new CodeEdge(annEl.getId(), target.getId(), EdgeType.ANNOTATES));
            }
        }

        private void extractComments(CompilationUnit cu) {
            for (var comment : cu.getAllComments()) {
                if (comment instanceof JavadocComment) continue; // handled per-element
                if (comment.getCommentedNode().isPresent()) continue; // attached, handled by element

                ElementType type = comment instanceof LineComment
                        ? ElementType.COMMENT_LINE
                        : ElementType.COMMENT_BLOCK;
                var qname = filePath + "#comment@"
                        + comment.getRange().map(r -> String.valueOf(r.begin.line)).orElse("?");
                var el = newElement(type, qname);
                applyPosition(el, comment);
                el.setSnippet(comment.getContent());
                result.addElement(el);
            }
        }

        // ---- Visitor methods ------------------------------------------------

        @Override
        public void visit(CompilationUnit cu, Void arg) {
            // Package
            cu.getPackageDeclaration().ifPresent(pkg -> {
                packageName = pkg.getNameAsString();
                var pkgEl = newElement(ElementType.PACKAGE, packageName);
                pkgEl.setName(packageName);
                applyPosition(pkgEl, pkg);
                pkgEl.setSnippet(pkg.toString().trim());
                result.addElement(pkgEl);
                packageElementId = pkgEl.getId();
            });

            // Imports
            for (var imp : cu.getImports()) {
                visit(imp, arg);
            }

            // Types
            super.visit(cu, arg);

            // Orphan comments
            extractComments(cu);
        }

        @Override
        public void visit(ImportDeclaration imp, Void arg) {
            var qname = imp.getNameAsString() + (imp.isAsterisk() ? ".*" : "");
            var el = newElement(ElementType.IMPORT, filePath + "#import:" + qname);
            el.setName(qname);
            el.setQualifiedName(qname);
            applyPosition(el, imp);
            el.setSnippet(imp.toString().trim());
            result.addElement(el);
        }

        // ---- Type declarations -----------------------------------------------

        private void visitTypeDeclaration(TypeDeclaration<?> decl, Void arg, ElementType type) {
            var simpleName = decl.getNameAsString();
            var qname = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
            // Nest inside outer class if present
            if (!typeQNameStack.isEmpty()) {
                qname = typeQNameStack.peek() + "." + simpleName;
            }

            var el = newElement(type, qname);
            el.setName(simpleName);
            applyPosition(el, decl);
            el.setSnippet(truncateSnippet(decl.toString()));
            el.setVisibility(visibility(decl));
            el.setModifiers(modifiers(decl));

            extractJavadoc(decl, el);
            extractAnnotations(decl, el);
            result.addElement(el);

            // CONTAINS: package → class (top level only)
            if (typeQNameStack.isEmpty() && packageElementId != null) {
                addContains(packageElementId, el.getId());
            } else if (!typeIdStack.isEmpty()) {
                addContains(typeIdStack.peek(), el.getId());
            }

            // EXTENDS / IMPLEMENTS
            if (decl instanceof ClassOrInterfaceDeclaration coid) {
                for (var ext : coid.getExtendedTypes()) {
                    var targetQName = resolveTypeName(ext);
                    var targetId = CodeElement.generateId(repoId, filePath, ElementType.CLASS, targetQName);
                    result.addEdge(new CodeEdge(el.getId(), targetId,
                            coid.isInterface() ? EdgeType.EXTENDS : EdgeType.EXTENDS));
                }
                for (var impl : coid.getImplementedTypes()) {
                    var targetQName = resolveTypeName(impl);
                    var targetId = CodeElement.generateId(repoId, filePath, ElementType.INTERFACE, targetQName);
                    result.addEdge(new CodeEdge(el.getId(), targetId, EdgeType.IMPLEMENTS));
                }
            }

            typeQNameStack.push(qname);
            typeIdStack.push(el.getId());

            // Visit members
            for (var member : decl.getMembers()) {
                member.accept(this, arg);
            }

            typeQNameStack.pop();
            typeIdStack.pop();
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration decl, Void arg) {
            var type = decl.isInterface() ? ElementType.INTERFACE : ElementType.CLASS;
            visitTypeDeclaration(decl, arg, type);
        }

        @Override
        public void visit(EnumDeclaration decl, Void arg) {
            visitTypeDeclaration(decl, arg, ElementType.ENUM);
        }

        @Override
        public void visit(AnnotationDeclaration decl, Void arg) {
            visitTypeDeclaration(decl, arg, ElementType.ANNOTATION);
        }

        @Override
        public void visit(RecordDeclaration decl, Void arg) {
            visitTypeDeclaration(decl, arg, ElementType.CLASS);
        }

        // ---- Members --------------------------------------------------------

        @Override
        public void visit(ConstructorDeclaration decl, Void arg) {
            if (typeQNameStack.isEmpty()) return;
            var ownerQName = typeQNameStack.peek();
            var ownerId = typeIdStack.peek();

            var paramTypes = decl.getParameters().stream()
                    .map(p -> p.getType().asString())
                    .collect(Collectors.toList());
            var sig = decl.getNameAsString() + "(" + String.join(",", paramTypes) + ")";
            var qname = ownerQName + "#" + sig;

            var el = newElement(ElementType.CONSTRUCTOR, qname);
            el.setName(decl.getNameAsString());
            el.setSignature(sig);
            el.setParameterTypes(paramTypes);
            el.setVisibility(visibility(decl));
            el.setModifiers(modifiers(decl));
            el.setParentId(ownerId);
            applyPosition(el, decl);
            el.setSnippet(truncateSnippet(decl.toString()));
            extractJavadoc(decl, el);
            extractAnnotations(decl, el);
            result.addElement(el);
            addContains(ownerId, el.getId());

            // Parameters
            visitParameters(decl.getParameters(), el);
            // Calls inside body
            extractCallsFromNode(decl, el);
        }

        @Override
        public void visit(MethodDeclaration decl, Void arg) {
            if (typeQNameStack.isEmpty()) return;
            var ownerQName = typeQNameStack.peek();
            var ownerId = typeIdStack.peek();

            var paramTypes = decl.getParameters().stream()
                    .map(p -> p.getType().asString())
                    .collect(Collectors.toList());
            var sig = decl.getNameAsString() + "(" + String.join(",", paramTypes) + ")";
            var qname = ownerQName + "#" + sig;

            var el = newElement(ElementType.METHOD, qname);
            el.setName(decl.getNameAsString());
            el.setSignature(sig);
            el.setReturnType(decl.getType().asString());
            el.setParameterTypes(paramTypes);
            el.setVisibility(visibility(decl));
            el.setModifiers(modifiers(decl));
            el.setParentId(ownerId);
            applyPosition(el, decl);
            el.setSnippet(truncateSnippet(decl.toString()));
            extractJavadoc(decl, el);
            extractAnnotations(decl, el);
            result.addElement(el);
            addContains(ownerId, el.getId());

            // Parameters
            visitParameters(decl.getParameters(), el);
            // Calls inside body
            extractCallsFromNode(decl, el);
        }

        private void visitParameters(NodeList<Parameter> params, CodeElement owner) {
            for (var param : params) {
                var qname = owner.getQualifiedName() + "#param:" + param.getNameAsString();
                var el = newElement(ElementType.PARAMETER, qname);
                el.setName(param.getNameAsString());
                el.setReturnType(param.getType().asString());
                el.setParentId(owner.getId());
                applyPosition(el, param);
                el.setSnippet(param.toString());
                result.addElement(el);
                addContains(owner.getId(), el.getId());
            }
        }

        private void extractCallsFromNode(com.github.javaparser.ast.Node node, CodeElement caller) {
            node.findAll(MethodCallExpr.class).forEach(call -> {
                var methodName = call.getNameAsString();
                // Try to determine scope/target class
                String targetClass = call.getScope()
                        .map(s -> s.toString())
                        .orElse(typeQNameStack.isEmpty() ? "" : typeQNameStack.peek());
                var callArgTypes = call.getArguments().stream()
                        .map(a -> "?")
                        .collect(Collectors.joining(","));
                var targetQName = targetClass + "#" + methodName + "(" + callArgTypes + ")";
                // We don't know the exact target file so use a synthetic id
                var targetId = CodeElement.generateId(repoId, filePath, ElementType.METHOD, targetQName);
                result.addEdge(new CodeEdge(caller.getId(), targetId, EdgeType.CALLS));
            });

            // ObjectCreationExpr → INSTANTIATES
            node.findAll(ObjectCreationExpr.class).forEach(oce -> {
                var targetQName = resolveTypeName(oce.getType());
                var targetId = CodeElement.generateId(repoId, filePath, ElementType.CLASS, targetQName);
                result.addEdge(new CodeEdge(caller.getId(), targetId, EdgeType.INSTANTIATES));
            });
        }

        @Override
        public void visit(FieldDeclaration decl, Void arg) {
            if (typeQNameStack.isEmpty()) return;
            var ownerQName = typeQNameStack.peek();
            var ownerId = typeIdStack.peek();

            for (var variable : decl.getVariables()) {
                var qname = ownerQName + "#" + variable.getNameAsString();
                var el = newElement(ElementType.FIELD, qname);
                el.setName(variable.getNameAsString());
                el.setReturnType(decl.getElementType().asString());
                el.setVisibility(visibility(decl));
                el.setModifiers(modifiers(decl));
                el.setParentId(ownerId);
                applyPosition(el, variable);
                el.setSnippet(decl.toString());
                extractJavadoc(decl, el);
                extractAnnotations(decl, el);
                result.addElement(el);
                addContains(ownerId, el.getId());
            }
        }

        @Override
        public void visit(EnumConstantDeclaration decl, Void arg) {
            if (typeQNameStack.isEmpty()) return;
            var ownerQName = typeQNameStack.peek();
            var ownerId = typeIdStack.peek();

            var qname = ownerQName + "." + decl.getNameAsString();
            var el = newElement(ElementType.ENUM_CONSTANT, qname);
            el.setName(decl.getNameAsString());
            el.setParentId(ownerId);
            applyPosition(el, decl);
            el.setSnippet(decl.toString());
            extractJavadoc(decl, el);
            result.addElement(el);
            addContains(ownerId, el.getId());
        }

        @Override
        public void visit(InitializerDeclaration decl, Void arg) {
            if (typeQNameStack.isEmpty()) return;
            var ownerQName = typeQNameStack.peek();
            var ownerId = typeIdStack.peek();

            var qname = ownerQName + "#<static_init>";
            var el = newElement(ElementType.STATIC_INITIALIZER, qname);
            el.setName("<static_init>");
            el.setParentId(ownerId);
            applyPosition(el, decl);
            el.setSnippet(truncateSnippet(decl.toString()));
            if (decl.isStatic()) el.addModifier("static");
            result.addElement(el);
            addContains(ownerId, el.getId());
        }

        // ---- Utility --------------------------------------------------------

        private String resolveTypeName(ClassOrInterfaceType type) {
            // Try to use the fully qualified form if available
            var sb = new StringBuilder();
            type.getScope().ifPresent(scope -> sb.append(scope.asString()).append("."));
            sb.append(type.getNameAsString());
            return sb.toString();
        }

        private String resolveTypeName(Type type) {
            return type.asString();
        }
    }
}
