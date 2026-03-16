package com.codegraph.core.model;

public enum ElementType {
    // Repository / filesystem
    REPO,
    DIRECTORY,
    FILE,

    // Language-level containers
    PACKAGE,        // Java package, Go package, Rust module, C++ namespace
    NAMESPACE,
    MODULE,

    // Type declarations
    CLASS,
    INTERFACE,
    ENUM,
    STRUCT,
    TRAIT,          // Rust trait
    PROTOCOL,       // Swift/ObjC protocol (future)
    TYPE_ALIAS,

    // Members
    CONSTRUCTOR,
    METHOD,
    FUNCTION,       // Top-level function (Go, Rust, C, C++)
    FIELD,          // Class member variable
    PROPERTY,       // TypeScript property
    ENUM_CONSTANT,
    STATIC_INITIALIZER,

    // Sub-method
    PARAMETER,
    LOCAL_VARIABLE,
    LAMBDA,

    // Imports / uses
    IMPORT,
    USE_DECLARATION, // Rust use

    // Documentation
    COMMENT_LINE,
    COMMENT_BLOCK,
    COMMENT_DOC,    // Javadoc, rustdoc, GoDoc
    ANNOTATION,
    ATTRIBUTE,      // Rust attribute #[...]
    DECORATOR,      // TypeScript/Python decorator

    // Config / docs
    MARKDOWN_DOCUMENT,
    MARKDOWN_HEADING,
    MARKDOWN_SECTION,
    CONFIG_FILE,
    CONFIG_KEY,

    // Cross-cutting
    CALL_SITE,      // A specific method call expression (for call graph)
    TYPE_REFERENCE, // A reference to a type
    UNKNOWN
}
