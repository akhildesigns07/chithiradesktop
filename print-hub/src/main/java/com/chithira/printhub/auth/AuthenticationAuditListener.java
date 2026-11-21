package com.chithira.printhub.auth;

import com.chithira.printhub.audit.Actor;
import com.chithira.printhub.audit.AuditService;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Every login and failed login goes to the audit log. */
@Component
public class AuthenticationAuditListener {

    private final AppUserRepository users;
    private final AuditService audit;

    public AuthenticationAuditListener(AppUserRepository users, AuditService audit) {
        this.users = users;
        this.audit = audit;
    }

    @EventListener
    @Transactional
    public void success(AuthenticationSuccessEvent event) {
        if (!(event.getAuthentication().getPrincipal() instanceof HubUser user)) return;
        users.findById(user.getId()).ifPresent(entity -> entity.setLastLoginAt(Instant.now()));
        audit.record(Actor.user(user.getId(), user.getUsername()), "LOGIN", "USER", user.getId(), "Logged in", null, null);
    }

    @EventListener
    @Transactional
    public void failure(AbstractAuthenticationFailureEvent event) {
        String attemptedUsername = String.valueOf(event.getAuthentication().getPrincipal());
        audit.record(Actor.user(null, attemptedUsername), "LOGIN_FAILED", "USER", null,
                "Failed login for '" + attemptedUsername + "': " + event.getException().getMessage(), null, null);
    }
}
