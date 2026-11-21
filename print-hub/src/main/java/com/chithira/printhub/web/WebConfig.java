package com.chithira.printhub.web;

import com.chithira.printhub.auth.PageAccessInterceptor;
import com.chithira.printhub.auth.PasswordChangeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the web interceptors: forced password change first, then page access.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PageAccessInterceptor pageAccess;
    private final PasswordChangeInterceptor passwordChange;

    public WebConfig(PageAccessInterceptor pageAccess, PasswordChangeInterceptor passwordChange) {
        this.pageAccess = pageAccess;
        this.passwordChange = passwordChange;
    }

    @Override
    public void addInterceptors(InterceptorRegistry r) {
        r.addInterceptor(passwordChange).excludePathPatterns("/api/**", "/webjars/**", "/css/**", "/js/**", "/login");
        r.addInterceptor(pageAccess).excludePathPatterns("/api/**", "/webjars/**", "/css/**", "/js/**");
    }
}
