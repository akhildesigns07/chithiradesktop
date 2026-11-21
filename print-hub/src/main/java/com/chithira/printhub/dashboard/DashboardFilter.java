package com.chithira.printhub.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Filters shared by every dashboard (all optional except the dates).
 *
 * @param from / to   inclusive days, by the date the job was sent
 * @param paperPcmid  Paper Catalog id
 */
public record DashboardFilter(LocalDate from, LocalDate to, Long customerId, String paperPcmid, Long deviceId) {

    /** Longest range a dashboard shows, so a mistyped year can't load everything. */
    static final int MAX_DAYS = 400;
    static final int DEFAULT_DAYS = 30;

    /** Fills missing dates with the last 30 days and puts the range in order. */
    public static DashboardFilter of(LocalDate from, LocalDate to, Long customerId, String paperPcmid, Long deviceId) {
        LocalDate today = LocalDate.now();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_DAYS - 1) : from;
        if (start.isAfter(end)) { LocalDate swap = start; start = end; end = swap; }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) start = end.minusDays(MAX_DAYS - 1);
        String paper = paperPcmid == null || paperPcmid.isBlank() ? null : paperPcmid.trim();
        return new DashboardFilter(start, end, customerId, paper, deviceId);
    }

    Instant fromInstant() { return from.atStartOfDay(ZoneId.systemDefault()).toInstant(); }

    Instant toInstantExclusive() { return to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant(); }

    public long days() { return ChronoUnit.DAYS.between(from, to) + 1; }

    /** True if a customer, paper or desktop narrows the view. */
    public boolean isNarrowed() { return customerId != null || paperPcmid != null || deviceId != null; }
}
