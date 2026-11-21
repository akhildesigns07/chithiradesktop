package com.chithira.printhub.auth;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.EnumSet;
import java.util.Set;

/** The logged-in staff member (or customer) as Spring Security sees them. */
public class HubUser extends User {

    private final long id;
    private final String displayName;
    private final Set<Role> roles;

    public HubUser(AppUser user) {
        super(user.getUsername(), user.getPasswordHash(), user.isActive(), true, true, true,
                user.getRoles().stream().map(role -> new SimpleGrantedAuthority(role.authority())).toList());
        this.id = user.getId();
        this.displayName = user.getDisplayName();
        this.roles = user.getRoles().isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(user.getRoles());
    }

    public long getId() { return id; }
    public String getDisplayName() { return displayName; }
    public Set<Role> getRoles() { return roles; }
    public boolean isAdmin() { return roles.contains(Role.ADMIN); }
}
