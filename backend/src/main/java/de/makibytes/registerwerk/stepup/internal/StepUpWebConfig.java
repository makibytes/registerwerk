package de.makibytes.registerwerk.stepup.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers {@link DualControlApproverInterceptor} for the API (T3-23). */
@Configuration
class StepUpWebConfig implements WebMvcConfigurer {

    private final DualControlApproverInterceptor dualControlApproverInterceptor;

    StepUpWebConfig(DualControlApproverInterceptor dualControlApproverInterceptor) {
        this.dualControlApproverInterceptor = dualControlApproverInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(dualControlApproverInterceptor).addPathPatterns("/api/**");
    }
}
