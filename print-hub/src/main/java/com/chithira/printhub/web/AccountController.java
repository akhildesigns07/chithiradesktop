package com.chithira.printhub.web;

import com.chithira.printhub.auth.HubUser;
import com.chithira.printhub.auth.PasswordChangeInterceptor;
import com.chithira.printhub.auth.UserService;
import com.chithira.printhub.common.BusinessException;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Every user can change their own password. */
@Controller
public class AccountController {

    private final UserService users;

    public AccountController(UserService users) { this.users = users; }

    @GetMapping("/account/password")
    public String form(HttpSession session, Model model) {
        model.addAttribute("forced", session.getAttribute(PasswordChangeInterceptor.FLAG) != null);
        return "account/password";
    }

    @PostMapping("/account/password")
    public String change(@AuthenticationPrincipal HubUser me, @RequestParam String current, @RequestParam String next,
                         @RequestParam String confirm, HttpSession session, Model model, RedirectAttributes redirect) {
        try {
            if (!next.equals(confirm)) throw new BusinessException("The two new passwords don't match");
            users.changeOwnPassword(me.getId(), current, next);
            session.removeAttribute(PasswordChangeInterceptor.FLAG);
            redirect.addFlashAttribute("ok", "Password changed");
            return "redirect:/";
        } catch (BusinessException exception) {
            model.addAttribute("forced", session.getAttribute(PasswordChangeInterceptor.FLAG) != null);
            model.addAttribute("error", exception.getMessage());
            return "account/password";
        }
    }
}
