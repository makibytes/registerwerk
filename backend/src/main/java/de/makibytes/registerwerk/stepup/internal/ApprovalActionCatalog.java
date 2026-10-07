package de.makibytes.registerwerk.stepup.internal;

/**
 * Which requests may be put into the approval queue: only an action that really is a four-eyes action, and only
 * for a route that really carries it. A request for an action the target endpoint does not gate would yield a
 * token that endpoint never accepts - harmless, but it would show an approver a statement that is not true.
 */
interface ApprovalActionCatalog {

    /**
     * @param action the dual-control reason (the {@code @RequiresStepUp} reason, or a {@code DualControlGate} reason)
     * @param method HTTP method of the real request
     * @param path   request path of the real request (no query)
     */
    boolean accepts(String action, String method, String path);
}
