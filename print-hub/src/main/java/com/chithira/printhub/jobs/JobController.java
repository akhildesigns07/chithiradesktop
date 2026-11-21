package com.chithira.printhub.jobs;

import com.chithira.printhub.audit.AuditLogRepository;
import com.chithira.printhub.auth.AccessService;
import com.chithira.printhub.auth.RequiresPage;
import com.chithira.printhub.common.BusinessException;
import com.chithira.printhub.common.PageKey;
import com.chithira.printhub.common.TextUtils;
import com.chithira.printhub.fiery.FieryProperties;
import com.chithira.printhub.fiery.JobStatusPoller;
import com.chithira.printhub.fiery.PollerHealth;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;
import java.time.ZoneId;

/**
 * Print jobs: list with filters, and the job page with its status timeline, counts and cancel.
 * Viewing needs PRINT_JOBS view; cancelling needs PRINT_JOBS edit; counts need PRINT_ROOM edit.
 */
@Controller
@RequestMapping("/jobs")
@RequiresPage(PageKey.PRINT_JOBS)
public class JobController {

    private final PrintJobService jobs;
    private final AuditLogRepository auditRepository;
    private final AccessService access;
    private final PollerHealth pollerHealth;
    private final FieryProperties fieryProperties;
    private final JobStatusPoller poller;

    public JobController(PrintJobService jobs, AuditLogRepository auditRepository, AccessService access,
                         PollerHealth pollerHealth, FieryProperties fieryProperties, JobStatusPoller poller) {
        this.jobs = jobs;
        this.auditRepository = auditRepository;
        this.access = access;
        this.pollerHealth = pollerHealth;
        this.fieryProperties = fieryProperties;
        this.poller = poller;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) PrintJobStatus status,
                       @RequestParam(required = false) Long customerId,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        ZoneId zone = ZoneId.systemDefault();
        JobSearch search = new JobSearch(TextUtils.clean(q), status, customerId,
                from == null ? null : from.atStartOfDay(zone).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant());
        Page<PrintJob> result = jobs.search(search, page);
        model.addAttribute("result", result);
        model.addAttribute("q", q);
        model.addAttribute("status", status);
        model.addAttribute("customerId", customerId);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("statuses", PrintJobStatus.values());
        model.addAttribute("liveStatuses", PrintJobService.encodeStatuses(result.getContent()));
        model.addAttribute("latestJobId", page == 0 ? jobs.latestJobId() : null);
        addFieryStatus(model);
        return "jobs/list";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable long id, Model model) {
        PrintJob job = jobs.get(id);
        model.addAttribute("job", job);
        model.addAttribute("history", jobs.history(id));
        model.addAttribute("audit", auditRepository.findByEntityTypeAndEntityIdOrderByIdDesc("PRINT_JOB", String.valueOf(id),
                PageRequest.of(0, 30)).getContent());
        model.addAttribute("canCount", job.getStatus().acceptsCounts() && access.can(PageKey.PRINT_ROOM, true));
        model.addAttribute("canCancel", !job.getStatus().isClosed() && access.can(PageKey.PRINT_JOBS, true));
        model.addAttribute("canCheck", job.getStatus().isOnFiery() && job.getFieryJobId() != null
                && fieryProperties.isConfigured() && access.can(PageKey.PRINT_JOBS, true));
        model.addAttribute("liveStatuses", PrintJobService.encodeStatuses(List.of(job)));
        model.addAttribute("overdue", jobs.isCountsOverdue(job));
        if (!model.containsAttribute("counts")) model.addAttribute("counts", JobCounts.of(job));
        model.addAttribute("wasteReasons", jobs.wasteReasons());
        return "jobs/view";
    }

    @PostMapping("/{id}/counts")
    @RequiresPage(value = PageKey.PRINT_ROOM, edit = true)
    public String counts(@PathVariable long id, @ModelAttribute JobCounts counts, RedirectAttributes redirect) {
        try {
            requireNumbers(counts);
            PrintJob job = jobs.enterCounts(id, counts);
            redirect.addFlashAttribute("ok", "Saved: printed " + job.getPrintedCount() + ", waste " + job.getWasteCount());
        } catch (BusinessException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
            redirect.addFlashAttribute("counts", counts);
        }
        return "redirect:/jobs/" + id;
    }

    /** "Check now": asks the Fiery about this job immediately instead of waiting for the next poll. */
    @PostMapping("/{id}/check")
    public String checkNow(@PathVariable long id, RedirectAttributes redirect) {
        JobStatusPoller.CheckResult result = poller.checkNow(id);
        redirect.addFlashAttribute(result.message().startsWith("Fiery not reachable") ? "error" : "ok", result.message());
        return "redirect:/jobs/" + id;
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable long id, @RequestParam(defaultValue = "") String reason, RedirectAttributes redirect) {
        try {
            jobs.cancel(id, reason);
            redirect.addFlashAttribute("ok", "Job cancelled");
        } catch (BusinessException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/jobs/" + id;
    }

    private void addFieryStatus(Model model) {
        model.addAttribute("fieryConfigured", fieryProperties.isConfigured());
        model.addAttribute("poller", pollerHealth);
    }

    /** Printed and waste must be whole numbers ≥ 0 (form fields arrive empty when left blank). */
    static void requireNumbers(JobCounts counts) {
        if (counts.getPrinted() == null) throw new BusinessException("Enter the total printed");
        if (counts.getWaste() == null) counts.setWaste(0);
        if (counts.getPrinted() < 0 || counts.getWaste() < 0) throw new BusinessException("Counts can't be negative");
    }
}
