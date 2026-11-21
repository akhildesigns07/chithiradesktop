package com.chithira.fierydesk.hub;

import com.chithira.fierydesk.core.CustomerRef;
import com.chithira.fierydesk.fiery.JsonParser;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** A customer as the hub API returns it (and as the desktop caches it). */
public record HubCustomer(long id, String code, String name, String phone, String email, String company,
                          boolean active, String updatedAt) {

    /** Reads one item of {@code GET /api/v1/customers}. */
    static HubCustomer fromJson(Map<?, ?> item) {
        return new HubCustomer(Long.parseLong(JsonParser.text(item, "id")),
                JsonParser.text(item, "code"), JsonParser.text(item, "name"), blankToNull(JsonParser.text(item, "phone")),
                blankToNull(JsonParser.text(item, "email")), blankToNull(JsonParser.text(item, "company")),
                !Boolean.FALSE.equals(item.get("active")), JsonParser.text(item, "updatedAt"));
    }

    Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("code", code);
        json.put("name", name);
        json.put("phone", phone);
        json.put("email", email);
        json.put("company", company);
        json.put("active", active);
        json.put("updatedAt", updatedAt);
        return json;
    }

    public CustomerRef toRef() { return new CustomerRef(id, code, name); }

    /** True if every word of the search text appears in the code, name, phone, company or email. */
    public boolean matches(String search) {
        if (search == null || search.isBlank()) return true;
        String haystack = (code + " " + name + " " + nullToEmpty(phone) + " " + nullToEmpty(company) + " "
                + nullToEmpty(email)).toLowerCase(Locale.ROOT);
        String digits = nullToEmpty(phone);
        for (String word : search.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            boolean phoneMatch = word.chars().allMatch(Character::isDigit) && digits.contains(word);
            if (!haystack.contains(word) && !phoneMatch) return false;
        }
        return true;
    }

    /** How the customer is shown in the picker: "C-0042 · Anu Weddings · 9847012345". */
    @Override
    public String toString() {
        StringBuilder label = new StringBuilder(code).append(" · ").append(name);
        if (company != null && !company.equalsIgnoreCase(name)) label.append(" (").append(company).append(')');
        if (phone != null) label.append(" · ").append(phone);
        return label.toString();
    }

    private static String blankToNull(String text) { return text == null || text.isBlank() ? null : text; }

    private static String nullToEmpty(String text) { return text == null ? "" : text; }
}
