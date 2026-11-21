package com.chithira.printhub.jobs;

import com.chithira.printhub.auth.RequiresPage;
import com.chithira.printhub.common.BusinessException;
import com.chithira.printhub.common.PageKey;
import com.chithira.printhub.fiery.FieryProperties;
import com.chithira.printhub.fiery.PollerHealth;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Print room: jobs waiting for their total printed and waste counts (oldest first, overdue ones flagged),
 * jobs still on the Fiery, and today's counted jobs for corrections. Entering counts needs PRINT_ROOM edit.
 */
@Controller
@RequestMapping("/print-room")
@RequiresPage(PageKey.PRINT_ROOM)
public class PrintRoomController {

    private static final int RECENT_HOURS = 24;

    private final PrintJobService jobs;
    private final PollerHealth pollerHealth;
    private final FieryProperties fieryProperties;

    public PrintRoomController(PrintJobService jobs, PollerHealth pollerHealth, FieryProperties fieryProperties) {
        this.jobs = jobs;
        this.pollerHealth = pollerHealth;
        this.fieryProperties = fieryProperties;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("waiting", jobs.waitingForCounts());
        model.addAttribute("printing", jobs.beingPrinted());
        model.addAttribute("counted", jobs.countedRecently(RECENT_HOURS));
        model.addAttribute("wasteReasons", jobs.wasteReasons());
        model.addAttribute("jobs", jobs);
        model.addAttribute("fieryConfigured", fieryProperties.isConfigured());
        model.addAttribute("poller", pollerHealth);
        return "jobs/print-room";
    }

    @PostMapping("/{id}/counts")
    public String counts(@PathVariable long id, @ModelAttribute JobCounts counts, RedirectAttributes redirect) {
        try {
            JobController.requireNumbers(counts);
            PrintJob job = jobs.enterCounts(id, counts);
            redirect.addFlashAttribute("ok", job.getFileName() + ": printed " + job.getPrintedCount() + ", waste " + job.getWasteCount());
        } catch (BusinessException exception) {
            redirect.addFlashAttribute("error", jobs.get(id).getFileName() + ": " + exception.getMessage());
        }
        return "redirect:/print-room";
    }
}
