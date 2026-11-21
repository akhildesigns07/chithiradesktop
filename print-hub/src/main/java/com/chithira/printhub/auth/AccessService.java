package com.chithira.printhub.auth;

import com.chithira.printhub.common.PageKey;
import com.chithira.printhub.common.PrintHubProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Who may see / change which page. Admin always has full access; the other roles follow
 * the matrix Admin edits in Admin → Page access. Used by {@link PageAccessInterceptor}
 * and by the templates ({@code ${@access.canView('CUSTOMERS')}}).
 */
@Service("access")
public class AccessService {

    private final RolePageAccessRepository repo;
    private final PrintHubProperties props;
    private volatile Map<Role, Map<PageKey, RolePageAccess>> matrix;

    public AccessService(RolePageAccessRepository repo, PrintHubProperties props) {
        this.repo = repo;
        this.props = props;
    }

    public boolean canView(String page) { return can(PageKey.valueOf(page), false); }

    public boolean canEdit(String page) { return can(PageKey.valueOf(page), true); }

    public boolean can(PageKey page, boolean edit) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof HubUser user)) return false;
        return can(user.getRoles(), page, edit);
    }

    public boolean can(Set<Role> roles, PageKey page, boolean edit) {
        if (!enabled(page)) return false;
        if (roles.contains(Role.ADMIN)) return true;
        if (page.adminOnly()) return false;
        for (Role role : roles) {
            RolePageAccess entry = matrix().getOrDefault(role, Map.of()).get(page);
            if (entry != null && (edit ? entry.isCanEdit() : entry.isCanView())) return true;
        }
        return false;
    }

    /** False when the page's optional feature is switched off in application.yml. */
    public boolean enabled(PageKey page) {
        return page.feature() == null || props.features().isOn(page.feature());
    }

    /** Menu entries for the current user, grouped by section, in declaration order. */
    public Map<String, List<PageKey>> menu() {
        Map<String, List<PageKey>> sections = new LinkedHashMap<>();
        for (PageKey page : PageKey.values()) {
            if (page.built() && can(page, false)) sections.computeIfAbsent(page.section(), section -> new ArrayList<>()).add(page);
        }
        return sections;
    }

    /** Pages shown in the Admin matrix (not admin-only, feature on). */
    public List<PageKey> configurablePages() {
        return Arrays.stream(PageKey.values()).filter(page -> !page.adminOnly() && enabled(page)).toList();
    }

    public List<Role> configurableRoles() {
        return Arrays.stream(Role.values())
                .filter(role -> role != Role.ADMIN && (role != Role.CUSTOMER || props.features().customerPortal()))
                .toList();
    }

    public RolePageAccess entry(Role role, PageKey page) {
        return matrix().getOrDefault(role, Map.of()).get(page);
    }

    public void reload() { matrix = null; }

    private Map<Role, Map<PageKey, RolePageAccess>> matrix() {
        Map<Role, Map<PageKey, RolePageAccess>> loaded = matrix;
        if (loaded == null) {
            loaded = new EnumMap<>(Role.class);
            for (RolePageAccess entry : repo.findAll()) {
                loaded.computeIfAbsent(entry.getRole(), role -> new EnumMap<>(PageKey.class)).put(entry.getPageKey(), entry);
            }
            matrix = loaded;
        }
        return loaded;
    }
}
