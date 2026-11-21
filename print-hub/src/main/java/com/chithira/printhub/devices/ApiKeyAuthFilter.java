package com.chithira.printhub.devices;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads {@code X-Api-Key} on /api/** and signs the desktop in for this request only.
 * Not a Spring bean on purpose: it must run only in the API security chain.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";

    private final DeviceService devices;

    public ApiKeyAuthFilter(DeviceService devices) { this.devices = devices; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key != null && !key.isBlank()) {
            devices.authenticate(key.trim(), request.getRemoteAddr(), request.getHeader("X-App-Version")).ifPresent(device -> {
                var authentication = new UsernamePasswordAuthenticationToken(new DevicePrincipal(device.getId(), device.getName()), null,
                        List.of(new SimpleGrantedAuthority("ROLE_DEVICE")));
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            });
        }
        chain.doFilter(request, response);
    }
}
