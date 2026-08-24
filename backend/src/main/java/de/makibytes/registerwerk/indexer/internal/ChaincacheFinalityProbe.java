package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.chain.api.ChaincacheCredentials;
import de.makibytes.registerwerk.chain.api.RpcNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.Optional;

/**
 * Reads a single block's finality directly from chaincache's own canonical-chain record —
 * {@code GET /{chain}/api/blocks/{number}/finality} — for chains that opted into
 * {@link de.makibytes.registerwerk.chain.api.ChainConfig.FinalitySource#CHAINCACHE}. Used by
 * {@link GraphNodeSyncService} as an alternative to {@code probeEvmBlock}'s own RPC self-probing
 * (fetching a fresh block hash and computing the level from head/safe/finalized tags) — chaincache
 * already tracks exactly this per block, gap-free, from its own durable canonical-chain state, so
 * there is nothing to (re)compute here beyond deserializing its answer.
 *
 * <p>chaincache's own javadoc on that endpoint states it is "shaped exactly like
 * {@code ReorgGuard.FinalityProbe}'s outcome so a consumer needs no translation layer" — its
 * {@code finality} field is one of chaincache's {@code BlockFinality} values
 * (PROVISIONAL/SAFE/FINALIZED), which map 1:1 onto {@link ReorgGuard.ProbeResult}'s same three
 * names. Detecting {@link ReorgGuard.ProbeResult#ORPHANED} is the caller's job, not this class's —
 * exactly like {@code probeEvmBlock}, it compares the returned {@code blockHash} against whatever
 * baseline {@code token_transfer} rows already recorded for that height; this class only reports
 * what chaincache currently has on record.
 */
@Component
class ChaincacheFinalityProbe {

    private static final Logger log = LoggerFactory.getLogger(ChaincacheFinalityProbe.class);

    private final RestClient.Builder restClientBuilder;
    private final ChaincacheCredentials credentials;

    ChaincacheFinalityProbe(RestClient.Builder restClientBuilder, ChaincacheCredentials credentials) {
        this.restClientBuilder = restClientBuilder;
        this.credentials = credentials;
    }

    record Observation(String blockHash, ReorgGuard.ProbeResult level) {}

    /**
     * @return chaincache's current record for {@code blockNumber} on {@code node}'s chain, or
     *         empty if the node is missing its chaincache metadata, chaincache is unreachable,
     *         it has no observation yet for this height (404 — a legitimate, expected outcome for
     *         a block chaincache hasn't ingested yet), or its {@code finality} value is anything
     *         other than the three expected strings. Never throws.
     */
    Optional<Observation> observe(RpcNode node, long blockNumber) {
        String managementUrl = node.getManagementUrl();
        String remoteChainKey = node.getRemoteChainKey();
        if (managementUrl == null || managementUrl.isBlank() || remoteChainKey == null || remoteChainKey.isBlank()) {
            return Optional.empty();
        }
        try {
            RestClient client = restClientBuilder.clone().baseUrl(managementUrl).build();
            BlockFinalityResponse response = client.get()
                    .uri("/{chain}/api/blocks/{number}/finality", remoteChainKey, blockNumber)
                    .headers(headers -> credentials.bearerFor(managementUrl).ifPresent(headers::setBearerAuth))
                    .retrieve()
                    .body(BlockFinalityResponse.class);
            if (response == null || response.blockHash() == null) {
                return Optional.empty();
            }
            ReorgGuard.ProbeResult level = switch (response.finality()) {
                case "FINALIZED" -> ReorgGuard.ProbeResult.FINALIZED;
                case "SAFE" -> ReorgGuard.ProbeResult.SAFE;
                case "PROVISIONAL" -> ReorgGuard.ProbeResult.PROVISIONAL;
                default -> null;
            };
            if (level == null) {
                log.warn("chaincache at {} returned an unrecognized finality value '{}' for chain={} block={}",
                        managementUrl, response.finality(), remoteChainKey, blockNumber);
                return Optional.empty();
            }
            return Optional.of(new Observation(response.blockHash(), level));
        } catch (RestClientException e) {
            // Covers both "unreachable" and a 404 ("no canonical observation yet") — both are
            // ordinary, expected outcomes here (a very fresh block, or chaincache being briefly
            // down), not errors worth escalating beyond debug: the caller already treats an empty
            // Optional as ProbeOutcome.unknown(), which never manufactures a false reorg.
            log.debug("chaincache block-finality probe failed for {} chain={} block={}: {}",
                    managementUrl, remoteChainKey, blockNumber, e.getMessage());
            return Optional.empty();
        }
    }

    /** Mirrors chaincache's {@code api.BlockFinalityResponse} record shape — see that class's own
     *  javadoc, which explicitly designs this shape for a consumer like this one. */
    record BlockFinalityResponse(String chainKey, long blockNumber, String blockHash, String finality, Instant observedAt) {}
}
