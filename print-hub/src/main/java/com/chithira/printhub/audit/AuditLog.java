package com.chithira.printhub.audit;

import jakarta.persistence.*;

import java.time.Instant;

/** One audit row. Never updated or deleted. */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant at;

    @Column(name = "actor_type", nullable = false)
    private String actorType;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_name", nullable = false)
    private String actorName;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "entity_id")
    private String entityId;

    private String summary;

    @Column(name = "before_json")
    private String beforeJson;

    @Column(name = "after_json")
    private String afterJson;

    private String ip;

    protected AuditLog() {}

    AuditLog(Actor actor, String action, String entityType, String entityId, String summary,
             String beforeJson, String afterJson, String ip) {
        this.at = Instant.now();
        this.actorType = actor.type();
        this.actorId = actor.id();
        this.actorName = truncate(actor.name(), 120);
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.summary = truncate(summary, 500);
        this.beforeJson = truncate(beforeJson, 20000);
        this.afterJson = truncate(afterJson, 20000);
        this.ip = ip;
    }

    private static String truncate(String text, int max) { return text == null || text.length() <= max ? text : text.substring(0, max); }

    public Long getId() { return id; }
    public Instant getAt() { return at; }
    public String getActorType() { return actorType; }
    public Long getActorId() { return actorId; }
    public String getActorName() { return actorName; }
    public String getAction() { return action; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    public String getSummary() { return summary; }
    public String getBeforeJson() { return beforeJson; }
    public String getAfterJson() { return afterJson; }
    public String getIp() { return ip; }
}
