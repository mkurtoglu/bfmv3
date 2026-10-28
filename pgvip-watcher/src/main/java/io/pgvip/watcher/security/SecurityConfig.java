package io.pgvip.watcher.security;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import io.pgvip.watcher.config.PgvipProperties;
import io.pgvip.watcher.ui.LoginView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Console users come from {@code pgvip.users}. ADMIN may operate the cluster, VIEWER may only look. The REST API
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
    public UserDetailsService users(PgvipProperties props, PasswordEncoder encoder) {
        List<UserDetails> list = new ArrayList<>();
        for (PgvipProperties.User u : props.users()) {
            if (u.name() == null || u.passwordHash() == null) {
                throw new IllegalStateException("pgvip.users entries need name and password-hash");
            }
            list.add(User.withUsername(u.name()).password(u.passwordHash()).roles(u.role().name()).build());
        }
        if (list.isEmpty()) {
            byte[] b = new byte[12];
            new SecureRandom().nextBytes(b);
            String pw = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            LOG.warn("No pgvip.users configured. Generated a one-time admin login for this run: user 'admin',"
                    + " password '{}'. Configure users with hashes from 'java -jar pgvip-watcher.jar hash-password'.",
                    pw);
            list.add(User.withUsername("admin").password(encoder.encode(pw)).roles("ADMIN").build());
        }
        return new InMemoryUserDetailsManager(list);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain api(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("ADMIN", "VIEWER")
                        .anyRequest().hasRole("ADMIN"))
                .httpBasic(Customizer.withDefaults())
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain console(HttpSecurity http) throws Exception {
        http.with(VaadinSecurityConfigurer.vaadin(), v -> v.loginView(LoginView.class));
        return http.build();
    }
}
