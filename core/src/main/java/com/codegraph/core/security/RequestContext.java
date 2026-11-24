package com.codegraph.core.security;

import java.util.function.Supplier;

/**
 * Holds the {@link Principal} for the current request thread.
 *
 * <p>The REST server sets it in a before-handler and clears it afterwards; MCP over
 * stdio runs with {@link #setDefault(Principal) a default} of {@link Principal#LOCAL}.
 * When nothing is set the default applies, which is {@link Principal#ANONYMOUS} so a
 * forgotten code path fails closed.
 */
public final class RequestContext {

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();
    private static volatile Principal defaultPrincipal = Principal.ANONYMOUS;

    private RequestContext() {}

    public static Principal current() {
        Principal p = CURRENT.get();
        return p != null ? p : defaultPrincipal;
    }

    public static void set(Principal principal) { CURRENT.set(principal); }

    public static void clear() { CURRENT.remove(); }

    /** Principal used when no request has set one (stdio mode, auth disabled). */
    public static void setDefault(Principal principal) {
        defaultPrincipal = principal != null ? principal : Principal.ANONYMOUS;
    }

    /** Runs {@code body} as {@code principal}, restoring the previous principal afterwards. */
    public static <T> T callAs(Principal principal, Supplier<T> body) {
        Principal previous = CURRENT.get();
        CURRENT.set(principal);
        try {
            return body.get();
        } finally {
            if (previous != null) CURRENT.set(previous); else CURRENT.remove();
        }
    }
}
