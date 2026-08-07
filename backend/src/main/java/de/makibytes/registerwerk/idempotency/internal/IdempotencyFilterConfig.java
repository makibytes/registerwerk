package de.makibytes.registerwerk.idempotency.internal;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link IdempotencyFilter} to run AFTER Spring Security's filter chain (which sits at
 * a very early order, ~{@code Ordered.LOWEST_PRECEDENCE - 200}) so {@code SecurityContextHolder}
 * already has the authenticated principal by the time this filter runs.
 */
@Configuration
class IdempotencyFilterConfig {

    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(IdempotencyService service) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(new IdempotencyFilter(service));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        return registration;
    }
}
