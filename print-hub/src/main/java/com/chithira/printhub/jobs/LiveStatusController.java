package com.chithira.printhub.jobs;

import com.chithira.printhub.auth.AccessService;
import com.chithira.printhub.common.PageKey;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /jobs/live?ids=12,13}: polled every few seconds by the job list, job page and print room
 * (static/js/live-status.js) so they refresh as soon as a status changes. Logged-in staff only, with view
 * access to print jobs or the print room.
 */
@RestController
public class LiveStatusController {

    /** A page never asks about more jobs than it shows (50 per page). */
    private static final int MAX_IDS = 200;

    private final PrintJobService jobs;
    private final AccessService access;

    public LiveStatusController(PrintJobService jobs, AccessService access) {
        this.jobs = jobs;
        this.access = access;
    }

    @GetMapping("/jobs/live")
    public PrintJobService.LiveStatus live(@RequestParam(defaultValue = "") String ids) {
        if (!access.can(PageKey.PRINT_JOBS, false) && !access.can(PageKey.PRINT_ROOM, false)) {
            throw new AccessDeniedException("No access to print jobs");
        }
        List<Long> jobIds = new ArrayList<>();
        for (String part : ids.split(",")) {
            if (jobIds.size() >= MAX_IDS) break;
            try {
                if (!part.isBlank()) jobIds.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // skip anything that is not an id
            }
        }
        return jobs.liveStatus(jobIds);
    }
}
