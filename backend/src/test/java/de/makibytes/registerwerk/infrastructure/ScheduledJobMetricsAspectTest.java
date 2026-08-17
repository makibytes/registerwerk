package de.makibytes.registerwerk.infrastructure;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ScheduledJobMetricsAspect")
class ScheduledJobMetricsAspectTest {

    private SimpleMeterRegistry registry;
    private ScheduledJobMetricsAspect aspect;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        aspect = new ScheduledJobMetricsAspect(registry);
    }

    /** Fixture methods carrying the exact annotations a real job class would. */
    static class Fixture {
        @SchedulerLock(name = "myLockedJob")
        void lockedJob() {}

        void unlockedJob() {}
    }

    private ProceedingJoinPoint joinPointFor(String methodName) throws NoSuchMethodException {
        Method method = Fixture.class.getDeclaredMethod(methodName);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getDeclaringType()).thenReturn(Fixture.class);
        when(signature.getName()).thenReturn(methodName);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        return joinPoint;
    }

    @Test
    @DisplayName("records outcome=success with the @SchedulerLock name for a successful run")
    void recordsSuccess_usingSchedulerLockName() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPointFor("lockedJob");
        when(joinPoint.proceed()).thenReturn(null);

        aspect.instrument(joinPoint);

        Timer timer = registry.find("registerwerk.scheduled.job")
                .tag("job", "myLockedJob").tag("outcome", "success").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("records outcome=failure and rethrows when the job throws")
    void recordsFailure_andRethrows() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPointFor("lockedJob");
        RuntimeException boom = new RuntimeException("boom");
        when(joinPoint.proceed()).thenThrow(boom);

        assertThatThrownBy(() -> aspect.instrument(joinPoint)).isSameAs(boom);

        Timer timer = registry.find("registerwerk.scheduled.job")
                .tag("job", "myLockedJob").tag("outcome", "failure").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("falls back to Class.method when no @SchedulerLock is present "
            + "(e.g. RpcNodeHealthService.checkAll, deliberately unlocked)")
    void fallsBackToClassDotMethod_whenNoSchedulerLock() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPointFor("unlockedJob");
        when(joinPoint.proceed()).thenReturn(null);

        aspect.instrument(joinPoint);

        Timer timer = registry.find("registerwerk.scheduled.job")
                .tag("job", "Fixture.unlockedJob").tag("outcome", "success").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }
}
