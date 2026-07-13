package com.secai.config;

import java.util.UUID;

/**
 * Holds the authenticated user's organizationId for the current request thread.
 * Set by JwtAuthFilter after token validation. Cleared after request completes.
 * Every repository query MUST filter by this value.
 */
public class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_ORG = new ThreadLocal<>();

    public static void set(UUID orgId) {
        CURRENT_ORG.set(orgId);
    }

    public static UUID get() {
        UUID orgId = CURRENT_ORG.get();
        if (orgId == null) {
            throw new IllegalStateException("TenantContext not initialized — no JWT present");
        }
        return orgId;
    }

    public static void clear() {
        CURRENT_ORG.remove();
    }
}