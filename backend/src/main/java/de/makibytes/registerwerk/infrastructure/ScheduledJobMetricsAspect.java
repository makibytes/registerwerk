package de.makibytes.registerwerk.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Instruments every {@code @Scheduled} method in the application with a
 * {@code registerwerk.scheduled.job} duration timer, tagged by {@code job} and
 * {@code outcome} (success/failure) — Phase 4: none of the 38 scheduled jobs previously exposed
 * duration or success/failure as a metric at all, only via log lines.
 *
 * <p>Applying this as a single cross-cutting aspect (rather than hand-instrumenting each of the
 * 38 job classes) means every current and future {@code @Scheduled} method is covered
 * automatically with zero per-job code.
 *
 * <p><strong>Interaction with ShedLock.</strong> {@code @SchedulerLock} does not create a Spring
 * AOP proxy around the target bean — it wraps the underlying {@code Runnable} at the
 * {@code TaskScheduler} level (see {@code net.javacrumbs.shedlock.spring.aop}), completely
 * independently of this aspect's {@code @annotation(Scheduled)} pointcut. On a replica that
 * fails to acquire the lock, ShedLock never invokes the target method at all, so this aspect's
 * advice never runs either — metrics only ever reflect ticks that actually executed, never
 * lock-skipped ticks on non-leader replicas. (Verified against the shedlock-spring 7.7.0 jar on
 * the classpath before relying on this ordering assumption.)
 *
 * <p>The job name is taken from {@code @SchedulerLock#name()} when present — every job in this
 * codebase already carries one, and it is already the operational vocabulary used in ShedLock's
 * own {@code shedlock} table and log lines, so reusing it keeps one name per job everywhere
 * instead of introducing a second naming scheme.
 */
@Aspect
@Component
class ScheduledJobMetricsAspect {

    private final MeterRegistry meterRegistry;

    ScheduledJobMetricsAspect(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    Object instrument(ProceedingJoinPoint joinPoint) throws Throwable {
        String jobName = jobName(joinPoint);
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            return joinPoint.proceed();
        } catch (Throwable t) {
            outcome = "failure";
            throw t;
        } finally {
            sample.stop(Timer.builder("registerwerk.scheduled.job")
                    .description("Duration and outcome of every @Scheduled job execution")
                    .tag("job", jobName)
                    .tag("outcome", outcome)
                    .register(meterRegistry));
        }
    }

    private String jobName(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
        if (lock != null && !lock.name().isBlank()) {
            return lock.name();
        }
        // RpcNodeHealthService.checkAll is deliberately unlocked (see its own Javadoc — it
        // refreshes per-instance in-memory state and must run on every replica) and is the one
        // @Scheduled method in the app with no @SchedulerLock; fall back to a class.method name.
        return signature.getDeclaringType().getSimpleName() + "." + signature.getName();
    }
}
