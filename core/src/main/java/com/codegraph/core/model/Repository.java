package com.codegraph.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    // Portfolio fields: where the repo comes from and who may see it
    private Visibility visibility;
    private String source;          // "config", "local" (added at runtime) or "github"
    private String gitUrl;
    private String htmlUrl;
    private String defaultBranch;
    private String indexedCommit;   // commit SHA the index reflects, for git-backed repos
    private Instant pushedAt;
    private boolean archived;

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

    /** GitHub visibility. {@code null} means unknown and is treated as {@link Visibility#PRIVATE}. */
    public Visibility getVisibility() { return visibility; }
    public void setVisibility(Visibility visibility) { this.visibility = visibility; }

    /** True only when the repo is known to be public; unknown visibility fails closed. */
    @JsonIgnore
    public boolean isPublic() { return visibility == Visibility.PUBLIC; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getGitUrl() { return gitUrl; }
    public void setGitUrl(String gitUrl) { this.gitUrl = gitUrl; }

    public String getHtmlUrl() { return htmlUrl; }
    public void setHtmlUrl(String htmlUrl) { this.htmlUrl = htmlUrl; }

    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }

    public String getIndexedCommit() { return indexedCommit; }
    public void setIndexedCommit(String indexedCommit) { this.indexedCommit = indexedCommit; }

    public Instant getPushedAt() { return pushedAt; }
    public void setPushedAt(Instant pushedAt) { this.pushedAt = pushedAt; }

    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
}
