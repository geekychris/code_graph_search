package com.codegraph.parser;

import com.codegraph.core.model.Language;

import java.nio.file.Path;

public interface LanguageParser {
    Language getLanguage();
    boolean canParse(Path file);
    ParseResult parse(Path file, String repoId, Path repoRoot);
}
