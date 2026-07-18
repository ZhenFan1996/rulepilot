package com.rulepilot.identity.adapter.in.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class IdentitySecurityConfiguration {

    @Bean
    SecurityFilterChain identitySecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; "
                                        + "form-action 'self'; script-src 'self' 'wasm-unsafe-eval'; "
                                        + "worker-src 'self' blob:; style-src 'self'; "
                                        + "img-src 'self' data:; font-src 'self'; media-src 'self' blob:; "
                                        + "connect-src 'self'"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicyHeader(permissions -> permissions.policy(
                                "camera=(), microphone=(), geolocation=(), payment=(), usb=()")))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/info",
                                "/actuator/prometheus",
                                "/api/auth/csrf",
                                "/api/auth/login",
                                "/error")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/games", "/api/v1/games/*/editions", "/api/v1/games/*/expansions")
                        .hasRole("EDITOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/editions/*/documents")
                        .hasRole("EDITOR")
                        .requestMatchers("/actuator/metrics", "/actuator/metrics/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/mcp", "/mcp/**"))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> response.sendError(HttpStatus.UNAUTHORIZED.value()))
                        .accessDeniedHandler((request, response, exception) -> response.sendError(HttpStatus.FORBIDDEN.value())))
                .formLogin(form -> form
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> response.setStatus(HttpServletResponse.SC_NO_CONTENT))
                        .failureHandler((request, response, exception) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .httpBasic(Customizer.withDefaults())
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("SESSION")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(HttpServletResponse.SC_NO_CONTENT)));

        return http.build();
    }

    @Bean
    PasswordEncoder identityPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService localIdentityUsers(
            PasswordEncoder passwordEncoder,
            @Value("${rulepilot.identity.user.username}") String userName,
            @Value("${rulepilot.identity.user.password}") String userPassword,
            @Value("${rulepilot.identity.admin.username}") String adminName,
            @Value("${rulepilot.identity.admin.password}") String adminPassword) {
        return new InMemoryUserDetailsManager(
                User.withUsername(userName)
                        .password(passwordEncoder.encode(userPassword))
                        .roles("USER")
                        .build(),
                User.withUsername(adminName)
                        .password(passwordEncoder.encode(adminPassword))
                        .roles("USER", "EDITOR", "ADMIN")
                        .build());
    }
}
