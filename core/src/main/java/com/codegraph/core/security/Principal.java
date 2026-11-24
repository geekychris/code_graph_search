package com.codegraph.core.security;

/**
 * The caller a request runs as.
 *
 * @param name         token name or "anonymous", for logs and audit
 * @param seesPrivate  true if the caller may see private repositories
 * @param admin        true if the caller may add, remove, reindex or sync repositories
 */
public record Principal(String name, boolean seesPrivate, boolean admin) {

    /** A trusted local process (MCP over stdio, auth disabled on a laptop). Sees everything. */
    public static final Principal LOCAL = new Principal("local", true, true);

    /** An unauthenticated caller. Sees public repositories only. */
    public static final Principal ANONYMOUS = new Principal("anonymous", false, false);
}
