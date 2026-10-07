package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.SolanaClientFactory;
import de.makibytes.registerwerk.blockchain.api.Web3jClientFactory;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.RpcNodeChainVerifier;
import io.micrometer.core.instrument.MeterRegistry;
import org.p2p.solanaj.rpc.RpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import de.makibytes.registerwerk.chain.events.RpcNodeChangedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthBlock;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Verifies that an RPC endpoint serves the chain the {@link ChainConfig} pins (P4C-1): the node's
 * {@code eth_chainId} must equal {@code chain_config.chain_id}, and its genesis block hash must equal
 * {@code chain_config.genesis_hash}. The genesis hash is captured by the first check whose chain id
 * matched (compare-and-set, so a node can never overwrite an existing pin). Solana has no chain id;
 * only the genesis hash is compared.
 */
@Component
class RpcChainIdentityChecker implements RpcNodeChainVerifier {

    private static final Logger log = LoggerFactory.getLogger(RpcChainIdentityChecker.class);

    private final Web3jClientFactory web3jClientFactory;
    private final SolanaClientFactory solanaClientFactory;
    private final ChainConfigRepository chainConfigRepository;
    private final MeterRegistry meters;
    private final long timeoutSeconds;
    private final LocalDevChainRepinPolicy repinPolicy;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    RpcChainIdentityChecker(Web3jClientFactory web3jClientFactory, SolanaClientFactory solanaClientFactory,
                            ChainConfigRepository chainConfigRepository, MeterRegistry meters,
                            @Value("${registerwerk.rpc.health-check-timeout-seconds:5}") long timeoutSeconds,
                            LocalDevChainRepinPolicy repinPolicy, ApplicationEventPublisher events,
                            PlatformTransactionManager transactionManager) {
        this.web3jClientFactory = web3jClientFactory;
        this.solanaClientFactory = solanaClientFactory;
        this.chainConfigRepository = chainConfigRepository;
        this.meters = meters;
        this.timeoutSeconds = timeoutSeconds;
        this.repinPolicy = repinPolicy;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public Verdict verify(ChainConfig chain, String url) {
        try {
            return switch (chain.getChainType()) {
                case EVM -> checkEvm(chain, web3jClientFactory.createClient(url));
                case SOLANA -> checkSolana(chain, solanaClientFactory.createClient(url));
                default -> Verdict.unverifiable("no chain identity check for " + chain.getChainType());
            };
        } catch (RuntimeException e) {
            return Verdict.unverifiable("endpoint could not be verified: " + e.getMessage());
        }
    }

    /** Never throws: an unreachable endpoint is UNVERIFIABLE (the caller's probe records the failure). */
    Verdict checkEvm(ChainConfig chain, Web3j client) {
        Long pinnedChainId = chain.getChainId();
        if (pinnedChainId == null) {
            return Verdict.unverifiable("chain id of " + chain.getIdentifier() + " is not pinned");
        }
        try {
            long reported = client.ethChainId().sendAsync().get(timeoutSeconds, TimeUnit.SECONDS)
                    .getChainId().longValueExact();
            if (reported != pinnedChainId) {
                return mismatch(chain, "eth_chainId " + reported + " differs from the pinned chain id " + pinnedChainId);
            }
            EthBlock.Block genesis = client.ethGetBlockByNumber(DefaultBlockParameterName.EARLIEST, false)
                    .sendAsync().get(timeoutSeconds, TimeUnit.SECONDS).getBlock();
            if (genesis == null || genesis.getHash() == null) {
                return Verdict.unverifiable("genesis block not available");
            }
            return compareGenesis(chain, genesis.getHash());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Verdict.unverifiable("interrupted");
        } catch (Exception e) {
            return Verdict.unverifiable("endpoint could not be verified: " + e.getMessage());
        }
    }

    Verdict checkSolana(ChainConfig chain, RpcClient client) {
        try {
            return compareGenesis(chain, client.getApi().getGenesisHash());
        } catch (Exception e) {
            return Verdict.unverifiable("endpoint could not be verified: " + e.getMessage());
        }
    }

    private Verdict compareGenesis(ChainConfig chain, String reportedRaw) {
        String reported = reportedRaw == null ? "" : reportedRaw.trim().toLowerCase(Locale.ROOT);
        if (reported.isEmpty()) {
            return Verdict.unverifiable("empty genesis hash");
        }
        String pinned = chain.getGenesisHash();
        if (pinned == null || pinned.isBlank()) {
            if (chainConfigRepository.pinGenesisHashIfAbsent(chain.getId(), reported) > 0) {
                log.info("Pinned genesis hash of chain {}: {}", chain.getIdentifier(), reported);
                chain.setGenesisHash(reported);
                return new Verdict(Outcome.MATCH, "genesis hash pinned");
            }
            // Lost a race with another pin: compare against the winner.
            String winner = chainConfigRepository.findById(chain.getId()).map(ChainConfig::getGenesisHash).orElse(null);
            chain.setGenesisHash(winner);
            pinned = winner;
            if (pinned == null) return Verdict.unverifiable("genesis hash could not be pinned");
        }
        if (!pinned.equalsIgnoreCase(reported)) {
            if (repinPolicy.allowsRepin(chain) && repin(chain, pinned, reported)) {
                return new Verdict(Outcome.MATCH, "genesis hash re-pinned (local dev chain recreated)");
            }
            return mismatch(chain, "genesis hash " + reported + " differs from the pinned " + pinned);
        }
        return new Verdict(Outcome.MATCH, "ok");
    }

    private Verdict mismatch(ChainConfig chain, String detail) {
        meters.counter("registerwerk.rpc.chain_mismatch", "chain", String.valueOf(chain.getIdentifier())).increment();
        return new Verdict(Outcome.MISMATCH, detail);
    }

    /**
     * Compare-and-set re-pin plus its audit event in ONE transaction: the audit listener is a
     * transactional event listener, so an event published outside a transaction (the health round has
     * none) would be dropped silently.
     */
    private boolean repin(ChainConfig chain, String previous, String reported) {
        boolean changed = Boolean.TRUE.equals(tx.execute(status -> {
            if (chainConfigRepository.repinGenesisHash(chain.getId(), previous, reported) == 0) {
                return false;
            }
            events.publishEvent(new RpcNodeChangedEvent(chain.getId(), null, "SYSTEM", "GENESIS_PIN_AUTO_REPIN",
                    chain.getId(), reported, previous, null, null));
            return true;
        }));
        if (changed) {
            log.warn("Local dev chain {} was recreated: genesis hash re-pinned {} -> {} (demo seeding on, "
                    + "non-production)", chain.getIdentifier(), previous, reported);
            chain.setGenesisHash(reported);
        }
        return changed;
    }
}
