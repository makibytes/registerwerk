package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.shared.ProductionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Decides when a genesis-hash mismatch may be resolved by re-pinning instead of quarantining the
 * node: only for a local development ledger that is recreated routinely (a disposable anvil in the
 * Compose demo) AND only while demo seeding is on AND never in production mode. A local dev chain is
 * one with a dev chain id (31337 / 1337) or whose configured RPC endpoint is loopback or the demo's
 * Compose-internal anvil host. Every other chain keeps the quarantine; its recovery path is the
 * operator's step-up + second-approver {@code POST /api/v1/admin/chains/{id}/nodes/genesis-pin/reset}.
 */
@Component
class LocalDevChainRepinPolicy {

    private static final Set<Long> DEV_CHAIN_IDS = Set.of(31337L, 1337L);
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]", "host.docker.internal");

    private final boolean seedDemoData;
    private final boolean production;
    private final String demoRpcHost;

    @Autowired
    LocalDevChainRepinPolicy(Environment environment,
                             @Value("${registerwerk.seed-demo-data:false}") boolean seedDemoData,
                             @Value("${registerwerk.lending.local-demo-backend-rpc-url:http://anvil:8545}") String demoRpcUrl) {
        this(seedDemoData, ProductionMode.resolve(environment), demoRpcUrl);
    }

    LocalDevChainRepinPolicy(boolean seedDemoData, boolean production, String demoRpcUrl) {
        this.seedDemoData = seedDemoData;
        this.production = production;
        this.demoRpcHost = hostOf(demoRpcUrl);
    }

    boolean allowsRepin(ChainConfig chain) {
        if (!seedDemoData || production || chain == null) {
            return false;
        }
        if (chain.getChainId() != null && DEV_CHAIN_IDS.contains(chain.getChainId())) {
            return true;
        }
        String host = hostOf(chain.getRpcUrl());
        return host != null && (LOCAL_HOSTS.contains(host) || host.equals(demoRpcHost));
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
