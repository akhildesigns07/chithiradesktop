package com.chithira.printhub.common;

import com.chithira.printhub.auth.Role;
import com.chithira.printhub.common.PrintHubProperties.Feature;

import java.util.Map;

/**
 * Every page (or group of pages) whose access Admin can control per role.
 * A new module adds its key here; Admin can then grant it in Admin → Page access.
 *
 * @param built    false = planned module, not available yet (hidden from the menu)
 * @param adminOnly always and only Admin (not shown in the matrix)
 */
public enum PageKey {
    OVERALL_DASHBOARD("Overall dashboard", "/", "Dashboards", null, true, false,
            Map.of(Role.FRONT_STAFF, 'V', Role.PRINT_ROOM, 'V')),
    CUSTOMERS("Customers", "/customers", "Customers", null, true, false,
            Map.of(Role.FRONT_STAFF, 'E', Role.PRINT_ROOM, 'V')),
    PRINT_JOBS("Print jobs", "/jobs", "Printing", null, false, false,
            Map.of(Role.FRONT_STAFF, 'E', Role.PRINT_ROOM, 'E')),
    /** Entering and correcting total printed + waste counts. Print-room staff edit; Admin always. */
    PRINT_ROOM("Print room – printed & waste counts", "/print-room", "Printing", null, false, false,
            Map.of(Role.PRINT_ROOM, 'E')),
    WASTE_DASHBOARD("Waste dashboard", "/dashboards/waste", "Dashboards", null, false, false,
            Map.of(Role.FRONT_STAFF, 'V', Role.PRINT_ROOM, 'V')),
    PAPERS("Paper catalog", "/papers", "Printing", null, false, false,
            Map.of(Role.FRONT_STAFF, 'V', Role.PRINT_ROOM, 'E')),

    // planned modules (feature flags in application.yml)
    REPORTS("Reports", "/reports", "Business", Feature.REPORTS, false, false, Map.of()),
    BILLING("Bills", "/billing", "Business", Feature.BILLING, false, false, Map.of()),
    PAYMENT_REMINDERS("Payment reminders", "/reminders", "Business", Feature.PAYMENT_REMINDERS, false, false, Map.of()),
    INVENTORY("Paper inventory", "/inventory", "Business", Feature.INVENTORY, false, false, Map.of()),

    ADMIN_USERS("Users", "/admin/users", "Admin", null, true, true, Map.of()),
    ADMIN_ACCESS("Page access", "/admin/access", "Admin", null, true, true, Map.of()),
    ADMIN_DEVICES("Desktops & API keys", "/admin/devices", "Admin", null, true, true, Map.of()),
    ADMIN_AUDIT("Audit log", "/admin/audit", "Admin", null, true, true, Map.of());

    private final String label, path, section;
    private final Feature feature;
    private final boolean built, adminOnly;
    private final Map<Role, Character> defaults;

    PageKey(String label, String path, String section, Feature feature, boolean built, boolean adminOnly,
            Map<Role, Character> defaults) {
        this.label = label;
        this.path = path;
        this.section = section;
        this.feature = feature;
        this.built = built;
        this.adminOnly = adminOnly;
        this.defaults = defaults;
    }

    public String label() { return label; }
    public String path() { return path; }
    public String section() { return section; }
    public Feature feature() { return feature; }
    public boolean built() { return built; }
    public boolean adminOnly() { return adminOnly; }

    /** Default access for a new install: 'V' view, 'E' view + edit, absent = none. */
    public boolean defaultView(Role r) { return defaults.containsKey(r); }
    public boolean defaultEdit(Role r) { return Character.valueOf('E').equals(defaults.get(r)); }
}
