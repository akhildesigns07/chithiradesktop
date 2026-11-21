package com.chithira.printhub.web;

import com.chithira.printhub.auth.AccessAdminService;
import com.chithira.printhub.auth.AccessService;
import com.chithira.printhub.auth.RequiresPage;
import com.chithira.printhub.common.PageKey;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashSet;
import java.util.List;

/** Admin → Page access: which role may view / edit which page. */
@Controller
@RequestMapping("/admin/access")
@RequiresPage(PageKey.ADMIN_ACCESS)
public class AccessAdminController {

    private final AccessService access;
    private final AccessAdminService admin;

    public AccessAdminController(AccessService access, AccessAdminService admin) {
        this.access = access;
        this.admin = admin;
    }

    @GetMapping
    public String matrix(Model model) {
        model.addAttribute("pages", access.configurablePages());
        model.addAttribute("roles", access.configurableRoles());
        return "admin/access";
    }

    @PostMapping
    public String save(@RequestParam(name = "box", required = false) List<String> boxes, RedirectAttributes redirect) {
        int changeCount = admin.save(boxes == null ? new HashSet<>() : new HashSet<>(boxes));
        redirect.addFlashAttribute("ok", changeCount == 0 ? "No changes" : changeCount + " change(s) saved. They apply immediately.");
        return "redirect:/admin/access";
    }
}
