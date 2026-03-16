package com.codegraph.core.model;

public enum EdgeType {
    // Structural containment
    CONTAINS,           // directory→file, file→class, class→method, etc.

    // Type hierarchy
    EXTENDS,            // class extends class/interface
    IMPLEMENTS,         // class implements interface
    OVERRIDES,          // method overrides parent method
    MIXES_IN,           // trait/mixin inclusion

    // Call graph
    CALLS,              // method A calls method B
    INSTANTIATES,       // new SomeClass() - constructor call

    // References
    USES_TYPE,          // field/parameter/return type reference
    IMPORTS,            // file imports another file/package
    DEPENDS_ON,         // module/package level dependency

    // Documentation
    DOCUMENTS,          // comment/javadoc → the element it documents
    ANNOTATES,          // annotation → annotated element

    // Cross-language
    IMPLEMENTS_PROTO,   // Go/Java impl of protobuf definition
    CALLS_RPC,          // cross-language RPC call
    SHARES_TYPE,        // same type concept across languages (generated code)

    // Markdown
    SECTION_OF,         // heading is section of document
    DOCUMENTS_DIR,      // README documents the directory

    // Config
    CONFIGURES,         // config key configures a class/method
    REFERENCES_CLASS,   // config value references a class name

    // Positional
    PRECEDES,           // element A immediately precedes element B (sibling order)
    DEFINED_IN,         // element defined in file
}
