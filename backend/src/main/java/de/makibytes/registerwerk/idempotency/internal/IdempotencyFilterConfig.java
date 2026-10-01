package de.makibytes.registerwerk.idempotency.internal;

import de.makibytes.registerwerk.idempotency.api.RequiresIdempotencyKey;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import java.util.function.Predicate;

/**
 * Registers {@link IdempotencyFilter} to run AFTER Spring Security's filter chain (which sits at
 * a very early order, ~{@code Ordered.LOWEST_PRECEDENCE - 200}) so {@code SecurityContextHolder}
 * already has the authenticated principal by the time this filter runs.
 */
@Configuration
class IdempotencyFilterConfig {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilterConfig.class);

    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(
            IdempotencyService service,
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(
                new IdempotencyFilter(service, keyRequired(handlerMapping), annotated(handlerMapping,
                        de.makibytes.registerwerk.idempotency.api.NoIdempotencyReplay.class)));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        return registration;
    }

    /**
     * Resolves the MVC handler of the request (without dispatching it) and reports whether it, or its
     * controller class, carries {@link RequiresIdempotencyKey}. An unresolvable handler is "not
     * required" (404s etc.); a lookup failure is logged and treated the same.
     */
    static Predicate<HttpServletRequest> keyRequired(ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        return annotated(handlerMapping, RequiresIdempotencyKey.class);
    }

    /** Same handler resolution for any marker annotation on the handler method or its controller class. */
    static Predicate<HttpServletRequest> annotated(ObjectProvider<RequestMappingHandlerMapping> handlerMapping,
                                                   Class<? extends java.lang.annotation.Annotation> marker) {
        return request -> {
            RequestMappingHandlerMapping mapping = handlerMapping.getIfAvailable();
            if (mapping == null) {
                return false;
            }
            boolean alreadyParsed = ServletRequestPathUtils.hasParsedRequestPath(request);
            try {
                if (!alreadyParsed) {
                    ServletRequestPathUtils.parseAndCache(request);
                }
                HandlerExecutionChain chain = mapping.getHandler(request);
                if (chain != null && chain.getHandler() instanceof HandlerMethod handler) {
                    return AnnotatedElementUtils.hasAnnotation(handler.getMethod(), marker)
                            || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), marker);
                }
                return false;
            } catch (Exception e) {
                log.warn("Could not resolve the handler of {} {} for the idempotency requirement: {}",
                        request.getMethod(), request.getRequestURI(), e.toString());
                return false;
            } finally {
                if (!alreadyParsed) {
                    ServletRequestPathUtils.clearParsedRequestPath(request);
                }
            }
        };
    }
}
