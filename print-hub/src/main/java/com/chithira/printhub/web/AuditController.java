package com.chithira.printhub.web;

import com.chithira.printhub.audit.AuditService;
import com.chithira.printhub.auth.RequiresPage;
import com.chithira.printhub.common.PageKey;
import com.chithira.printhub.common.TextUtils;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.ZoneId;

/** Admin → Audit log (read only). */
@Controller
@RequestMapping("/admin/audit")
@RequiresPage(PageKey.ADMIN_AUDIT)
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) { this.audit = audit; }

    @GetMapping
    public String list(@RequestParam(required = false) String action, @RequestParam(required = false) String entityType,
                       @RequestParam(required = false) String actor, @RequestParam(required = false) String entityId,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        ZoneId zone = ZoneId.systemDefault();
        model.addAttribute("result", audit.search(TextUtils.clean(action), TextUtils.clean(entityType), TextUtils.clean(actor),
                TextUtils.clean(entityId), from == null ? null : from.atStartOfDay(zone).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant(), page));
        model.addAttribute("action", action);
        model.addAttribute("entityType", entityType);
        model.addAttribute("actor", actor);
        model.addAttribute("entityId", entityId);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        return "admin/audit";
    }
}
