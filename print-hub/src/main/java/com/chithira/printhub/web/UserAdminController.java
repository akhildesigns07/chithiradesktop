package com.chithira.printhub.web;

import com.chithira.printhub.auth.HubUser;
import com.chithira.printhub.auth.RequiresPage;
import com.chithira.printhub.auth.Role;
import com.chithira.printhub.auth.UserService;
import com.chithira.printhub.common.BusinessException;
import com.chithira.printhub.common.PageKey;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Admin → Users: add staff accounts, change roles, deactivate, reset passwords.
 */
@Controller
@RequestMapping("/admin/users")
@RequiresPage(PageKey.ADMIN_USERS)
public class UserAdminController {

    private final UserService users;

    public UserAdminController(UserService users) { this.users = users; }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("users", users.all());
        model.addAttribute("roles", Role.values());
        return "admin/users";
    }

    @PostMapping
    public String create(@RequestParam String username, @RequestParam(defaultValue = "") String displayName,
                         @RequestParam(required = false) List<Role> roles, RedirectAttributes redirect) {
        try {
            String initialPassword = users.create(username, displayName, toSet(roles));
            redirect.addFlashAttribute("secretTitle", "User " + username.trim().toLowerCase() + " created");
            redirect.addFlashAttribute("secret", initialPassword);
            redirect.addFlashAttribute("secretNote", "Temporary password. Give it to the user; they must choose their own at first login.");
        } catch (BusinessException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/users";
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable long id, Model model) {
        model.addAttribute("u", users.get(id));
        model.addAttribute("roles", Role.values());
        return "admin/user-edit";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable long id, @AuthenticationPrincipal HubUser me,
                         @RequestParam(defaultValue = "") String displayName,
                         @RequestParam(required = false) List<Role> roles,
                         @RequestParam(defaultValue = "false") boolean active, RedirectAttributes redirect) {
        try {
            users.update(id, displayName, toSet(roles), active, me.getId());
            redirect.addFlashAttribute("ok", "User saved. Role changes apply at their next login.");
            return "redirect:/admin/users";
        } catch (BusinessException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
            return "redirect:/admin/users/" + id;
        }
    }

    @PostMapping("/{id}/reset-password")
    public String reset(@PathVariable long id, RedirectAttributes redirect) {
        String initialPassword = users.resetPassword(id);
        redirect.addFlashAttribute("secretTitle", "New temporary password for " + users.get(id).getUsername());
        redirect.addFlashAttribute("secret", initialPassword);
        redirect.addFlashAttribute("secretNote", "They must choose their own password at next login.");
        return "redirect:/admin/users";
    }

    private static Set<Role> toSet(List<Role> roles) {
        return roles == null || roles.isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(roles);
    }
}
