package com.codegraph.core.config;

import com.codegraph.core.model.Language;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RepoConfig {

    private String id;
    private String name;
    private String path;
    private List<Language> languages = new ArrayList<>();
    private List<String> excludePatterns = List.of(
        "**/target/**", "**/build/**", "**/.git/**",
        "**/node_modules/**", "**/.idea/**", "**/*.class"
    );
    private List<String> includePatterns = new ArrayList<>();
    private ClassFilesConfig classFiles = new ClassFilesConfig();
    private List<CrossRepoLink> crossRepoLinks = new ArrayList<>();
    private String description;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClassFilesConfig {
        private boolean enabled = false;
        private List<String> jarPaths = new ArrayList<>();
        private List<String> packageFilters = new ArrayList<>(); // e.g., "com.example.*"
        private List<String> classDirectories = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getJarPaths() { return jarPaths; }
        public void setJarPaths(List<String> jarPaths) { this.jarPaths = jarPaths; }
        public List<String> getPackageFilters() { return packageFilters; }
        public void setPackageFilters(List<String> packageFilters) { this.packageFilters = packageFilters; }
        public List<String> getClassDirectories() { return classDirectories; }
        public void setClassDirectories(List<String> classDirectories) { this.classDirectories = classDirectories; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CrossRepoLink {
        private String repoId;
        private String linkType;  // grpc, rest, shared-types, etc.
        private String description;

        public String getRepoId() { return repoId; }
        public void setRepoId(String repoId) { this.repoId = repoId; }
        public String getLinkType() { return linkType; }
        public void setLinkType(String linkType) { this.linkType = linkType; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public List<Language> getLanguages() { return languages; }
    public void setLanguages(List<Language> languages) { this.languages = languages; }
    public List<String> getExcludePatterns() { return excludePatterns; }
    public void setExcludePatterns(List<String> excludePatterns) { this.excludePatterns = excludePatterns; }
    public List<String> getIncludePatterns() { return includePatterns; }
    public void setIncludePatterns(List<String> includePatterns) { this.includePatterns = includePatterns; }
    public ClassFilesConfig getClassFiles() { return classFiles; }
    public void setClassFiles(ClassFilesConfig classFiles) { this.classFiles = classFiles; }
    public List<CrossRepoLink> getCrossRepoLinks() { return crossRepoLinks; }
    public void setCrossRepoLinks(List<CrossRepoLink> crossRepoLinks) { this.crossRepoLinks = crossRepoLinks; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
