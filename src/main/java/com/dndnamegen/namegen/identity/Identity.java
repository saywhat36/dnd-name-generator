package com.dndnamegen.namegen.identity;

import java.util.Objects;

/**
 * The current request's identity for favorites/reports. Both endpoints require an authenticated
 * request -- there is no anonymous fallback (see docs/DECISIONS.md, identity resolution slice
 * revision) -- so {@code ownerId} is always present. {@code sessionId} is carried alongside it
 * purely because {@code name_reports.session_id} is {@code NOT NULL} with no {@code owner_id}
 * column of its own; {@code SessionIdFilter} still mints/reads it on every request regardless of
 * authentication state, so it is always available too.
 */
public final class Identity {

    private final Long ownerId;
    private final String sessionId;

    private Identity(Long ownerId, String sessionId) {
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId must not be null");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
    }

    public static Identity of(Long ownerId, String sessionId) {
        return new Identity(ownerId, sessionId);
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
