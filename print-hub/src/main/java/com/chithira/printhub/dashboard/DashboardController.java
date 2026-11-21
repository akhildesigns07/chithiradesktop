package com.chithira.printhub.dashboard;

import com.chithira.printhub.audit.AuditService;
import com.chithira.printhub.auth.AccessService;
import com.chithira.printhub.auth.RequiresPage;
import com.chithira.printhub.common.PageKey;
import com.chithira.printhub.customers.CustomerService;
import com.chithira.printhub.devices.DeviceService;
import com.chithira.printhub.papers.PaperSyncService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Dashboard pages. Every page takes the same filters ({@code from}, {@code to}, {@code customerId},
 * {@code paper}, {@code deviceId}); the start page ("/") is the overall dashboard for those who may see it.
 */
@Controller
public class DashboardController {

    /** A quick date range above the filters, e.g. "Last 7 days". */
    public record QuickRange(String label, LocalDate from, LocalDate to) {}

    private final DashboardService dashboards;
    private final AccessService access;
    private final PaperSyncService papers;
    private final DeviceService devices;
    private final CustomerService customers;
    private final AuditService audit;

    public DashboardController(DashboardService dashboards, AccessService access, PaperSyncService papers,
                               DeviceService devices, CustomerService customers, AuditService audit) {
        this.dashboards = dashboards;
        this.access = access;
        this.papers = papers;
        this.devices = devices;
        this.customers = customers;
        this.audit = audit;
    }

    /** Start page: the overall dashboard, or the first page this user may open. */
    @GetMapping("/")
    public String overall(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          @RequestParam(required = false) Long customerId, @RequestParam(required = false) String paper,
                          @RequestParam(required = false) Long deviceId, Model model) {
        if (!access.can(PageKey.OVERALL_DASHBOARD, false)) {
            return access.menu().values().stream().flatMap(List::stream).findFirst()
                    .map(page -> "redirect:" + page.path()).orElse("no-access");
        }
        DashboardData data = prepare(model, "/", from, to, customerId, paper, deviceId);
        charts(model, Map.of(
                "daily", ChartSpec.dailyPrintedAndWaste(data.days()),
                "wastePercent", ChartSpec.dailyWastePercent(data.days()),
                "customers", ChartSpec.groupsPrintedAndWaste(data.byCustomer()),
                "papers", ChartSpec.groupsPrintedAndWaste(data.byPaper())));
        if (access.can(PageKey.ADMIN_AUDIT, false)) model.addAttribute("recent", audit.latest());
        return "dashboards/overall";
    }

    @GetMapping("/dashboards/prints")
    @RequiresPage(PageKey.PRINT_DASHBOARD)
    public String prints(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(required = false) Long customerId, @RequestParam(required = false) String paper,
                         @RequestParam(required = false) Long deviceId, Model model) {
        DashboardData data = prepare(model, "/dashboards/prints", from, to, customerId, paper, deviceId);
        charts(model, Map.of(
                "sheetsSent", ChartSpec.dailySheetsSent(data.days()),
                "daily", ChartSpec.dailyPrintedAndWaste(data.days()),
                "status", ChartSpec.jobsByStatus(data.byStatus()),
                "devices", ChartSpec.groupsPrintedAndWaste(data.byDevice())));
        return "dashboards/prints";
    }

    @GetMapping("/dashboards/papers")
    @RequiresPage(PageKey.PAPER_DASHBOARD)
    public String papers(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(required = false) Long customerId, @RequestParam(required = false) String paper,
                         @RequestParam(required = false) Long deviceId, Model model) {
        DashboardData data = prepare(model, "/dashboards/papers", from, to, customerId, paper, deviceId);
        charts(model, Map.of(
                "papers", ChartSpec.groupsPrintedAndWaste(data.byPaper()),
                "wastePercent", ChartSpec.groupsWastePercent(data.byPaper()),
                "daily", ChartSpec.dailyPrintedAndWaste(data.days())));
        return "dashboards/papers";
    }

    @GetMapping("/dashboards/waste")
    @RequiresPage(PageKey.WASTE_DASHBOARD)
    public String waste(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(required = false) Long customerId, @RequestParam(required = false) String paper,
                        @RequestParam(required = false) Long deviceId, Model model) {
        DashboardData data = prepare(model, "/dashboards/waste", from, to, customerId, paper, deviceId);
        charts(model, Map.of(
                "wastePercent", ChartSpec.dailyWastePercent(data.days()),
                "reasons", ChartSpec.groupsSingle(data.wasteByReason(), "Waste", ChartSpec.WASTE, DashboardData.Group::waste),
                "papers", ChartSpec.groupsWastePercent(data.byPaper()),
                "operators", ChartSpec.groupsSingle(data.byOperator(), "Waste", ChartSpec.WASTE, DashboardData.Group::waste)));
        return "dashboards/waste";
    }

    @GetMapping("/dashboards/customers")
    @RequiresPage(PageKey.CUSTOMER_DASHBOARD)
    public String customers(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            @RequestParam(required = false) Long customerId, @RequestParam(required = false) String paper,
                            @RequestParam(required = false) Long deviceId, Model model) {
        DashboardData data = prepare(model, "/dashboards/customers", from, to, customerId, paper, deviceId);
        if (customerId != null) {
            charts(model, Map.of(
                    "daily", ChartSpec.dailyPrintedAndWaste(data.days()),
                    "papers", ChartSpec.groupsPrintedAndWaste(data.byPaper())));
        } else {
            charts(model, Map.of(
                    "customers", ChartSpec.groupsPrintedAndWaste(data.byCustomer()),
                    "wastePercent", ChartSpec.groupsWastePercent(data.byCustomer())));
        }
        return "dashboards/customers";
    }

    // ------------------------------------------------------------------ shared

    private DashboardData prepare(Model model, String path, LocalDate from, LocalDate to, Long customerId,
                                  String paper, Long deviceId) {
        DashboardFilter filter = DashboardFilter.of(from, to, customerId, paper, deviceId);
        DashboardData data = dashboards.load(filter);
        model.addAttribute("data", data);
        model.addAttribute("filter", filter);
        model.addAttribute("path", path);
        model.addAttribute("ranges", quickRanges());
        model.addAttribute("paperOptions", papers.all());
        model.addAttribute("deviceOptions", devices.all());
        ifPresent(customerId, id -> model.addAttribute("customer", customers.get(id)));
        return data;
    }

    private static void charts(Model model, Map<String, String> charts) { model.addAttribute("charts", charts); }

    private static void ifPresent(Long id, Consumer<Long> action) { if (id != null) action.accept(id); }

    static List<QuickRange> quickRanges() {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        return List.of(
                new QuickRange("Today", today, today),
                new QuickRange("Last 7 days", today.minusDays(6), today),
                new QuickRange("Last 30 days", today.minusDays(29), today),
                new QuickRange("Last 90 days", today.minusDays(89), today),
                new QuickRange("This month", monthStart, today),
                new QuickRange("Last month", monthStart.minusMonths(1), monthStart.minusDays(1)));
    }
}
