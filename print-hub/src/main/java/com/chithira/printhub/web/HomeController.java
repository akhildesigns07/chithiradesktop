package com.chithira.printhub.web;

import com.chithira.printhub.audit.AuditService;
import com.chithira.printhub.auth.AccessService;
import com.chithira.printhub.common.PageKey;
import com.chithira.printhub.customers.CustomerService;
import com.chithira.printhub.devices.DeviceService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/** Start page. Phase 4 turns this into the overall print / waste dashboard. */
@Controller
public class HomeController {

    private final CustomerService customers;
    private final DeviceService devices;
    private final AuditService audit;
    private final AccessService access;

    public HomeController(CustomerService customers, DeviceService devices, AuditService audit,
                          AccessService access) {
        this.customers = customers;
        this.devices = devices;
        this.audit = audit;
        this.access = access;
    }

    @GetMapping("/")
    public String home(Model model) {
        if (!access.can(PageKey.OVERALL_DASHBOARD, false)) {
            // land on the first page this user may open
            var firstAllowedPage = access.menu().values().stream().flatMap(List::stream).findFirst();
            if (firstAllowedPage.isPresent()) return "redirect:" + firstAllowedPage.get().path();
            return "no-access";
        }
        model.addAttribute("customerCount", customers.activeCount());
        model.addAttribute("deviceCount", devices.activeCount());
        if (access.can(PageKey.ADMIN_AUDIT, false)) model.addAttribute("recent", audit.latest());
        return "home";
    }

    @GetMapping("/login")
    public String login() { return "login"; }
}
