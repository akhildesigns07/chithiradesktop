package com.chithira.printhub.auth;

import com.chithira.printhub.audit.AuditService;
import com.chithira.printhub.common.PageKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Saves the page-access matrix and records every change. */
@Service
public class AccessAdminService {

    private final RolePageAccessRepository repo;
    private final AccessService access;
    private final AuditService audit;

    public AccessAdminService(RolePageAccessRepository repo, AccessService access, AuditService audit) {
        this.repo = repo;
        this.access = access;
        this.audit = audit;
    }

    /** @param ticked "V:ROLE:PAGE" and "E:ROLE:PAGE" for every ticked box */
    @Transactional
    public int save(Set<String> ticked) {
        List<String> changes = new ArrayList<>();
        for (Role role : access.configurableRoles()) {
            for (PageKey page : access.configurablePages()) {
                boolean edit = ticked.contains("E:" + role + ":" + page);
                boolean view = edit || ticked.contains("V:" + role + ":" + page);
                RolePageAccess entry = repo.findByRoleAndPageKey(role, page).orElse(null);
                boolean wasView = entry != null && entry.isCanView(), wasEdit = entry != null && entry.isCanEdit();
                if (wasView == view && wasEdit == edit) continue;
                if (entry == null) entry = new RolePageAccess(role, page, view, edit);
                else entry.set(view, edit);
                repo.save(entry);
                changes.add(role.label() + " · " + page.label() + ": " + level(wasView, wasEdit) + " → " + level(view, edit));
            }
        }
        access.reload();
        if (!changes.isEmpty()) {
            audit.record("PAGE_ACCESS_CHANGED", "PAGE_ACCESS", null, changes.size() + " change(s)", null, changes);
        }
        return changes.size();
    }

    private static String level(boolean view, boolean edit) { return edit ? "edit" : view ? "view" : "none"; }
}
