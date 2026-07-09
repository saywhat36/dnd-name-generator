package com.dndnamegen.namegen.identity;

import java.util.Objects;

/**
 * The current request's identity for favorites/reports. Both endpoints require an authenticated
 * request -- there is no anonymous fallback (see docs/DECISIONS.md, identity resolution slice
 * revision) -- so {@code ownerId} is always present. {@code sessionId} is carried alongside it
 * even though neither {@code FavoriteService} nor, as of slice 6, {@code NameReportService}
 * reads it anymore -- {@code SessionIdFilter} still mints/reads the cookie on every request
 * regardless of authentication state (see its own Javadoc for why it stays registered), so
 * dropping the field here would be a no-op change with no simplification to show for it.
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
