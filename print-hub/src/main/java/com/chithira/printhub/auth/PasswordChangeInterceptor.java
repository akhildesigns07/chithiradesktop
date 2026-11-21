package com.chithira.printhub.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** After a temporary password, the user must choose their own before using any page. */
@Component
public class PasswordChangeInterceptor implements HandlerInterceptor {

    public static final String FLAG = "mustChangePassword";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(FLAG) == null) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/account/password") || path.startsWith("/logout") || path.startsWith("/error")) return true;
        response.sendRedirect(request.getContextPath() + "/account/password");
        return false;
    }
}
