package com.dndnamegen.namegen.user;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

/**
 * Extends Spring Security's {@code User} principal to carry the {@code users.id} row id
 * alongside username/password/authorities -- the stock {@code User} has no field for it, and the
 * identity-resolution slice needs a stable owner id to key favorites on, not just the username
 * {@code Authentication.getName()} exposes.
 */
public class AppUserDetails extends User {

    private final Long ownerId;

    public AppUserDetails(
            Long ownerId,
            String username,
            String password,
            boolean enabled,
            Collection<? extends GrantedAuthority> authorities) {
        super(username, password, enabled, true, true, true, authorities);
        this.ownerId = ownerId;
    }

    public Long getOwnerId() {
        return ownerId;
    }
}
