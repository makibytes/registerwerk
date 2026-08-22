package de.makibytes.registerwerk.chain.api;

import java.util.Optional;

/**
 * Supplies the bearer token to present to a chaincache instance's read APIs
 * ({@code GET /api/capabilities}, the durable-event WebSocket) — chaincache's own default posture
 * is {@code chaincache.auth.enabled: true}, requiring a role among {@code USER}/{@code ADMIN}/
 * {@code OPERATOR} on {@code /api/**} and {@code /{chain}/api/**} (its {@code /{chain}/rpc} and
 * {@code /{chain}/ws} routes stay open unless the operator separately sets
 * {@code chaincache.auth.rpc-enabled}). Without this port, every probe against a
 * default-configured chaincache workload 401s and every node silently demotes to
 * {@code DIRECT_RPC} — which is exactly what went unnoticed while the demo stack ran with
 * {@code CHAINCACHE_AUTH_ENABLED=false}.
 *
 * <p>Takes {@code managementUrl} so a future fleet of workloads with different secrets can be
 * supported without changing this contract; today's single implementation
 * ({@code ChaincacheTokenFactory}) mints against one shared
 * {@code registerwerk.chaincache.jwt-secret} regardless of which workload is asked.
 */
public interface ChaincacheCredentials {

    /**
     * @return a bearer token for {@code managementUrl}, or empty when this deployment has no
     *         credential configured for it (e.g. {@code registerwerk.chaincache.jwt-secret} is
     *         unset — a legitimate configuration for a chaincache instance running with
     *         {@code chaincache.auth.enabled: false}, not an error).
     */
    Optional<String> bearerFor(String managementUrl);
}
