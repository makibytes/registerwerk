package de.makibytes.registerwerk.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${registerwerk.cors.allowed-origins:*}")
    private List<String> allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins(allowedOrigins.toArray(String[]::new))
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            // Browsers hide every response header from JS unless it is explicitly exposed.
            // WWW-Authenticate carries the OAuth2 claims challenge (error="insufficient_claims")
            // that the customer SPA must read to trigger an Entra step-up redirect.
            .exposedHeaders("WWW-Authenticate", "X-Total-Count")
            // The session token moved from the login response body into an httpOnly cookie
            // (SessionCookieService) — the browser only attaches it cross-origin (this path:
            // the operator portal's dev-mode direct-to-backend calls, see environment.ts) when
            // the request opts into credentials AND the server allows them explicitly.
            // allowCredentials(true) requires a concrete origin list, never "*" — Spring throws
            // at startup if allowedOrigins still resolved to the wildcard default, which is a
            // deliberate fail-fast: silently falling back to no-credentials would just turn
            // into "operator login works locally, breaks the moment someone deploys with the
            // property unset."
            .allowCredentials(true)
            .maxAge(3600);
    }

    @Bean
    @Scope(BeanDefinition.SCOPE_PROTOTYPE)
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
