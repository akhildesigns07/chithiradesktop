package com.chithira.printhub.auth;

import com.chithira.printhub.audit.AuditService;
import com.chithira.printhub.common.BusinessException;
import com.chithira.printhub.common.NotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Set;

/**
 * User accounts: create with a temporary password, edit roles, deactivate, reset and change passwords.
 * Keeps at least one active admin and audits every change (never the password itself).
 */
@Service
public class UserService {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UserService(AppUserRepository users, PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
    }

    public List<AppUser> all() { return users.findAllByOrderByUsername(); }

    public AppUser get(long id) { return users.findById(id).orElseThrow(() -> new NotFoundException("User")); }

    /** Creates a user with a temporary password (returned once) that must be changed at first login. */
    @Transactional
    public String create(String username, String displayName, Set<Role> roles) {
        String normalizedName = username.trim().toLowerCase();
        if (!normalizedName.matches("[a-z0-9._-]{3,60}")) throw new BusinessException("Username: 3–60 letters, digits, . _ -");
        if (users.findByUsernameIgnoreCase(normalizedName).isPresent()) throw new BusinessException("Username '" + normalizedName + "' is taken");
        if (roles.isEmpty()) throw new BusinessException("Choose at least one role");
        String initialPassword = temporaryPassword();
        AppUser user = new AppUser();
        user.setUsername(normalizedName);
        user.setDisplayName(displayName.isBlank() ? normalizedName : displayName.trim());
        user.setRoles(roles);
        user.setPasswordHash(encoder.encode(initialPassword));
        user.setMustChangePassword(true);
        users.save(user);
        audit.record("USER_CREATED", "USER", user.getId(), "User " + normalizedName + " created", null, user.toAudit());
        return initialPassword;
    }

    @Transactional
    public void update(long id, String displayName, Set<Role> roles, boolean active, long actingUserId) {
        AppUser user = get(id);
        var before = user.toAudit();
        if (roles.isEmpty()) throw new BusinessException("Choose at least one role");
        boolean losesAdmin = user.getRoles().contains(Role.ADMIN) && user.isActive() && (!roles.contains(Role.ADMIN) || !active);
        if (losesAdmin && users.countActiveAdmins() <= 1) throw new BusinessException("There must always be at least one active admin");
        if (id == actingUserId && !active) throw new BusinessException("You can't deactivate your own account");
        user.setDisplayName(displayName.isBlank() ? user.getUsername() : displayName.trim());
        user.setRoles(roles);
        user.setActive(active);
        audit.record("USER_UPDATED", "USER", id, "User " + user.getUsername() + " updated", before, user.toAudit());
    }

    /** New temporary password (returned once). */
    @Transactional
    public String resetPassword(long id) {
        AppUser user = get(id);
        String initialPassword = temporaryPassword();
        user.setPasswordHash(encoder.encode(initialPassword));
        user.setMustChangePassword(true);
        audit.record("USER_PASSWORD_RESET", "USER", id, "Password reset for " + user.getUsername(), null, null);
        return initialPassword;
    }

    @Transactional
    public void changeOwnPassword(long id, String current, String next) {
        AppUser user = get(id);
        if (!encoder.matches(current, user.getPasswordHash())) throw new BusinessException("Current password is wrong");
        if (next == null || next.length() < 8) throw new BusinessException("New password: at least 8 characters");
        if (encoder.matches(next, user.getPasswordHash())) throw new BusinessException("Choose a different password");
        user.setPasswordHash(encoder.encode(next));
        user.setMustChangePassword(false);
        audit.record("USER_PASSWORD_CHANGED", "USER", id, user.getUsername() + " changed their password", null, null);
        if (user.getRoles().contains(Role.ADMIN)) deleteFirstLoginFile();
    }

    /** The first-start password file is useless (and shouldn't linger) once an admin has their own password. */
    private static void deleteFirstLoginFile() {
        try {
            Files.deleteIfExists(Path.of(InitialDataLoader.FIRST_LOGIN_FILE));
        } catch (IOException ignored) {
            // not important: the password in it no longer works
        }
    }

    static String temporaryPassword() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 12; i++) builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return builder.toString();
    }
}
