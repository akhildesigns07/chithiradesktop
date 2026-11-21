package com.chithira.printhub.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The staff login page (the start page "/" is the overall dashboard, see DashboardController). */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login() { return "login"; }
}
