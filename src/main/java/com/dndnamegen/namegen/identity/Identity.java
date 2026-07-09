package com.dndnamegen.namegen.identity;

import java.util.Objects;

/**
 * The current request's identity. {@code ownerId} is non-null for an authenticated request and
 * null for an anonymous one -- reintroduced in slice 7 specifically so the now-public browse
 * routes (see {@code WebSecurityConfig}) can still resolve an {@code Identity} to read
 * favorited/reported name ids for an anonymous visitor, rather than 500ing or redirecting them
 * to {@code /login} just to view names (see docs/DECISIONS.md, slice 7). Favorites/reports
 * themselves stay authenticated-only: route-level security (the filter chain) and
 * {@code @PreAuthorize} both gate those endpoints ahead of {@code Identity} resolution, so
 * {@code FavoriteService}/{@code NameReportService} can keep assuming a non-null {@code ownerId}
 * without re-checking it. {@code sessionId} is always present -- {@code SessionIdFilter} mints
 * it regardless of authentication state (see its own Javadoc).
 */
public final class Identity {

    private final Long ownerId;
    private final String sessionId;

    private Identity(Long ownerId, String sessionId) {
        this.ownerId = ownerId;
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
    }

    public static Identity of(Long ownerId, String sessionId) {
        return new Identity(ownerId, sessionId);
    }

    public static Identity anonymous(String sessionId) {
        return new Identity(null, sessionId);
    }

    public boolean isAuthenticated() {
        return ownerId != null;
    }

    public Long ownerId() {
        return ownerId;
    }

    public String sessionId() {
        return sessionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Identity other)) {
            return false;
        }
        return Objects.equals(ownerId, other.ownerId) && Objects.equals(sessionId, other.sessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ownerId, sessionId);
    }
}
