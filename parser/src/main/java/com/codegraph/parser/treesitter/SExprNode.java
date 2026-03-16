package com.codegraph.parser.treesitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Represents a single node in a tree-sitter S-expression parse tree.
 *
 * <p>Example S-expression fragment that produces one of these nodes:
 * <pre>
 *   name: (identifier [2, 5] - [2, 9])
 * </pre>
 *
 * <p>Position values are 0-indexed (as emitted by tree-sitter).
 * Consumers should add 1 when converting to {@code CodeElement} line/column fields.
 */
public class SExprNode {

    /** The grammar node type, e.g. {@code "function_declaration"}, {@code "identifier"}. */
    private final String type;

    /**
     * The named field label from the parent, e.g. {@code "name"} for {@code name: (identifier...)}.
     * {@code null} if this node is an unnamed child.
     */
    private final String namedField;

    /** 0-indexed start line. */
    private final int startLine;
    /** 0-indexed start column. */
    private final int startCol;
    /** 0-indexed end line (inclusive). */
    private final int endLine;
    /** 0-indexed end column (exclusive on end line). */
    private final int endCol;

    /** Direct children (in source order). */
    private final List<SExprNode> children;

    /**
     * The source text of this node, extracted from the original source file using the
     * position range. Populated after construction by {@link SExprParser}.
     * May be {@code null} for non-leaf compound nodes.
     */
    private String text;

    public SExprNode(String type, String namedField,
                     int startLine, int startCol, int endLine, int endCol) {
        this.type = type;
        this.namedField = namedField;
        this.startLine = startLine;
        this.startCol = startCol;
        this.endLine = endLine;
        this.endCol = endCol;
        this.children = new ArrayList<>();
    }

    // -------------------------------------------------------------------------
    // Query helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if this node has no children (leaf node).
     */
    public boolean isLeaf() {
        return children.isEmpty();
    }

    /**
     * Depth-first search returning the first descendant (or self) whose
     * {@link #getType()} equals {@code nodeType}.
     */
    public Optional<SExprNode> findFirst(String nodeType) {
        if (this.type.equals(nodeType)) return Optional.of(this);
        for (var child : children) {
            var found = child.findFirst(nodeType);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    /**
     * Depth-first search returning all descendants (and possibly self) whose
     * {@link #getType()} equals {@code nodeType}.
     */
    public List<SExprNode> findAll(String nodeType) {
        var acc = new ArrayList<SExprNode>();
        collectAll(nodeType, acc);
        return acc;
    }

    private void collectAll(String nodeType, List<SExprNode> acc) {
        if (this.type.equals(nodeType)) acc.add(this);
        for (var child : children) child.collectAll(nodeType, acc);
    }

    /**
     * Returns the first <em>direct</em> child whose {@link #getNamedField()} equals
     * {@code field}.
     */
    public Optional<SExprNode> getNamedChild(String field) {
        return children.stream()
                .filter(c -> field.equals(c.namedField))
                .findFirst();
    }

    /**
     * Returns the first direct child whose {@link #getType()} equals {@code childType}.
     */
    public Optional<SExprNode> getChildByType(String childType) {
        return children.stream()
                .filter(c -> childType.equals(c.type))
                .findFirst();
    }

    /**
     * Returns the text of the {@code name:} child node, or an empty string if absent.
     */
    public String nameText() {
        return getNamedChild("name")
                .map(n -> n.getText() != null ? n.getText() : "")
                .orElse("");
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getType()         { return type; }
    public String getNamedField()   { return namedField; }
    public int getStartLine()       { return startLine; }
    public int getStartCol()        { return startCol; }
    public int getEndLine()         { return endLine; }
    public int getEndCol()          { return endCol; }
    public List<SExprNode> getChildren() { return children; }

    public String getText()         { return text; }
    public void setText(String t)   { this.text = t; }

    void addChild(SExprNode child) { children.add(child); }

    @Override
    public String toString() {
        return "SExprNode{type='" + type + "', field='" + namedField
                + "', [" + startLine + "," + startCol + "]-[" + endLine + "," + endCol + "]"
                + (text != null ? ", text='" + text + "'" : "")
                + "}";
    }
}
