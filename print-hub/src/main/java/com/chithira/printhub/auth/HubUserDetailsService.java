package com.chithira.printhub.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads a staff or customer account for login (usernames are case-insensitive).
 */
@Service
public class HubUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public HubUserDetailsService(AppUserRepository users) { this.users = users; }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return users.findByUsernameIgnoreCase(username.trim())
                .map(HubUser::new)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }
}
