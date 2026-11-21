package com.chithira.printhub.dashboard;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Everything a dashboard page shows, worked out for one filter.
 *
 * @param byStatus jobs per status (label → count), in life-cycle order
 */
public record DashboardData(DashboardFilter filter, Totals totals, List<Day> days, Map<String, Long> byStatus,
                            List<Group> byCustomer, List<Group> byPaper, List<Group> byDevice, List<Group> byOperator,
                            List<Group> bySendMethod, List<Group> wasteByReason, List<WorstJob> worstWaste,
                            Workload workload) {

    /** Totals of the range. */
    public record Totals(long jobs, long jobsSent, long sheetsSent, long countedJobs, long printed, long waste,
                         long jobsWithWaste, long failed, long cancelled) {
        public double wastePercent() { return percent(waste, printed + waste); }
        /** Counted jobs as a share of jobs sent. */
        public double countedPercent() { return percent(countedJobs, jobsSent); }
    }

    /** One calendar day. */
    public record Day(LocalDate date, long jobs, long sheetsSent, long printed, long waste) {
        public double wastePercent() { return percent(waste, printed + waste); }
    }

    /**
     * One row of a grouping (customer, paper, desktop, reason …).
     *
     * @param key   id used in links (customer id, paper pcmid, device name …), may be null
     */
    public record Group(String key, String label, long jobs, long sheetsSent, long printed, long waste) {
        public double wastePercent() { return percent(waste, printed + waste); }
    }

    /** A counted job with waste, for the "most waste" list. */
    public record WorstJob(long jobId, String fileName, String customerName, String paperName, long printed,
                           long waste, String reason) {
        public double wastePercent() { return percent(waste, printed + waste); }
    }

    /** What is open right now (not limited to the date range). */
    public record Workload(long onFiery, long awaitingCounts) {}

    static double percent(long part, long whole) { return whole == 0 ? 0 : 100.0 * part / whole; }
}
