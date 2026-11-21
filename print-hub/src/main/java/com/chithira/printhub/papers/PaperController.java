package com.chithira.printhub.papers;

import com.chithira.printhub.auth.RequiresPage;
import com.chithira.printhub.common.BusinessException;
import com.chithira.printhub.common.PageKey;
import com.chithira.printhub.fiery.FieryProperties;
import com.chithira.printhub.jobs.PrintJobRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Paper catalog page: every paper from the Fiery with counted usage; "Sync from Fiery" needs PAPERS edit. */
@Controller
@RequestMapping("/papers")
@RequiresPage(PageKey.PAPERS)
public class PaperController {

    /** Counted usage of one paper. */
    public record Usage(long jobs, long printed, long waste) {
        public double wastePercent() { return printed + waste == 0 ? 0 : 100.0 * waste / (printed + waste); }
    }

    private final PaperSyncService sync;
    private final PrintJobRepository jobRepository;
    private final FieryProperties fieryProperties;

    public PaperController(PaperSyncService sync, PrintJobRepository jobRepository, FieryProperties fieryProperties) {
        this.sync = sync;
        this.jobRepository = jobRepository;
        this.fieryProperties = fieryProperties;
    }

    @GetMapping
    public String list(Model model) {
        Map<String, Usage> usage = new HashMap<>();
        for (Object[] row : jobRepository.countedTotalsPerPaper()) {
            usage.put((String) row[0], new Usage(((Number) row[1]).longValue(), ((Number) row[2]).longValue(), ((Number) row[3]).longValue()));
        }
        List<Paper> papers = sync.all();
        model.addAttribute("papers", papers);
        model.addAttribute("usage", usage);
        model.addAttribute("noUsage", new Usage(0, 0, 0));
        model.addAttribute("fieryConfigured", fieryProperties.isConfigured());
        model.addAttribute("lastSyncAt", sync.getLastSyncAt());
        model.addAttribute("lastError", sync.getLastError());
        return "papers/list";
    }

    @PostMapping("/sync")
    public String syncNow(RedirectAttributes redirect) {
        try {
            redirect.addFlashAttribute("ok", sync.sync().summary());
        } catch (BusinessException | IOException exception) {
            redirect.addFlashAttribute("error", "Sync failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        return "redirect:/papers";
    }
}
