package com.chithira.printhub.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Enforces {@link RequiresPage} on web controllers. */
@Component
public class PageAccessInterceptor implements HandlerInterceptor {

    private final AccessService access;

    public PageAccessInterceptor(AccessService access) { this.access = access; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) return true;
        RequiresPage requiresPage = handlerMethod.getMethodAnnotation(RequiresPage.class);
        if (requiresPage == null) requiresPage = handlerMethod.getBeanType().getAnnotation(RequiresPage.class);
        if (requiresPage == null) return true;
        boolean edit = requiresPage.edit() || !"GET".equalsIgnoreCase(request.getMethod());
        if (!access.can(requiresPage.value(), edit)) {
            throw new AccessDeniedException("No " + (edit ? "edit" : "view") + " access to " + requiresPage.value().label());
        }
        return true;
    }
}
