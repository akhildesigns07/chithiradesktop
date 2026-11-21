package com.chithira.printhub.dashboard;

import com.chithira.printhub.dashboard.DashboardData.Day;
import com.chithira.printhub.dashboard.DashboardData.Group;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

/**
 * A chart as JSON for {@code static/js/dashboard.js}: its form, category labels and series.
 * Colour follows the measure everywhere: <b>printed</b> sheets are categorical slot 1 (blue),
 * <b>waste</b> slot 2 (orange), so a reader learns the colours once for every dashboard.
 */
public final class ChartSpec {

    /** Categorical colour slots (see hub.css: --series-1 / --series-2). */
    static final int PRINTED = 1, WASTE = 2;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM");
    /** Longest ranking shown as bars; the rest stay in the table. */
    static final int TOP = 10;

    private ChartSpec() {}

    /** Printed + waste sheets per day, stacked columns. */
    public static String dailyPrintedAndWaste(List<Day> days) {
        return write(chart("columns", dayLabels(days), true, "sheets",
                series("Printed", PRINTED, days, Day::printed), series("Waste", WASTE, days, Day::waste)));
    }

    /** Sheets sent to the Fiery per day (single series). */
    public static String dailySheetsSent(List<Day> days) {
        return write(chart("columns", dayLabels(days), false, "sheets", series("Sheets sent", PRINTED, days, Day::sheetsSent)));
    }

    /** Waste as a share of all counted sheets, per day (single line; days without counts are gaps). */
    public static String dailyWastePercent(List<Day> days) {
        List<Object> values = new ArrayList<>();
        for (Day day : days) values.add(day.printed() + day.waste() == 0 ? null : round(day.wastePercent()));
        return write(chart("line", dayLabels(days), false, "%", seriesValues("Waste %", WASTE, values)));
    }

    /** Printed + waste per group (customer, paper …), top {@link #TOP}, horizontal stacked bars. */
    public static String groupsPrintedAndWaste(List<Group> groups) {
        List<Group> top = groups.stream().filter(group -> group.printed() + group.waste() > 0).limit(TOP).toList();
        return write(chart("hbar", top.stream().map(Group::label).toList(), true, "sheets",
                series("Printed", PRINTED, top, Group::printed), series("Waste", WASTE, top, Group::waste)));
    }

    /** One measure per group, ranked by that measure (largest first), top {@link #TOP}, horizontal bars. */
    public static String groupsSingle(List<Group> groups, String name, int slot, ToLongFunction<Group> value) {
        List<Group> top = groups.stream().filter(group -> value.applyAsLong(group) > 0)
                .sorted(Comparator.comparingLong(value).reversed()).limit(TOP).toList();
        return write(chart("hbar", top.stream().map(Group::label).toList(), false, "sheets",
                series(name, slot, top, value)));
    }

    /** Waste % per group (only groups with counted sheets), worst first, top {@link #TOP}. */
    public static String groupsWastePercent(List<Group> groups) {
        List<Group> top = groups.stream().filter(group -> group.printed() + group.waste() > 0)
                .sorted((a, b) -> Double.compare(b.wastePercent(), a.wastePercent())).limit(TOP).toList();
        return write(chart("hbar", top.stream().map(Group::label).toList(), false, "%",
                seriesDouble("Waste %", WASTE, top, Group::wastePercent)));
    }

    /** Jobs per status, horizontal bars (one series). */
    public static String jobsByStatus(Map<String, Long> byStatus) {
        List<Object> values = new ArrayList<>(byStatus.values());
        return write(chart("hbar", new ArrayList<>(byStatus.keySet()), false, "jobs", seriesValues("Jobs", PRINTED, values)));
    }

    // ------------------------------------------------------------------ building

    private static Map<String, Object> chart(String type, List<String> labels, boolean stacked, String unit,
                                             Map<?, ?>... series) {
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("type", type);
        chart.put("labels", labels);
        chart.put("stacked", stacked);
        chart.put("unit", unit);
        chart.put("series", List.of(series));
        return chart;
    }

    private static <T> Map<String, Object> series(String name, int slot, List<T> items, ToLongFunction<T> value) {
        return seriesValues(name, slot, items.stream().map(item -> (Object) value.applyAsLong(item)).toList());
    }

    private static <T> Map<String, Object> seriesDouble(String name, int slot, List<T> items, ToDoubleFunction<T> value) {
        return seriesValues(name, slot, items.stream().map(item -> (Object) round(value.applyAsDouble(item))).toList());
    }

    private static Map<String, Object> seriesValues(String name, int slot, List<Object> values) {
        Map<String, Object> series = new LinkedHashMap<>();
        series.put("name", name);
        series.put("slot", slot);
        series.put("values", values);
        return series;
    }

    private static List<String> dayLabels(List<Day> days) { return days.stream().map(day -> DAY.format(day.date())).toList(); }

    private static double round(double value) { return Math.round(value * 10) / 10.0; }

    private static String write(Object chart) {
        try {
            return JSON.writeValueAsString(chart);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
