package com.chithira.printhub.auth;

import com.chithira.printhub.audit.Actor;
import com.chithira.printhub.audit.AuditService;
import com.chithira.printhub.common.PageKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

/**
 * First start: creates the {@code admin} account (one-time password in the log) and the default
 * page access for every role. Later starts only add defaults for pages added since.
 */
@Component
public class InitialDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialDataLoader.class);

    /** File (in the working folder) holding the first admin password until it is changed. */
    public static final String FIRST_LOGIN_FILE = "FIRST-LOGIN.txt";

    private final AppUserRepository users;
    private final RolePageAccessRepository accessRepo;
    private final AccessService access;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public InitialDataLoader(AppUserRepository users, RolePageAccessRepository accessRepo, AccessService access,
                     PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.accessRepo = accessRepo;
        this.access = access;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (Role role : Role.values()) {
            if (role == Role.ADMIN) continue;
            for (PageKey page : PageKey.values()) {
                if (page.adminOnly() || accessRepo.findByRoleAndPageKey(role, page).isPresent()) continue;
                accessRepo.save(new RolePageAccess(role, page, page.defaultView(role), page.defaultEdit(role)));
            }
        }
        access.reload();

        if (users.count() == 0) {
            String initialPassword = UserService.temporaryPassword();
            AppUser admin = new AppUser();
            admin.setUsername("admin");
            admin.setDisplayName("Administrator");
            admin.setRoles(EnumSet.of(Role.ADMIN));
            admin.setPasswordHash(encoder.encode(initialPassword));
            admin.setMustChangePassword(true);
            users.save(admin);
            audit.record(Actor.SYSTEM, "USER_CREATED", "USER", admin.getId(), "First admin account created", null, admin.toAudit());
            log.warn("""

                    ============================================================
                      First start: log in as  admin  with password  {}
                      You will be asked to choose a new password.
                    ============================================================""", initialPassword);
            writeFirstLoginFile(initialPassword);
        }
    }

    /**
     * Also writes the one-time password to {@value #FIRST_LOGIN_FILE} in the working folder, so whoever installed the
     * Windows service can read it without opening logs. Deleted when the admin chooses their own password.
     */
    private static void writeFirstLoginFile(String initialPassword) {
        try {
            Files.writeString(Path.of(FIRST_LOGIN_FILE), """
                    Chithira Print Hub - first login
                    ================================
                    Open the Print Hub in a browser (http://localhost:8080 on this PC) and log in with:

                      Username:  admin
                      Password:  %s

                    You will be asked to choose your own password. This file is deleted automatically then.
                    """.formatted(initialPassword).replace("\n", System.lineSeparator()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not write {}: {}", FIRST_LOGIN_FILE, e.getMessage());
        }
    }
}
