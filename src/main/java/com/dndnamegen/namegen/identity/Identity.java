package com.dndnamegen.namegen.identity;

import java.util.Objects;

/**
 * The current request's identity for favorites/reports. {@code sessionId} is always present --
 * {@code SessionIdFilter} mints it for every request regardless of authentication state -- while
 * {@code ownerId} is only set once a request carries an authenticated principal. Favorites branch
 * on {@link #isAuthenticated()} to key on whichever id is available; reports deliberately always
 * use {@link #sessionId()} (see docs/DECISIONS.md, identity resolution slice), since
 * {@code name_reports.session_id} is {@code NOT NULL} and migrating it is out of scope here.
 */
public final class Identity {

    private final Long ownerId;
    private final String sessionId;

    private Identity(Long ownerId, String sessionId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.ownerId = ownerId;
    }

    public static Identity ofUser(Long ownerId, String sessionId) {
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        return new Identity(ownerId, sessionId);
    }

    public static Identity ofSession(String sessionId) {
        return new Identity(null, sessionId);
    }

    public boolean isAuthenticated() {
        return ownerId != null;
    }

    /** Only meaningful when {@link #isAuthenticated()} is true. */
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
