package com.codegraph.parser.classfile;

import com.codegraph.core.model.*;
import com.codegraph.parser.ParseResult;
import org.objectweb.asm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * Parses Java {@code .class} files and JAR archives using ASM.
 *
 * <p>Extracts the same structural elements as {@link com.codegraph.parser.java.JavaSourceParser}
 * but from bytecode — no source snippets are available; a placeholder is set instead.
 *
 * <p>Typical usage:
 * <pre>{@code
 * var parser = new ClassFileParser(List.of("com.example", "org.mylib"), List.of());
 * List<ParseResult> results = parser.parseJar(Path.of("library.jar"));
 * ParseResult single = parser.parseClassFile(Path.of("MyClass.class"), "my-repo");
 * }</pre>
 */
public class ClassFileParser {

    private static final Logger log = LoggerFactory.getLogger(ClassFileParser.class);
    private static final String BYTECODE_SNIPPET = "// [bytecode — no source available]";

    /**
     * Simple configuration for class-file parsing.
     *
     * @param packageFilters  list of package prefixes to include (e.g. "com.example").
     *                        Empty list means include everything.
     * @param excludePatterns list of regex patterns for qualified names to exclude.
     */
    public record ClassFilesConfig(List<String> packageFilters, List<String> excludePatterns) {
        public static ClassFilesConfig all() {
            return new ClassFilesConfig(List.of(), List.of());
        }
    }

    private final ClassFilesConfig config;
    private final List<Pattern> excludeCompiledPatterns;

    public ClassFileParser(ClassFilesConfig config) {
        this.config = config;
        this.excludeCompiledPatterns = config.excludePatterns().stream()
                .map(Pattern::compile)
                .toList();
    }

    public ClassFileParser() {
        this(ClassFilesConfig.all());
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Parse all {@code .class} entries in the given JAR file.
     *
     * @param jarFile absolute path to the JAR
     * @return one {@link ParseResult} per parsed class entry
     */
    public List<ParseResult> parseJar(Path jarFile) {
        var results = new ArrayList<ParseResult>();
        var repoId = jarFile.getFileName().toString();

        try (var jar = new JarFile(jarFile.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;

                var className = entryToClassName(entry.getName());
                if (!shouldInclude(className)) continue;

                try (var in = jar.getInputStream(entry)) {
                    var result = parseClassStream(in, className, repoId, jarFile.toString());
                    if (result != null) results.add(result);
                } catch (Exception e) {
                    log.warn("Failed to parse class {} in {}: {}", className, jarFile, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Cannot open JAR {}: {}", jarFile, e.getMessage());
        }
        return results;
    }

    /**
     * Parse a single {@code .class} file.
     *
     * @param classFile absolute path to the .class file
     * @param repoId    repository identifier
     */
    public ParseResult parseClassFile(Path classFile, String repoId) {
        var className = guessClassNameFromPath(classFile);
        if (!shouldInclude(className)) {
            return null;
        }
        try (var in = Files.newInputStream(classFile)) {
            return parseClassStream(in, className, repoId, classFile.toString());
        } catch (IOException e) {
            log.error("Cannot read {}: {}", classFile, e.getMessage());
            var r = new ParseResult(classFile.toString());
            r.setError("IO error: " + e.getMessage());
            return r;
        }
    }

    // -------------------------------------------------------------------------
    // Internal parsing
    // -------------------------------------------------------------------------

    private ParseResult parseClassStream(InputStream in, String className,
                                          String repoId, String sourceLabel) {
        var result = new ParseResult(sourceLabel);
        try {
            var reader = new ClassReader(in);
            var visitor = new GraphClassVisitor(className, repoId, sourceLabel, result);
            reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Exception e) {
            log.warn("ASM failed on {}: {}", className, e.getMessage());
            result.setError("ASM error: " + e.getMessage());
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Filters
    // -------------------------------------------------------------------------

    private boolean shouldInclude(String className) {
        if (className == null || className.isBlank()) return false;
        // Package filter
        if (!config.packageFilters().isEmpty()) {
            boolean matched = config.packageFilters().stream()
                    .anyMatch(prefix -> className.startsWith(prefix + ".") || className.equals(prefix));
            if (!matched) return false;
        }
        // Exclude patterns
        for (var pattern : excludeCompiledPatterns) {
            if (pattern.matcher(className).find()) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // ASM visitor
    // -------------------------------------------------------------------------

    private static class GraphClassVisitor extends ClassVisitor {

        private final String repoId;
        private final String filePath;  // jar path or class file path (used as "filePath" in elements)
        private final ParseResult result;
        private final String className; // e.g. "com.example.MyClass"

        private CodeElement classElement;

        GraphClassVisitor(String className, String repoId, String filePath, ParseResult result) {
            super(Opcodes.ASM9);
            this.className = className;
            this.repoId = repoId;
            this.filePath = filePath;
            this.result = result;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            var elType = resolveClassType(access);
            var el = newElement(elType, className);
            el.setName(simpleClassName(className));
            el.setVisibility(accessToVisibility(access));
            el.setSnippet(BYTECODE_SNIPPET);
            el.setLineStart(0);

            var mods = accessToModifiers(access);
            mods.forEach(el::addModifier);

            // Super class
            if (superName != null && !superName.equals("java/lang/Object")) {
                var superQName = internalToQName(superName);
                var superId = CodeElement.generateId(repoId, filePath, ElementType.CLASS, superQName);
                result.addEdge(new CodeEdge(el.getId(), superId, EdgeType.EXTENDS));
            }
            // Interfaces
            if (interfaces != null) {
                for (var iface : interfaces) {
                    var ifaceQName = internalToQName(iface);
                    var ifaceId = CodeElement.generateId(repoId, filePath, ElementType.INTERFACE, ifaceQName);
                    result.addEdge(new CodeEdge(el.getId(), ifaceId, EdgeType.IMPLEMENTS));
                }
            }

            result.addElement(el);
            classElement = el;

            // Package element
            var pkgName = packageOf(className);
            if (!pkgName.isEmpty()) {
                var pkgEl = newElement(ElementType.PACKAGE, pkgName);
                pkgEl.setName(pkgName);
                pkgEl.setId(CodeElement.generateId(repoId, filePath, ElementType.PACKAGE, pkgName));
                result.addElement(pkgEl);
                result.addEdge(new CodeEdge(pkgEl.getId(), el.getId(), EdgeType.CONTAINS));
            }
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                          String signature, String[] exceptions) {
            if (classElement == null) return null;
            // Skip synthetic / bridge methods
            if ((access & Opcodes.ACC_SYNTHETIC) != 0) return null;

            var paramTypes = descriptorToParamTypes(descriptor);
            var returnType = descriptorToReturnType(descriptor);
            var sig = name + "(" + String.join(",", paramTypes) + ")";
            var qname = className + "#" + sig;

            ElementType elType;
            if (name.equals("<init>")) {
                elType = ElementType.CONSTRUCTOR;
            } else if (name.equals("<clinit>")) {
                elType = ElementType.STATIC_INITIALIZER;
            } else {
                elType = ElementType.METHOD;
            }

            var el = newElement(elType, qname);
            el.setName(name.equals("<init>") ? simpleClassName(className) : name);
            el.setSignature(sig);
            el.setReturnType(returnType);
            el.setParameterTypes(paramTypes);
            el.setVisibility(accessToVisibility(access));
            el.setSnippet(BYTECODE_SNIPPET);
            el.setParentId(classElement.getId());

            var mods = accessToModifiers(access);
            mods.forEach(el::addModifier);

            result.addElement(el);
            result.addEdge(new CodeEdge(classElement.getId(), el.getId(), EdgeType.CONTAINS));
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                        String signature, Object value) {
            if (classElement == null) return null;
            if ((access & Opcodes.ACC_SYNTHETIC) != 0) return null;

            var qname = className + "#" + name;
            var el = newElement(ElementType.FIELD, qname);
            el.setName(name);
            el.setReturnType(descriptorToTypeName(descriptor));
            el.setVisibility(accessToVisibility(access));
            el.setSnippet(BYTECODE_SNIPPET);
            el.setParentId(classElement.getId());

            accessToModifiers(access).forEach(el::addModifier);

            result.addElement(el);
            result.addEdge(new CodeEdge(classElement.getId(), el.getId(), EdgeType.CONTAINS));
            return null;
        }

        // ---- Utilities -------------------------------------------------------

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

        private static ElementType resolveClassType(int access) {
            if ((access & Opcodes.ACC_INTERFACE) != 0) return ElementType.INTERFACE;
            if ((access & Opcodes.ACC_ENUM)      != 0) return ElementType.ENUM;
            if ((access & Opcodes.ACC_ANNOTATION) != 0) return ElementType.ANNOTATION;
            return ElementType.CLASS;
        }

        private static String accessToVisibility(int access) {
            if ((access & Opcodes.ACC_PUBLIC)    != 0) return "public";
            if ((access & Opcodes.ACC_PROTECTED) != 0) return "protected";
            if ((access & Opcodes.ACC_PRIVATE)   != 0) return "private";
            return "package";
        }

        private static List<String> accessToModifiers(int access) {
            var list = new ArrayList<String>();
            if ((access & Opcodes.ACC_STATIC)    != 0) list.add("static");
            if ((access & Opcodes.ACC_FINAL)     != 0) list.add("final");
            if ((access & Opcodes.ACC_ABSTRACT)  != 0) list.add("abstract");
            if ((access & Opcodes.ACC_NATIVE)    != 0) list.add("native");
            if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) list.add("synchronized");
            if ((access & Opcodes.ACC_VOLATILE)  != 0) list.add("volatile");
            if ((access & Opcodes.ACC_TRANSIENT) != 0) list.add("transient");
            return list;
        }

        /** Convert internal name (slashes) to qualified name (dots). */
        private static String internalToQName(String internal) {
            return internal.replace('/', '.');
        }

        /** Extract package name from fully qualified class name. */
        private static String packageOf(String qname) {
            int dot = qname.lastIndexOf('.');
            return dot < 0 ? "" : qname.substring(0, dot);
        }

        /** Simple (unqualified) class name. */
        private static String simpleClassName(String qname) {
            int dot = qname.lastIndexOf('.');
            return dot < 0 ? qname : qname.substring(dot + 1);
        }

        /** Parse a method descriptor and return the list of parameter type names. */
        private static List<String> descriptorToParamTypes(String descriptor) {
            var params = new ArrayList<String>();
            int i = 1; // skip opening '('
            while (i < descriptor.length() && descriptor.charAt(i) != ')') {
                int[] advance = new int[1];
                var typeName = parseDescriptorType(descriptor, i, advance);
                params.add(typeName);
                i = advance[0];
            }
            return params;
        }

        /** Return the return type from a method descriptor. */
        private static String descriptorToReturnType(String descriptor) {
            int close = descriptor.indexOf(')');
            if (close < 0 || close + 1 >= descriptor.length()) return "void";
            return parseDescriptorType(descriptor, close + 1, new int[1]);
        }

        /** Parse a single type from a field descriptor. */
        private static String descriptorToTypeName(String descriptor) {
            return parseDescriptorType(descriptor, 0, new int[1]);
        }

        private static String parseDescriptorType(String desc, int pos, int[] advance) {
            if (pos >= desc.length()) { advance[0] = pos + 1; return "?"; }
            char c = desc.charAt(pos);
            switch (c) {
                case 'Z': advance[0] = pos + 1; return "boolean";
                case 'B': advance[0] = pos + 1; return "byte";
                case 'C': advance[0] = pos + 1; return "char";
                case 'D': advance[0] = pos + 1; return "double";
                case 'F': advance[0] = pos + 1; return "float";
                case 'I': advance[0] = pos + 1; return "int";
                case 'J': advance[0] = pos + 1; return "long";
                case 'S': advance[0] = pos + 1; return "short";
                case 'V': advance[0] = pos + 1; return "void";
                case 'L': {
                    int semi = desc.indexOf(';', pos);
                    if (semi < 0) { advance[0] = desc.length(); return "?"; }
                    advance[0] = semi + 1;
                    return desc.substring(pos + 1, semi).replace('/', '.');
                }
                case '[': {
                    var inner = parseDescriptorType(desc, pos + 1, advance);
                    return inner + "[]";
                }
                default: advance[0] = pos + 1; return String.valueOf(c);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Path utilities
    // -------------------------------------------------------------------------

    /** Convert a JAR entry path like {@code com/example/MyClass.class} to a class name. */
    private static String entryToClassName(String entryName) {
        return entryName.replace('/', '.').replace('\\', '.')
                .replaceAll("\\.class$", "");
    }

    /** Best-effort class name from a .class file path. */
    private static String guessClassNameFromPath(Path classFile) {
        var name = classFile.getFileName().toString();
        return name.endsWith(".class") ? name.substring(0, name.length() - 6) : name;
    }
}
