package com.chithira.printhub.dashboard;

import com.chithira.printhub.dashboard.DashboardData.Day;
import com.chithira.printhub.dashboard.DashboardData.Group;
import com.chithira.printhub.dashboard.DashboardData.Totals;
import com.chithira.printhub.dashboard.DashboardData.WorstJob;
import com.chithira.printhub.dashboard.DashboardData.Workload;
import com.chithira.printhub.jobs.PrintJobStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Works out the dashboard figures for a filter (see the package description for what each figure counts). */
@Service
public class DashboardService {

    private static final int WORST_JOBS = 15;

    private final DashboardRepository repository;

    public DashboardService(DashboardRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public DashboardData load(DashboardFilter filter) {
        List<JobFact> facts = repository.jobFacts(filter);
        return new DashboardData(filter, totals(facts), days(facts, filter), byStatus(facts),
                group(facts, fact -> fact.customerId() == null ? null : String.valueOf(fact.customerId()),
                        fact -> fact.customerId() == null ? "No customer" : fact.customerCode() + " " + fact.customerName()),
                group(facts, JobFact::paperPcmid, fact -> fact.paperName() == null ? "Unknown paper" : fact.paperName()),
                group(facts, JobFact::deviceName, JobFact::deviceName),
                group(facts, JobFact::operator, fact -> fact.operator() == null ? "Unknown" : fact.operator()),
                group(facts, fact -> fact.sendMethod().name(), fact -> methodLabel(fact.sendMethod().name())),
                wasteByReason(facts), worstWaste(facts), workload());
    }

    static Totals totals(List<JobFact> facts) {
        long sent = 0, sheets = 0, counted = 0, printed = 0, waste = 0, withWaste = 0, failed = 0, cancelled = 0;
        for (JobFact fact : facts) {
            if (fact.sent()) { sent++; sheets += fact.sheetsRequested(); }
            if (fact.counted()) { counted++; printed += fact.printed(); waste += fact.waste(); }
            if (fact.waste() > 0) withWaste++;
            if (fact.status() == PrintJobStatus.FAILED) failed++;
            if (fact.status() == PrintJobStatus.CANCELLED) cancelled++;
        }
        return new Totals(facts.size(), sent, sheets, counted, printed, waste, withWaste, failed, cancelled);
    }

    /** One entry per calendar day of the range, including days without jobs. */
    static List<Day> days(List<JobFact> facts, DashboardFilter filter) {
        ZoneId zone = ZoneId.systemDefault();
        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        for (LocalDate day = filter.from(); !day.isAfter(filter.to()); day = day.plusDays(1)) byDay.put(day, new long[4]);
        for (JobFact fact : facts) {
            long[] sums = byDay.get(fact.sentAt().atZone(zone).toLocalDate());
            if (sums == null) continue;
            sums[0]++;
            if (fact.sent()) sums[1] += fact.sheetsRequested();
            sums[2] += fact.printed();
            sums[3] += fact.waste();
        }
        List<Day> days = new ArrayList<>();
        byDay.forEach((day, sums) -> days.add(new Day(day, sums[0], sums[1], sums[2], sums[3])));
        return days;
    }

    static Map<String, Long> byStatus(List<JobFact> facts) {
        Map<PrintJobStatus, Long> counts = facts.stream().collect(Collectors.groupingBy(JobFact::status, Collectors.counting()));
        Map<String, Long> ordered = new LinkedHashMap<>();
        for (PrintJobStatus status : PrintJobStatus.values()) {
            if (counts.containsKey(status)) ordered.put(status.label(), counts.get(status));
        }
        return ordered;
    }

    /** Groups by a key; sorted by printed, then sheets sent, then jobs (largest first). */
    static List<Group> group(List<JobFact> facts, Function<JobFact, String> key, Function<JobFact, String> label) {
        Map<String, long[]> sums = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (JobFact fact : facts) {
            String groupKey = String.valueOf(key.apply(fact));
            labels.putIfAbsent(groupKey, label.apply(fact));
            long[] values = sums.computeIfAbsent(groupKey, k -> new long[4]);
            values[0]++;
            if (fact.sent()) values[1] += fact.sheetsRequested();
            values[2] += fact.printed();
            values[3] += fact.waste();
        }
        return sums.entrySet().stream()
                .map(entry -> new Group("null".equals(entry.getKey()) ? null : entry.getKey(), labels.get(entry.getKey()),
                        entry.getValue()[0], entry.getValue()[1], entry.getValue()[2], entry.getValue()[3]))
                .sorted(Comparator.comparingLong(Group::printed).thenComparingLong(Group::sheetsSent)
                        .thenComparingLong(Group::jobs).reversed())
                .toList();
    }

    /** Waste sheets per reason, largest first. */
    static List<Group> wasteByReason(List<JobFact> facts) {
        Map<String, long[]> sums = new LinkedHashMap<>();
        for (JobFact fact : facts) {
            if (fact.waste() == 0) continue;
            String reason = fact.wasteReason() == null ? "No reason given" : fact.wasteReason();
            long[] values = sums.computeIfAbsent(reason, k -> new long[2]);
            values[0]++;
            values[1] += fact.waste();
        }
        return sums.entrySet().stream()
                .map(entry -> new Group(entry.getKey(), entry.getKey(), entry.getValue()[0], 0, 0, entry.getValue()[1]))
                .sorted(Comparator.comparingLong(Group::waste).reversed())
                .toList();
    }

    static List<WorstJob> worstWaste(List<JobFact> facts) {
        return facts.stream().filter(fact -> fact.waste() > 0)
                .sorted(Comparator.comparingInt(JobFact::waste).reversed())
                .limit(WORST_JOBS)
                .map(fact -> new WorstJob(fact.jobId(), fact.fileName(),
                        fact.customerName() == null ? "–" : fact.customerName(),
                        fact.paperName() == null ? "–" : fact.paperName(), fact.printed(), fact.waste(),
                        fact.wasteReason() == null ? "–" : fact.wasteReason()))
                .toList();
    }

    private Workload workload() {
        return new Workload(
                repository.countByStatus(List.of(PrintJobStatus.SENT, PrintJobStatus.HELD, PrintJobStatus.PRINTING)),
                repository.countByStatus(List.of(PrintJobStatus.PRINTED, PrintJobStatus.REMOVED, PrintJobStatus.AWAITING_COUNTS)));
    }

    private static String methodLabel(String method) {
        return switch (method) {
            case "API" -> "Fiery API";
            case "DRIVER_API" -> "Fiery driver → API";
            case "DRIVER" -> "Printer driver";
            default -> method;
        };
    }
}
