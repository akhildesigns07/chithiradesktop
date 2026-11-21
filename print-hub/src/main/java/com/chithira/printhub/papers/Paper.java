package com.chithira.printhub.papers;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One Paper Catalog entry from the Fiery. Sizes are in points (72 per inch). */
@Entity
@Table(name = "paper")
public class Paper {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fiery_pcmid", nullable = false, unique = true)
    private String fieryPcmid;

    @Column(nullable = false)
    private String name;

    @Column(name = "width_pt")
    private Double widthPt;

    @Column(name = "height_pt")
    private Double heightPt;

    @Column(name = "weight_gsm")
    private String weightGsm;

    @Column(name = "media_type")
    private String mediaType;

    private String coating;
    private String color;
    private String tray;
    private boolean active = true;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt;

    protected Paper() {}

    Paper(String fieryPcmid) {
        this.fieryPcmid = fieryPcmid;
        this.firstSeenAt = Instant.now();
    }

    /**
     * Copies the values read from the Fiery.
     *
     * @return true if anything changed
     */
    boolean update(String name, Double widthPt, Double heightPt, String weightGsm, String mediaType,
                   String coating, String color, String tray) {
        boolean changed = !active || !Objects.equals(this.name, name) || !Objects.equals(this.widthPt, widthPt)
                || !Objects.equals(this.heightPt, heightPt) || !Objects.equals(this.weightGsm, weightGsm)
                || !Objects.equals(this.mediaType, mediaType) || !Objects.equals(this.coating, coating)
                || !Objects.equals(this.color, color) || !Objects.equals(this.tray, tray);
        this.name = name;
        this.widthPt = widthPt;
        this.heightPt = heightPt;
        this.weightGsm = weightGsm;
        this.mediaType = mediaType;
        this.coating = coating;
        this.color = color;
        this.tray = tray;
        this.active = true;
        this.lastSyncedAt = Instant.now();
        return changed;
    }

    void deactivate() { this.active = false; }

    /** "13 × 19 in (330 × 483 mm)", or "–" when the Fiery gave no size. */
    public String getSizeLabel() {
        if (widthPt == null || heightPt == null || widthPt <= 0 || heightPt <= 0) return "–";
        return inches(widthPt) + " × " + inches(heightPt) + " in (" + Math.round(widthPt / 72 * 25.4) + " × "
                + Math.round(heightPt / 72 * 25.4) + " mm)";
    }

    Map<String, Object> toAudit() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("pcmid", fieryPcmid);
        snapshot.put("name", name);
        snapshot.put("size", getSizeLabel());
        snapshot.put("weight", weightGsm);
        snapshot.put("active", active);
        return snapshot;
    }

    private static String inches(double points) {
        double value = Math.round(points / 72 * 100) / 100.0;
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    public Long getId() { return id; }
    public String getFieryPcmid() { return fieryPcmid; }
    public String getName() { return name; }
    public Double getWidthPt() { return widthPt; }
    public Double getHeightPt() { return heightPt; }
    public String getWeightGsm() { return weightGsm; }
    public String getMediaType() { return mediaType; }
    public String getCoating() { return coating; }
    public String getColor() { return color; }
    public String getTray() { return tray; }
    public boolean isActive() { return active; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
}
