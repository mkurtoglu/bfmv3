package com.bisoft.bfm.watcher.security;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.ui.LoginView;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Console users come from {@code bfm.users}. ADMIN may operate the cluster, VIEWER may only look. The REST API
 * under {@code /api} uses HTTP Basic with the same users; the console uses a login form.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService users(BfmProperties props, PasswordEncoder encoder) {
        List<UserDetails> list = new ArrayList<>();
        for (BfmProperties.User u : props.users()) {
            if (u.name() == null || u.passwordHash() == null) {
                throw new IllegalStateException("bfm.users entries need name and password-hash");
            }
            list.add(User.withUsername(u.name()).password(u.passwordHash()).roles(u.role().name()).build());
        }
        if (list.isEmpty()) {
            byte[] b = new byte[12];
            new SecureRandom().nextBytes(b);
            String pw = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            LOG.warn("No bfm.users configured. Generated a one-time admin login for this run: user 'admin',"
                    + " password '{}'. Configure users with hashes from 'java -jar bfm-watcher.jar hash-password'.",
                    pw);
            list.add(User.withUsername("admin").password(encoder.encode(pw)).roles("ADMIN").build());
        }
        return new InMemoryUserDetailsManager(list);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain api(HttpSecurity http) throws Exception {
        // The API answers with a status and a JSON body, written here rather than through sendError: an error
        // dispatch would go to /error, which belongs to the console chain and redirects to the login page, so a
        // script calling the API without credentials would get a 302 and an HTML page instead of 401.
        AuthenticationEntryPoint unauthenticated = (request, response, ex) -> {
            response.setHeader("WWW-Authenticate", "Basic realm=\"BFM\", charset=\"UTF-8\"");
            writeError(response, HttpStatus.UNAUTHORIZED, "authentication required");
        };
        AccessDeniedHandler forbidden = (request, response, ex) ->
                writeError(response, HttpStatus.FORBIDDEN, "this user may only read the cluster");
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(a -> a
                        // A database host enrolling has no user: its one-time token is the credential.
                        .requestMatchers(HttpMethod.POST, "/api/v1/enroll").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("ADMIN", "VIEWER")
                        .anyRequest().hasRole("ADMIN"))
                .httpBasic(b -> b.authenticationEntryPoint(unauthenticated))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated).accessDeniedHandler(forbidden))
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    private static void writeError(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    @Bean
    @Order(2)
    public SecurityFilterChain console(HttpSecurity http) throws Exception {
        // The bundled fonts are needed by the login page too; they are static files under an open licence.
        http.authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.GET, "/fonts/**").permitAll());
        http.with(VaadinSecurityConfigurer.vaadin(), v -> v.loginView(LoginView.class));
        return http.build();
    }
}
