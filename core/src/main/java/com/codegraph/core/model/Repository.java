package com.codegraph.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Repository {

    private String id;
    private String name;
    private String rootPath;
    private List<Language> languages;
    private IndexingStatus status;
    private Instant lastIndexed;
    private int elementCount;
    private int fileCount;
    private String description;

    public enum IndexingStatus {
        PENDING, INDEXING, READY, ERROR, WATCHING
    }

    public Repository() {}

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRootPath() { return rootPath; }
    public void setRootPath(String rootPath) { this.rootPath = rootPath; }

    public List<Language> getLanguages() { return languages; }
    public void setLanguages(List<Language> languages) { this.languages = languages; }

    public IndexingStatus getStatus() { return status; }
    public void setStatus(IndexingStatus status) { this.status = status; }

    public Instant getLastIndexed() { return lastIndexed; }
    public void setLastIndexed(Instant lastIndexed) { this.lastIndexed = lastIndexed; }

    public int getElementCount() { return elementCount; }
    public void setElementCount(int elementCount) { this.elementCount = elementCount; }

    public int getFileCount() { return fileCount; }
    public void setFileCount(int fileCount) { this.fileCount = fileCount; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
