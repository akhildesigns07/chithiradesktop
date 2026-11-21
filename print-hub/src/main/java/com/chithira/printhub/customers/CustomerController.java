package com.chithira.printhub.customers;

import com.chithira.printhub.audit.AuditLogRepository;
import com.chithira.printhub.auth.RequiresPage;
import com.chithira.printhub.common.BusinessException;
import com.chithira.printhub.common.PageKey;
import com.chithira.printhub.jobs.PrintJobService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Customer pages: list and search, create, view with history, edit, deactivate / reactivate.
 * View needs CUSTOMERS view access; every change needs CUSTOMERS edit access.
 */
@Controller
@RequestMapping("/customers")
@RequiresPage(PageKey.CUSTOMERS)
public class CustomerController {

    private final CustomerService customers;
    private final AuditLogRepository auditRepository;
    private final PrintJobService jobs;

    public CustomerController(CustomerService customers, AuditLogRepository auditRepository, PrintJobService jobs) {
        this.customers = customers;
        this.auditRepository = auditRepository;
        this.jobs = jobs;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "false") boolean inactive,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("result", customers.search(q, inactive, page, 25));
        model.addAttribute("q", q);
        model.addAttribute("inactive", inactive);
        return "customers/list";
    }

    @GetMapping("/new")
    @RequiresPage(value = PageKey.CUSTOMERS, edit = true)
    public String newForm(Model model) {
        model.addAttribute("form", new CustomerForm());
        return "customers/form";
    }

    @PostMapping("/new")
    public String create(@Valid @ModelAttribute("form") CustomerForm form, BindingResult errors, Model model, RedirectAttributes redirect) {
        if (errors.hasErrors()) return "customers/form";
        try {
            Customer customer = customers.create(form, "WEB");
            redirect.addFlashAttribute("ok", "Customer " + customer.getCode() + " " + customer.getName() + " created");
            return "redirect:/customers/" + customer.getId();
        } catch (BusinessException exception) {
            model.addAttribute("error", exception.getMessage());
            return "customers/form";
        }
    }

    @GetMapping("/{id}")
    public String view(@PathVariable long id, Model model) {
        model.addAttribute("c", customers.get(id));
        model.addAttribute("totals", jobs.totalsForCustomer(id));
        model.addAttribute("jobCount", jobs.jobCountForCustomer(id));
        model.addAttribute("recentJobs", jobs.recentForCustomer(id));
        model.addAttribute("history", auditRepository.findByEntityTypeAndEntityIdOrderByIdDesc("CUSTOMER", String.valueOf(id),
                PageRequest.of(0, 20)).getContent());
        return "customers/view";
    }

    @GetMapping("/{id}/edit")
    @RequiresPage(value = PageKey.CUSTOMERS, edit = true)
    public String editForm(@PathVariable long id, Model model) {
        Customer customer = customers.get(id);
        model.addAttribute("c", customer);
        model.addAttribute("form", CustomerForm.of(customer));
        return "customers/form";
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable long id, @Valid @ModelAttribute("form") CustomerForm form, BindingResult errors,
                         Model model, RedirectAttributes redirect) {
        if (errors.hasErrors()) {
            model.addAttribute("c", customers.get(id));
            return "customers/form";
        }
        try {
            customers.update(id, form);
            redirect.addFlashAttribute("ok", "Saved");
            return "redirect:/customers/" + id;
        } catch (BusinessException exception) {
            model.addAttribute("c", customers.get(id));
            model.addAttribute("error", exception.getMessage());
            return "customers/form";
        }
    }

    @PostMapping("/{id}/active")
    public String setActive(@PathVariable long id, @RequestParam boolean active, RedirectAttributes redirect) {
        customers.setActive(id, active);
        redirect.addFlashAttribute("ok", active ? "Customer reactivated" : "Customer deactivated");
        return "redirect:/customers/" + id;
    }
}
