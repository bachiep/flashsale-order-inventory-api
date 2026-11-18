package com.portfolio.flashsale.auth.config;

import com.portfolio.flashsale.auth.security.JwtAuthenticationFilter;
import com.portfolio.flashsale.auth.security.SecurityProblemHandler;
import com.portfolio.flashsale.common.observability.CorrelationIdFilter;
import com.portfolio.flashsale.common.ratelimit.FlashSaleRateLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final CorrelationIdFilter correlationIdFilter;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final FlashSaleRateLimitFilter flashSaleRateLimitFilter;
    private final SecurityProblemHandler securityProblemHandler;

    public SecurityConfig(
            CorrelationIdFilter correlationIdFilter,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            FlashSaleRateLimitFilter flashSaleRateLimitFilter,
            SecurityProblemHandler securityProblemHandler
    ) {
        this.correlationIdFilter = correlationIdFilter;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.flashSaleRateLimitFilter = flashSaleRateLimitFilter;
        this.securityProblemHandler = securityProblemHandler;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public FilterRegistrationBean<FlashSaleRateLimitFilter> flashSaleRateLimitFilterRegistration(
            FlashSaleRateLimitFilter filter
    ) {
        FilterRegistrationBean<FlashSaleRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(securityProblemHandler)
                        .accessDeniedHandler(securityProblemHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/assets/**",
                                "/favicon.ico",
                                "/api/v1/health",
                                "/actuator/health",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/h2-console/**",
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/metrics/summary").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/products/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/vouchers/active").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(correlationIdFilter, SecurityContextHolderFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(flashSaleRateLimitFilter, JwtAuthenticationFilter.class)
                .build();
    }
}
