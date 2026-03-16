package com.codegraph.parser;

import com.codegraph.core.model.CodeEdge;
import com.codegraph.core.model.CodeElement;

import java.util.ArrayList;
import java.util.List;

public class ParseResult {

    private final List<CodeElement> elements = new ArrayList<>();
    private final List<CodeEdge> edges = new ArrayList<>();
    private String filePath;
    private boolean success = true;
    private String errorMessage;

    public ParseResult(String filePath) {
        this.filePath = filePath;
    }

    public void addElement(CodeElement element) {
        elements.add(element);
    }

    public void addEdge(CodeEdge edge) {
        edges.add(edge);
    }

    public void setError(String message) {
        this.success = false;
        this.errorMessage = message;
    }

    public List<CodeElement> getElements() { return elements; }
    public List<CodeEdge> getEdges() { return edges; }
    public String getFilePath() { return filePath; }
    public boolean isSuccess() { return success; }
    public String getErrorMessage() { return errorMessage; }
}
