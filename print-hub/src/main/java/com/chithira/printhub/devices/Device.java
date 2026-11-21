package com.chithira.printhub.devices;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** A registered desktop (Fiery hot folder PC). */
@Entity
@Table(name = "device")
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String location;

    @Column(name = "key_prefix", nullable = false, unique = true)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false)
    private String keyHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceStatus status = DeviceStatus.ACTIVE;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "key_rotated_at")
    private Instant keyRotatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "last_ip")
    private String lastIp;

    @Column(name = "app_version")
    private String appVersion;

    public Map<String, Object> toAudit() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", name);
        snapshot.put("location", location);
        snapshot.put("keyPrefix", keyPrefix);
        snapshot.put("status", status);
        return snapshot;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getKeyPrefix() { return keyPrefix; }
    public String getKeyHash() { return keyHash; }
    public void setKey(ApiKeys.NewKey newKey) { this.keyPrefix = newKey.prefix(); this.keyHash = newKey.hash(); }
    public DeviceStatus getStatus() { return status; }
    public void setStatus(DeviceStatus status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getKeyRotatedAt() { return keyRotatedAt; }
    public void setKeyRotatedAt(Instant time) { this.keyRotatedAt = time; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant time) { this.revokedAt = time; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant time) { this.lastSeenAt = time; }
    public String getLastIp() { return lastIp; }
    public void setLastIp(String lastIp) { this.lastIp = lastIp; }
    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }
}
