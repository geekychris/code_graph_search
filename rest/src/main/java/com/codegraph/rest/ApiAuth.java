package com.codegraph.rest;

import com.codegraph.core.config.AppConfig;
import com.codegraph.core.security.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Turns an {@code Authorization: Bearer} header into a {@link Principal}.
 *
 * <p>Tokens are compared by SHA-256 digest, so the config never needs the token itself
 * (use {@code tokenEnv} to read one from the environment instead).
 */
public class ApiAuth {

    private static final Logger log = LoggerFactory.getLogger(ApiAuth.class);

    private final boolean enabled;
    private final boolean anonymousAllowed;
    private final Map<String, Principal> byDigest = new LinkedHashMap<>();

    public ApiAuth(AppConfig.AuthConfig config) {
        this.enabled = config.isEnabled();
        this.anonymousAllowed = !"deny".equalsIgnoreCase(config.getAnonymous());
        for (AppConfig.TokenConfig t : config.getTokens()) {
            String digest = t.getSha256();
            if ((digest == null || digest.isBlank()) && t.getTokenEnv() != null) {
                String raw = System.getenv(t.getTokenEnv());
                if (raw != null && !raw.isBlank()) digest = sha256(raw.trim());
            }
            if (digest == null || digest.isBlank()) {
                log.warn("Token '{}' has no sha256 and its tokenEnv is unset; ignoring it", t.getName());
                continue;
            }
            boolean all = "all".equalsIgnoreCase(t.getScope()) || "private".equalsIgnoreCase(t.getScope());
            byDigest.put(digest.toLowerCase(), new Principal(t.getName(), all, t.isAdmin()));
        }
        if (enabled) {
            log.info("API auth enabled: {} token(s), anonymous {}", byDigest.size(),
                    anonymousAllowed ? "sees public repos" : "refused");
        }
    }

    public boolean isEnabled() { return enabled; }

    /**
     * The caller for a request: {@link Principal#LOCAL} when auth is off, the token's
     * principal for a known token, {@link Principal#ANONYMOUS} with no token when that is
     * allowed, otherwise empty (the request is refused).
     */
    public Optional<Principal> authenticate(String authorizationHeader) {
        if (!enabled) return Optional.of(Principal.LOCAL);
        String token = bearer(authorizationHeader);
        if (token == null) {
            return anonymousAllowed ? Optional.of(Principal.ANONYMOUS) : Optional.empty();
        }
        return Optional.ofNullable(byDigest.get(sha256(token)));
    }

    private static String bearer(String header) {
        if (header == null) return null;
        String h = header.trim();
        if (h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String t = h.substring(7).trim();
            return t.isEmpty() ? null : t;
        }
        return null;
    }

    public static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
