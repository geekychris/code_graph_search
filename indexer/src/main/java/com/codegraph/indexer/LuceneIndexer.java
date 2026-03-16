package com.codegraph.indexer;

import com.codegraph.core.model.CodeElement;
import com.codegraph.core.model.ElementType;
import com.codegraph.core.model.Language;
import com.codegraph.core.model.SearchQuery;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.highlight.*;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages a Lucene full-text index for CodeElement search.
 * Thread-safe: IndexWriter is thread-safe; SearcherManager handles readers.
 */
public class LuceneIndexer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LuceneIndexer.class);

    // Field names
    static final String F_ID             = "id";
    static final String F_REPO_ID        = "repo_id";
    static final String F_ELEMENT_TYPE   = "element_type";
    static final String F_LANGUAGE       = "language";
    static final String F_NAME           = "name";
    static final String F_NAME_EXACT     = "name_exact";
    static final String F_QUALIFIED_NAME = "qualified_name";
    static final String F_QN_EXACT       = "qualified_name_exact";
    static final String F_FILE_PATH      = "file_path";
    static final String F_LINE_START     = "line_start";
    static final String F_LINE_END       = "line_end";
    static final String F_SNIPPET        = "snippet";
    static final String F_DOC_COMMENT    = "doc_comment";
    static final String F_SIGNATURE      = "signature";
    static final String F_VISIBILITY     = "visibility";
    static final String F_PARENT_ID      = "parent_id";
    static final String F_MODIFIERS      = "modifiers";
    static final String F_RETURN_TYPE    = "return_type";

    private static final int AUTO_COMMIT_THRESHOLD = 1000;

    private final FSDirectory directory;
    private final StandardAnalyzer analyzer;
    private final IndexWriter writer;
    private final SearcherManager searcherManager;
    private final AtomicInteger writesSinceCommit = new AtomicInteger(0);

    public LuceneIndexer(Path indexDir) throws IOException {
        this.directory = FSDirectory.open(indexDir);
        this.analyzer = new StandardAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        this.writer = new IndexWriter(directory, config);
        this.searcherManager = new SearcherManager(writer, null);
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    public void indexElement(CodeElement el) {
        if (el == null || el.getId() == null) return;
        try {
            Document doc = toDocument(el);
            // Use updateDocument to upsert by id term
            writer.updateDocument(new Term(F_ID, el.getId()), doc);
            int count = writesSinceCommit.incrementAndGet();
            if (count >= AUTO_COMMIT_THRESHOLD) {
                commit();
            }
        } catch (IOException e) {
            log.error("Failed to index element {}: {}", el.getId(), e.getMessage(), e);
        }
    }

    public void indexElements(List<CodeElement> elements) {
        for (CodeElement el : elements) {
            indexElement(el);
        }
    }

    public void deleteElementsForFile(String repoId, String filePath) {
        try {
            BooleanQuery.Builder q = new BooleanQuery.Builder();
            q.add(new TermQuery(new Term(F_REPO_ID, repoId)), BooleanClause.Occur.MUST);
            q.add(new TermQuery(new Term(F_FILE_PATH, filePath)), BooleanClause.Occur.MUST);
            writer.deleteDocuments(q.build());
            writesSinceCommit.incrementAndGet();
        } catch (IOException e) {
            log.error("Failed to delete elements for file {}/{}: {}", repoId, filePath, e.getMessage(), e);
        }
    }

    public void deleteByRepo(String repoId) {
        try {
            writer.deleteDocuments(new Term(F_REPO_ID, repoId));
            writesSinceCommit.incrementAndGet();
        } catch (IOException e) {
            log.error("Failed to delete repo {}: {}", repoId, e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    public List<CodeElement> search(SearchQuery query) {
        try {
            Query luceneQuery = buildQuery(query);
            Sort sort = buildSort(query);

            int needed = query.getOffset() + query.getLimit();
            if (needed <= 0) needed = 50;

            searcherManager.maybeRefresh();
            IndexSearcher searcher = searcherManager.acquire();
            try {
                TopDocs topDocs;
                if (sort != null) {
                    topDocs = searcher.search(luceneQuery, needed, sort);
                } else {
                    topDocs = searcher.search(luceneQuery, needed);
                }

                List<CodeElement> results = new ArrayList<>();
                int start = query.getOffset();
                int end = Math.min(topDocs.scoreDocs.length, needed);

                for (int i = start; i < end; i++) {
                    Document doc = searcher.storedFields().document(topDocs.scoreDocs[i].doc);
                    CodeElement el = fromDocument(doc);
                    if (el != null) results.add(el);
                }
                return results;
            } finally {
                searcherManager.release(searcher);
            }
        } catch (Exception e) {
            log.error("Search failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    public long count(SearchQuery query) {
        try {
            Query luceneQuery = buildQuery(query);
            searcherManager.maybeRefresh();
            IndexSearcher searcher = searcherManager.acquire();
            try {
                return searcher.count(luceneQuery);
            } finally {
                searcherManager.release(searcher);
            }
        } catch (Exception e) {
            log.error("Count failed: {}", e.getMessage(), e);
            return 0L;
        }
    }

    // -------------------------------------------------------------------------
    // Query building
    // -------------------------------------------------------------------------

    private Query buildQuery(SearchQuery sq) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        boolean hasFilters = false;

        // Full-text query across multiple fields
        String queryText = sq.getQuery();
        if (queryText != null && !queryText.isBlank()) {
            BooleanQuery.Builder ftBuilder = new BooleanQuery.Builder();
            for (String field : new String[]{F_NAME, F_QUALIFIED_NAME, F_SNIPPET, F_DOC_COMMENT, F_SIGNATURE}) {
                try {
                    org.apache.lucene.queryparser.classic.QueryParser qp =
                        new org.apache.lucene.queryparser.classic.QueryParser(field, analyzer);
                    qp.setDefaultOperator(org.apache.lucene.queryparser.classic.QueryParser.Operator.AND);
                    String escaped = org.apache.lucene.queryparser.classic.QueryParser.escape(queryText);
                    // Try parsed query first, fallback to wildcard prefix
                    Query parsedQuery = qp.parse(escaped);
                    ftBuilder.add(parsedQuery, BooleanClause.Occur.SHOULD);
                } catch (Exception e) {
                    // fallback: term query on the field
                    ftBuilder.add(new TermQuery(new Term(field, queryText.toLowerCase())), BooleanClause.Occur.SHOULD);
                }
            }
            ftBuilder.setMinimumNumberShouldMatch(1);
            builder.add(ftBuilder.build(), BooleanClause.Occur.MUST);
            hasFilters = true;
        }

        // Repo filter
        List<String> repoIds = sq.getRepoIds();
        if (repoIds != null && !repoIds.isEmpty()) {
            if (repoIds.size() == 1) {
                builder.add(new TermQuery(new Term(F_REPO_ID, repoIds.get(0))), BooleanClause.Occur.MUST);
            } else {
                BooleanQuery.Builder repoBuilder = new BooleanQuery.Builder();
                for (String rid : repoIds) {
                    repoBuilder.add(new TermQuery(new Term(F_REPO_ID, rid)), BooleanClause.Occur.SHOULD);
                }
                repoBuilder.setMinimumNumberShouldMatch(1);
                builder.add(repoBuilder.build(), BooleanClause.Occur.MUST);
            }
            hasFilters = true;
        }

        // Element type filter
        List<ElementType> elementTypes = sq.getElementTypes();
        if (elementTypes != null && !elementTypes.isEmpty()) {
            BooleanQuery.Builder etBuilder = new BooleanQuery.Builder();
            for (ElementType et : elementTypes) {
                etBuilder.add(new TermQuery(new Term(F_ELEMENT_TYPE, et.name())), BooleanClause.Occur.SHOULD);
            }
            etBuilder.setMinimumNumberShouldMatch(1);
            builder.add(etBuilder.build(), BooleanClause.Occur.MUST);
            hasFilters = true;
        }

        // Language filter
        List<Language> languages = sq.getLanguages();
        if (languages != null && !languages.isEmpty()) {
            BooleanQuery.Builder langBuilder = new BooleanQuery.Builder();
            for (Language lang : languages) {
                langBuilder.add(new TermQuery(new Term(F_LANGUAGE, lang.name())), BooleanClause.Occur.SHOULD);
            }
            langBuilder.setMinimumNumberShouldMatch(1);
            builder.add(langBuilder.build(), BooleanClause.Occur.MUST);
            hasFilters = true;
        }

        // File path pattern (glob -> wildcard query)
        String filePathPattern = sq.getFilePathPattern();
        if (filePathPattern != null && !filePathPattern.isBlank()) {
            // Convert glob wildcards to Lucene wildcard syntax (already compatible: * and ?)
            Query fpQuery = new WildcardQuery(new Term(F_FILE_PATH, filePathPattern.toLowerCase()));
            builder.add(fpQuery, BooleanClause.Occur.MUST);
            hasFilters = true;
        }

        // Qualified name prefix
        String qnPrefix = sq.getQualifiedNamePrefix();
        if (qnPrefix != null && !qnPrefix.isBlank()) {
            builder.add(new PrefixQuery(new Term(F_QN_EXACT, qnPrefix.toLowerCase())), BooleanClause.Occur.MUST);
            hasFilters = true;
        }

        // Visibility filter
        String visibility = sq.getVisibility();
        if (visibility != null && !visibility.isBlank()) {
            builder.add(new TermQuery(new Term(F_VISIBILITY, visibility.toLowerCase())), BooleanClause.Occur.MUST);
            hasFilters = true;
        }

        // Modifier filter (stored as space-separated; use term query)
        String modifierFilter = sq.getModifierFilter();
        if (modifierFilter != null && !modifierFilter.isBlank()) {
            builder.add(new TermQuery(new Term(F_MODIFIERS, modifierFilter.toLowerCase())), BooleanClause.Occur.MUST);
            hasFilters = true;
        }

        // If no constraints at all, match everything
        if (!hasFilters) {
            return new MatchAllDocsQuery();
        }

        return builder.build();
    }

    private Sort buildSort(SearchQuery sq) {
        if (sq.getSortBy() == null || sq.getSortBy() == SearchQuery.SortField.RELEVANCE) {
            return null; // default relevance sort
        }
        boolean reverse = !sq.isSortAscending();
        return switch (sq.getSortBy()) {
            case NAME          -> new Sort(new SortField(F_NAME_EXACT, SortField.Type.STRING, reverse));
            case QUALIFIED_NAME -> new Sort(new SortField(F_QN_EXACT, SortField.Type.STRING, reverse));
            case FILE_PATH     -> new Sort(new SortField(F_FILE_PATH, SortField.Type.STRING, reverse));
            case LINE          -> new Sort(new SortField(F_LINE_START, SortField.Type.LONG, reverse));
            case ELEMENT_TYPE  -> new Sort(new SortField(F_ELEMENT_TYPE, SortField.Type.STRING, reverse));
            case LANGUAGE      -> new Sort(new SortField(F_LANGUAGE, SortField.Type.STRING, reverse));
            default            -> null;
        };
    }

    // -------------------------------------------------------------------------
    // Document conversion
    // -------------------------------------------------------------------------

    private Document toDocument(CodeElement el) {
        Document doc = new Document();

        // id: stored, used as upsert key (StringField for term queries but NOT indexed for search)
        doc.add(new StringField(F_ID, el.getId(), Field.Store.YES));

        addStringField(doc, F_REPO_ID, el.getRepoId());
        if (el.getElementType() != null) {
            String et = el.getElementType().name();
            doc.add(new StringField(F_ELEMENT_TYPE, et, Field.Store.YES));
            doc.add(new SortedDocValuesField(F_ELEMENT_TYPE, new BytesRef(et)));
        }
        if (el.getLanguage() != null) {
            String lang = el.getLanguage().name();
            doc.add(new StringField(F_LANGUAGE, lang, Field.Store.YES));
            doc.add(new SortedDocValuesField(F_LANGUAGE, new BytesRef(lang)));
        }

        // name: analyzed TextField + non-analyzed StringField for exact + SortedDocValues for sorting
        if (el.getName() != null) {
            doc.add(new TextField(F_NAME, el.getName(), Field.Store.YES));
            doc.add(new StringField(F_NAME_EXACT, el.getName().toLowerCase(), Field.Store.NO));
            doc.add(new SortedDocValuesField(F_NAME_EXACT, new BytesRef(el.getName().toLowerCase())));
        }

        // qualified_name: analyzed TextField + non-analyzed for prefix/sort
        if (el.getQualifiedName() != null) {
            doc.add(new TextField(F_QUALIFIED_NAME, el.getQualifiedName(), Field.Store.YES));
            doc.add(new StringField(F_QN_EXACT, el.getQualifiedName().toLowerCase(), Field.Store.NO));
            doc.add(new SortedDocValuesField(F_QN_EXACT, new BytesRef(el.getQualifiedName().toLowerCase())));
        }

        if (el.getFilePath() != null) {
            String fpLower = el.getFilePath().toLowerCase();
            doc.add(new StringField(F_FILE_PATH, fpLower, Field.Store.YES));
            doc.add(new SortedDocValuesField(F_FILE_PATH, new BytesRef(fpLower)));
        }

        doc.add(new IntPoint(F_LINE_START, el.getLineStart()));
        doc.add(new StoredField(F_LINE_START, el.getLineStart()));
        doc.add(new NumericDocValuesField(F_LINE_START, el.getLineStart()));
        doc.add(new IntPoint(F_LINE_END, el.getLineEnd()));
        doc.add(new StoredField(F_LINE_END, el.getLineEnd()));

        // Also store col positions
        doc.add(new StoredField("col_start", el.getColStart()));
        doc.add(new StoredField("col_end", el.getColEnd()));

        addTextField(doc, F_SNIPPET, el.getSnippet());
        addTextField(doc, F_DOC_COMMENT, el.getDocComment());

        if (el.getSignature() != null) {
            doc.add(new TextField(F_SIGNATURE, el.getSignature(), Field.Store.YES));
        }

        addStringField(doc, F_VISIBILITY, el.getVisibility() != null ? el.getVisibility().toLowerCase() : null);
        addStringField(doc, F_PARENT_ID, el.getParentId());
        addStringField(doc, F_RETURN_TYPE, el.getReturnType());

        // modifiers: stored as space-separated string, analyzed for contains-type queries
        if (el.getModifiers() != null && !el.getModifiers().isEmpty()) {
            String mods = String.join(" ", el.getModifiers()).toLowerCase();
            doc.add(new TextField(F_MODIFIERS, mods, Field.Store.YES));
        }

        // parameter types: stored for reconstruction
        if (el.getParameterTypes() != null && !el.getParameterTypes().isEmpty()) {
            doc.add(new StoredField("parameter_types", String.join(",", el.getParameterTypes())));
        }

        return doc;
    }

    static CodeElement fromDocument(Document doc) {
        if (doc == null) return null;
        CodeElement el = new CodeElement();
        el.setId(doc.get(F_ID));
        el.setRepoId(doc.get(F_REPO_ID));

        String et = doc.get(F_ELEMENT_TYPE);
        if (et != null) {
            try { el.setElementType(ElementType.valueOf(et)); } catch (Exception ignored) {}
        }
        String lang = doc.get(F_LANGUAGE);
        if (lang != null) {
            try { el.setLanguage(Language.valueOf(lang)); } catch (Exception ignored) {}
        }

        el.setName(doc.get(F_NAME));
        el.setQualifiedName(doc.get(F_QUALIFIED_NAME));
        el.setSignature(doc.get(F_SIGNATURE));

        // file path stored lowercased - restore as-is (parser should have stored original)
        el.setFilePath(doc.get(F_FILE_PATH));

        IndexableField lineStart = doc.getField(F_LINE_START);
        if (lineStart != null) el.setLineStart(lineStart.numericValue().intValue());
        IndexableField lineEnd = doc.getField(F_LINE_END);
        if (lineEnd != null) el.setLineEnd(lineEnd.numericValue().intValue());
        IndexableField colStart = doc.getField("col_start");
        if (colStart != null) el.setColStart(colStart.numericValue().intValue());
        IndexableField colEnd = doc.getField("col_end");
        if (colEnd != null) el.setColEnd(colEnd.numericValue().intValue());

        el.setSnippet(doc.get(F_SNIPPET));
        el.setDocComment(doc.get(F_DOC_COMMENT));
        el.setReturnType(doc.get(F_RETURN_TYPE));
        el.setVisibility(doc.get(F_VISIBILITY));
        el.setParentId(doc.get(F_PARENT_ID));

        String mods = doc.get(F_MODIFIERS);
        if (mods != null && !mods.isBlank()) {
            el.setModifiers(new ArrayList<>(Arrays.asList(mods.split(" "))));
        }

        String paramTypes = doc.get("parameter_types");
        if (paramTypes != null && !paramTypes.isBlank()) {
            el.setParameterTypes(new ArrayList<>(Arrays.asList(paramTypes.split(","))));
        }

        return el;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void addStringField(Document doc, String name, String value) {
        if (value != null) {
            doc.add(new StringField(name, value, Field.Store.YES));
        }
    }

    private void addTextField(Document doc, String name, String value) {
        if (value != null && !value.isBlank()) {
            doc.add(new TextField(name, value, Field.Store.YES));
        }
    }

    public void commit() throws IOException {
        writer.commit();
        searcherManager.maybeRefresh();
        writesSinceCommit.set(0);
    }

    @Override
    public void close() {
        try {
            writer.commit();
            searcherManager.close();
        } catch (IOException e) {
            log.warn("Error committing Lucene writer on close: {}", e.getMessage());
        }
        try {
            writer.close();
        } catch (IOException e) {
            log.warn("Error closing Lucene writer: {}", e.getMessage());
        }
        try {
            directory.close();
        } catch (IOException e) {
            log.warn("Error closing Lucene directory: {}", e.getMessage());
        }
        analyzer.close();
    }
}
