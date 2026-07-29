package de.makibytes.registerwerk.blockchain.internal;

import com.daml.ledger.javaapi.data.AllocatePartyResponse;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.CantonLedgerClient;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.wallet.api.WalletStorage;
import de.makibytes.registerwerk.wallet.api.WalletStorage.CantonContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Allocates Canton parties on a participant node and persists credentials.
 *
 * <p>On Canton, creating a "wallet" means allocating a new party on the participant
 * and storing the resulting party ID + auth JWT for use in transaction submissions.
 */
@Component
public class CantonPartyAllocator {

    private static final Logger log = LoggerFactory.getLogger(CantonPartyAllocator.class);

    private final BlockchainClientRegistry registry;
    private final WalletStorage walletStorage;
    private final ChainConfigRepository chainConfigRepository;

    public CantonPartyAllocator(
            BlockchainClientRegistry registry,
            WalletStorage walletStorage,
            ChainConfigRepository chainConfigRepository) {
        this.registry              = registry;
        this.walletStorage         = walletStorage;
        this.chainConfigRepository = chainConfigRepository;
    }

    /**
     * Allocates a new party on the Canton participant bound to the given chain config,
     * stores the party credentials, and returns the resulting context.
     *
     * @param walletId    UUID of the newly created OperatorWallet record (for the keystore filename)
     * @param chainConfig the CANTON chain config whose participant to use
     * @param displayName human-readable name hint for the party (e.g. "Registerwerk-Issuer")
     * @return allocated party ID and JWT
     */
    public AllocationResult allocateParty(UUID walletId, ChainConfig chainConfig, String displayName) {
        CantonLedgerClient client = (CantonLedgerClient) registry.getCantonClientByIdentifier(chainConfig.getIdentifier());

        AllocatePartyResponse response = client.partyManagementClient()
                .allocateParty(displayName, displayName)
                .blockingGet();

        String partyId = response.getPartyDetails().getParty();
        log.info("Allocated Canton party '{}' on {}", partyId, chainConfig.getIdentifier());

        // Known limitation, not a pending fix: open-source/Community Canton has no API to mint
        // a JWT scoped to a single party — only Canton Enterprise supports per-party token
        // issuance. On Community Canton every locally hosted party is authorized by the
        // participant's shared admin token, so there is deliberately nothing party-specific to
        // store here; submissions for this party go through CantonLedgerClient's own
        // admin-token-bearing channel. Revisit only if/when the participant upgrades to
        // Canton Enterprise.
        String jwt = "";

        String keystorePath = walletStorage.storeCanton(walletId, partyId, jwt);
        return new AllocationResult(partyId, keystorePath);
    }

    /**
     * Stores an externally supplied party ID + JWT without allocating via the participant.
     * Used by the import-raw wallet endpoint for CANTON wallets.
     */
    public String importParty(UUID walletId, String partyId, String jwt) {
        return walletStorage.storeCanton(walletId, partyId, jwt);
    }

    /**
     * Result of a party allocation or import.
     *
     * @param partyId     the Canton party ID (stored as OperatorWallet.address)
     * @param keystorePath relative path of the stored credentials file
     */
    public record AllocationResult(String partyId, String keystorePath) {}
}
