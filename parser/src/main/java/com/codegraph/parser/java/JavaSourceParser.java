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
        // Fully-qualified class names imported by this file (non-wildcard,
        // non-static). Used by qualifyClassRef in the call-target
        // resolver so `SomeClass.method()` calls find the actual
        // declaration across files.
        private final java.util.Set<String> importedClassQNames = new java.util.HashSet<>();

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
            // Track non-wildcard imports for call-target class-ref
            // resolution (see qualifyClassRef in extractCallsFromNode).
            if (!imp.isAsterisk() && !imp.isStatic()) {
                importedClassQNames.add(imp.getNameAsString());
            }
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
            // See the equivalent block in visit(MethodDeclaration): qname
            // is name-only so cross-file references (INSTANTIATES edges
            // from `new Foo(...)`) match a name-based lookup without
            // needing to resolve caller arg types.
            var qname = ownerQName + "#" + decl.getNameAsString();

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
            // Qname is name-only (no arg-type suffix) so cross-file call
            // extraction can match by owner + method-name without needing
            // a full symbol solver to resolve caller arg types to
            // declared param types. Overloads collapse in the graph;
            // Signature field still carries the full arg-typed signature
            // for display + downstream disambiguation.
            var qname = ownerQName + "#" + decl.getNameAsString();

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
                // Resolve the target class qname. Two shapes:
                //   1. Unqualified `bar()` — no scope. Assume same class
                //      (top of the type stack). Cross-file-safe because
                //      typeQNameStack.peek() is already the fully-
                //      qualified class name.
                //   2. Qualified `foo.bar()` — scope present. If the
                //      scope is source text that matches a known class
                //      name (PascalCase or matches an import), use it;
                //      otherwise it's a variable + we can't resolve
                //      without a SymbolSolver. Fall back to same-class
                //      as best effort.
                String targetClass;
                if (call.getScope().isEmpty()) {
                    targetClass = typeQNameStack.isEmpty() ? "" : typeQNameStack.peek();
                } else {
                    var scopeText = call.getScope().get().toString().trim();
                    if (isLikelyClassName(scopeText)) {
                        // PascalCase — treat as a class reference. Try
                        // to qualify via package or import; fall back
                        // to raw text.
                        targetClass = qualifyClassRef(scopeText);
                    } else {
                        // Variable reference — real resolution needs a
                        // SymbolSolver we don't have. Same-class fallback
                        // catches the common `this.bar()` case.
                        targetClass = typeQNameStack.isEmpty() ? "" : typeQNameStack.peek();
                    }
                }
                // Name-only qname to match the declaration side (which
                // also switched to name-only for cross-file callability
                // — arg-typed signature lives in Signature field).
                var targetQName = targetClass + "#" + methodName;
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

        /**
         * Heuristic: does this look like a Java class name? Starts with
         * uppercase, is a single identifier (no dots/parens/brackets),
         * doesn't look like a constant (all caps + underscore is a
         * constant convention, not a class).
         */
        private boolean isLikelyClassName(String s) {
            if (s.isEmpty() || !Character.isUpperCase(s.charAt(0))) return false;
            if (s.contains("(") || s.contains("[") || s.contains(".")) return false;
            // ALL_CAPS_WITH_UNDERSCORES is typically a constant, not a class.
            var allCaps = true;
            for (var c : s.toCharArray()) {
                if (Character.isLowerCase(c)) { allCaps = false; break; }
            }
            if (allCaps && s.contains("_")) return false;
            return true;
        }

        /**
         * Turn a bare class name (`Foo`) into its qualified form using
         * the current package + import list. Falls back to the raw name
         * if no import matches (java.lang.* is implicit but rarely
         * called via static-method style).
         */
        private String qualifyClassRef(String simpleName) {
            // Try imports first — an `import a.b.Foo;` maps simple name Foo → a.b.Foo.
            for (var imp : importedClassQNames) {
                if (imp.endsWith("." + simpleName)) return imp;
            }
            // Fall back to same-package qualification.
            return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
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
            // Use fully-qualified form if the source has one; otherwise
            // look up in imports + fall back to same-package.
            if (type.getScope().isPresent()) {
                return type.getScope().get().asString() + "." + type.getNameAsString();
            }
            return qualifyClassRef(type.getNameAsString());
        }

        private String resolveTypeName(Type type) {
            // Also try to qualify a plain type expression when it's a
            // ClassOrInterfaceType — the earlier overload only fires
            // when the caller has the specific subclass in hand.
            if (type instanceof ClassOrInterfaceType coit) {
                return resolveTypeName(coit);
            }
            return type.asString();
        }
    }
}
