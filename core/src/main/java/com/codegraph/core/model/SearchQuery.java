package com.codegraph.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SearchQuery {

    private String query;                    // full-text query
    private List<String> repoIds;            // filter by repos (null = all)
    private List<ElementType> elementTypes;  // filter by element types
    private List<Language> languages;        // filter by language
    private String filePathPattern;          // glob pattern for file path filter
    private String qualifiedNamePrefix;      // prefix match on qualified name
    private String visibility;               // public, private, etc.
    private String modifierFilter;           // static, abstract, etc.
    private SortField sortBy;
    private boolean sortAscending = true;
    private int limit = 50;
    private int offset = 0;

    public enum SortField {
        RELEVANCE, NAME, QUALIFIED_NAME, FILE_PATH, LINE, ELEMENT_TYPE, LANGUAGE
    }

    public SearchQuery() {}

    public SearchQuery(String query) {
        this.query = query;
    }

    // Getters and setters
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public List<String> getRepoIds() { return repoIds; }
    public void setRepoIds(List<String> repoIds) { this.repoIds = repoIds; }

    public List<ElementType> getElementTypes() { return elementTypes; }
    public void setElementTypes(List<ElementType> elementTypes) { this.elementTypes = elementTypes; }

    public List<Language> getLanguages() { return languages; }
    public void setLanguages(List<Language> languages) { this.languages = languages; }

    public String getFilePathPattern() { return filePathPattern; }
    public void setFilePathPattern(String filePathPattern) { this.filePathPattern = filePathPattern; }

    public String getQualifiedNamePrefix() { return qualifiedNamePrefix; }
    public void setQualifiedNamePrefix(String qualifiedNamePrefix) { this.qualifiedNamePrefix = qualifiedNamePrefix; }

    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }

    public String getModifierFilter() { return modifierFilter; }
    public void setModifierFilter(String modifierFilter) { this.modifierFilter = modifierFilter; }

    public SortField getSortBy() { return sortBy; }
    public void setSortBy(SortField sortBy) { this.sortBy = sortBy; }

    public boolean isSortAscending() { return sortAscending; }
    public void setSortAscending(boolean sortAscending) { this.sortAscending = sortAscending; }

    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = Math.min(limit, 500); }

    public int getOffset() { return offset; }
    public void setOffset(int offset) { this.offset = offset; }
}
