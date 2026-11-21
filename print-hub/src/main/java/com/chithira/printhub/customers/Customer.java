package com.chithira.printhub.customers;

import com.chithira.printhub.common.TextUtils;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A customer of the shop. Created on the web or from a desktop ("quick add"); never deleted, only deactivated,
 * so past print jobs keep their customer.
 */
@Entity
@Table(name = "customer")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Shop reference, e.g. C-0042 (made from the id after the first save). */
    private String code;

    @Column(nullable = false)
    private String name;

    private String phone;
    private String email;
    private String company;
    private String gstin;
    private String address;
    private String notes;
    private boolean active = true;

    /** WEB or DESKTOP: where the customer was first created. */
    @Column(nullable = false)
    private String source;

    /** Login for the optional customer portal. */
    @Column(name = "portal_user_id")
    private Long portalUserId;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    void apply(CustomerForm form) {
        name = form.getName().trim();
        phone = form.cleanPhone();
        email = TextUtils.clean(form.getEmail());
        company = TextUtils.clean(form.getCompany());
        gstin = TextUtils.clean(form.getGstin());
        address = TextUtils.clean(form.getAddress());
        notes = TextUtils.clean(form.getNotes());
    }

    public Map<String, Object> toAudit() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("phone", phone);
        m.put("email", email);
        m.put("company", company);
        m.put("gstin", gstin);
        m.put("address", address);
        m.put("notes", notes);
        m.put("active", active);
        return m;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public String getCompany() { return company; }
    public String getGstin() { return gstin; }
    public String getAddress() { return address; }
    public String getNotes() { return notes; }
    public boolean isActive() { return active; }
    void setActive(boolean active) { this.active = active; }
    public String getSource() { return source; }
    void setSource(String source) { this.source = source; }
    public Long getPortalUserId() { return portalUserId; }
    public String getCreatedBy() { return createdBy; }
    void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
