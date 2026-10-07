package de.makibytes.registerwerk.stepup.internal;

import de.makibytes.registerwerk.stepup.api.RequiresStepUp;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the set of approvable requests from the live handler mappings: every controller route annotated
 * {@code @RequiresStepUp(requireSecondApprover = true)} (method or class level) contributes its reason, method and
 * path pattern. Built on first use, after the handler mappings are registered.
 *
 * <p>Actions gated programmatically through {@code DualControlGate} cannot be found by annotation; they are listed
 * in {@link #GATE_REASONS} (a test scans the sources so the list cannot drift) and are accepted for any mapped
 * route.
 */
@Component
class RouteApprovalActionCatalog implements ApprovalActionCatalog {

    /** Reasons passed to {@code DualControlGate.require / requireIfNotBootstrap} (and the admin-user wrapper). */
    static final Set<String> GATE_REASONS = Set.of(
            "OPERATOR_USER_INVITE", "OPERATOR_USER_ROLES", "OPERATOR_USER_ENABLE", "OPERATOR_USER_DISABLE",
            "OPERATOR_USER_DELETE", "OPERATOR_USER_REINSTATE", "DORA_INCIDENT_DOWNGRADE", "DORA_INCIDENT_CLOSE",
            "CLIENT_CLASSIFICATION_DOWNGRADE");

    private record Route(Set<RequestMethod> methods, List<PathPattern> patterns, String reason, boolean dualControl) {
        boolean matches(String method, PathContainer path) {
            boolean methodOk = methods.isEmpty() || methods.stream().anyMatch(m -> m.name().equals(method));
            return methodOk && patterns.stream().anyMatch(p -> p.matches(path));
        }
    }

    /** Absent in a context without a web layer (non-web integration tests): then nothing is approvable. */
    private final ObjectProvider<RequestMappingHandlerMapping> mapping;
    private volatile List<Route> routes;

    RouteApprovalActionCatalog(@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mapping) {
        this.mapping = mapping;
    }

    @Override
    public boolean accepts(String action, String method, String path) {
        if (action == null || method == null || path == null) {
            return false;
        }
        PathContainer container = PathContainer.parsePath(path);
        boolean gateReason = GATE_REASONS.contains(action);
        for (Route route : routes()) {
            if (!route.matches(method, container)) {
                continue;
            }
            if (gateReason || (route.dualControl() && action.equals(route.reason()))) {
                return true;
            }
        }
        return false;
    }

    private List<Route> routes() {
        List<Route> built = routes;
        if (built == null) {
            synchronized (this) {
                built = routes;
                if (built == null) {
                    built = build();
                    routes = built;
                }
            }
        }
        return built;
    }

    private List<Route> build() {
        List<Route> out = new ArrayList<>();
        RequestMappingHandlerMapping handlerMapping = mapping.getIfAvailable();
        if (handlerMapping == null) {
            return List.of();
        }
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            HandlerMethod handler = e.getValue();
            if (info.getPathPatternsCondition() == null) {
                continue;
            }
            RequiresStepUp ann = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), RequiresStepUp.class);
            if (ann == null) {
                ann = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), RequiresStepUp.class);
            }
            out.add(new Route(info.getMethodsCondition().getMethods(),
                    List.copyOf(info.getPathPatternsCondition().getPatterns()),
                    ann == null ? null : ann.reason(), ann != null && ann.requireSecondApprover()));
        }
        return List.copyOf(out);
    }
}
