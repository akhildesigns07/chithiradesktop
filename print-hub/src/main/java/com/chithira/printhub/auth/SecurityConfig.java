package com.chithira.printhub.auth;

import com.chithira.printhub.devices.ApiKeyAuthFilter;
import com.chithira.printhub.devices.DeviceService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Two separate doors:
 * <ul>
 *   <li>{@code /api/**}: desktops, header {@code X-Api-Key}, no session, no CSRF</li>
 *   <li>everything else: staff log in with their own username and password (form login, CSRF on)</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    @Order(1)
    SecurityFilterChain api(HttpSecurity http, DeviceService devices) throws Exception {
        http.securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthFilter(devices), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole("DEVICE"))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeJsonError(response, 401, "Missing or invalid API key"))
                        .accessDeniedHandler((request, response, exception) -> writeJsonError(response, 403, "This API key may not do that")));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain web(HttpSecurity http, AppUserRepository users) throws Exception {
        SavedRequestAwareAuthenticationSuccessHandler loginSuccessHandler = new SavedRequestAwareAuthenticationSuccessHandler() {
            @Override
            public void onAuthenticationSuccess(jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response,
                                                org.springframework.security.core.Authentication authentication)
                    throws java.io.IOException, jakarta.servlet.ServletException {
                users.findByUsernameIgnoreCase(authentication.getName())
                        .filter(AppUser::isMustChangePassword)
                        .ifPresent(user -> request.getSession().setAttribute(PasswordChangeInterceptor.FLAG, true));
                super.onAuthenticationSuccess(request, response, authentication);
            }
        };
        loginSuccessHandler.setDefaultTargetUrl("/");
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/webjars/**", "/css/**", "/js/**", "/favicon.ico", "/error").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login").successHandler(loginSuccessHandler).failureUrl("/login?error").permitAll())
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout"));
        return http.build();
    }

    private static void writeJsonError(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
