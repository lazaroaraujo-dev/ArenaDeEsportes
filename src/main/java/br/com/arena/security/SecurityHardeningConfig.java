package br.com.arena.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registro do filtro de rate limiting antes da cadeia do Spring Security. */
@Configuration
public class SecurityHardeningConfig {

    @Bean
    RateLimitFilter rateLimitFilter(ClientRateLimiter rateLimiter, ClientIdentityResolver resolver) {
        return new RateLimitFilter(rateLimiter, resolver);
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
