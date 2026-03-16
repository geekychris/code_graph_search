package com.codegraph.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CodeElement {

    private String id;
    private String repoId;
    private ElementType elementType;
    private Language language;

    // Identity
    private String name;               // simple name (e.g., "doSomething")
    private String qualifiedName;      // fully qualified (e.g., "com.example.MyClass.doSomething")
    private String signature;          // full signature for methods

    // Location
    private String filePath;           // relative to repo root
    private int lineStart;
    private int lineEnd;
    private int colStart;
    private int colEnd;

    // Content
    private String snippet;            // actual source text
    private String docComment;         // javadoc / rustdoc / GoDoc extracted comment

    // Type info (for methods/fields)
    private String returnType;
    private List<String> parameterTypes;
    private String visibility;         // public, private, protected, package, pub, etc.
    private List<String> modifiers;    // static, final, abstract, async, etc.

    // Relationships (stored for quick access; edges in graph are authoritative)
    private String parentId;           // direct container element id

    // Language-specific extras
    private Map<String, String> metadata;

    public CodeElement() {}

    public static String generateId(String repoId, String filePath, ElementType type, String qualifiedName) {
        try {
            String raw = repoId + "|" + filePath + "|" + type.name() + "|" + qualifiedName;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return repoId + "_" + Math.abs(qualifiedName.hashCode());
        }
    }

    public void addMetadata(String key, String value) {
        if (metadata == null) metadata = new HashMap<>();
        metadata.put(key, value);
    }

    public void addModifier(String modifier) {
        if (modifiers == null) modifiers = new ArrayList<>();
        modifiers.add(modifier);
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRepoId() { return repoId; }
    public void setRepoId(String repoId) { this.repoId = repoId; }

    public ElementType getElementType() { return elementType; }
    public void setElementType(ElementType elementType) { this.elementType = elementType; }

    public Language getLanguage() { return language; }
    public void setLanguage(Language language) { this.language = language; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getQualifiedName() { return qualifiedName; }
    public void setQualifiedName(String qualifiedName) { this.qualifiedName = qualifiedName; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public int getLineStart() { return lineStart; }
    public void setLineStart(int lineStart) { this.lineStart = lineStart; }

    public int getLineEnd() { return lineEnd; }
    public void setLineEnd(int lineEnd) { this.lineEnd = lineEnd; }

    public int getColStart() { return colStart; }
    public void setColStart(int colStart) { this.colStart = colStart; }

    public int getColEnd() { return colEnd; }
    public void setColEnd(int colEnd) { this.colEnd = colEnd; }

    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }

    public String getDocComment() { return docComment; }
    public void setDocComment(String docComment) { this.docComment = docComment; }

    public String getReturnType() { return returnType; }
    public void setReturnType(String returnType) { this.returnType = returnType; }

    public List<String> getParameterTypes() { return parameterTypes; }
    public void setParameterTypes(List<String> parameterTypes) { this.parameterTypes = parameterTypes; }

    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }

    public List<String> getModifiers() { return modifiers; }
    public void setModifiers(List<String> modifiers) { this.modifiers = modifiers; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

    @Override
    public String toString() {
        return elementType + ":" + qualifiedName + " (" + filePath + ":" + lineStart + ")";
    }
}
