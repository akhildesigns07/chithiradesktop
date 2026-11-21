package com.chithira.printhub.auth;

/**
 * What a user does in the shop. Page access per role is set by Admin; Admin itself always has full access.
 */
public enum Role {
    ADMIN("Admin"),
    FRONT_STAFF("Front staff"),
    PRINT_ROOM("Printing room"),
    CUSTOMER("Customer");

    private final String label;
    Role(String label) { this.label = label; }
    public String label() { return label; }
    public String authority() { return "ROLE_" + name(); }
}
