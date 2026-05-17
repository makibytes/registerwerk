package de.makibytes.registerwerk.chain.api;

import com.daml.ledger.javaapi.data.*;
import com.daml.ledger.rxjava.DamlLedgerClient;
import com.daml.ledger.rxjava.components.helpers.CommandsAndPendingSet;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around {@link DamlLedgerClient} exposing the Ledger API services used by
 * Registerwerk. Mirrors the role of Solanaj's {@code RpcClient} in the Solana integration —
 * one instance per Canton participant endpoint.
 *
 * <p>Construct via {@link CantonClientFactory}; manage lifecycle through
 * {@link de.makibytes.registerwerk.application.blockchain.BlockchainClientRegistry}.
 */
public class CantonLedgerClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CantonLedgerClient.class);

    private final DamlLedgerClient delegate;
    private final ManagedChannel channel;
    private final String applicationId;
    private final String synchronizerId;
    private final String ledgerApiUrl;

    CantonLedgerClient(
            DamlLedgerClient delegate,
            ManagedChannel channel,
            String applicationId,
            String synchronizerId,
            String ledgerApiUrl) {
        this.delegate       = delegate;
        this.channel        = channel;
        this.applicationId  = applicationId;
        this.synchronizerId = synchronizerId;
        this.ledgerApiUrl   = ledgerApiUrl;
    }

    // ── Ledger API service accessors ─────────────────────────────────────────

    public com.daml.ledger.rxjava.CommandClient commandClient() {
        return delegate.getCommandClient();
    }

    public com.daml.ledger.rxjava.TransactionsClient transactionsClient() {
        return delegate.getTransactionsClient();
    }

    public com.daml.ledger.rxjava.ActiveContractSetClient activeContractSetClient() {
        return delegate.getActiveContractSetClient();
    }

    public com.daml.ledger.rxjava.PartyManagementClient partyManagementClient() {
        return delegate.getPartyManagementClient();
    }

    public com.daml.ledger.rxjava.PackageClient packageClient() {
        return delegate.getPackageClient();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Submits a command and waits for the transaction to be committed.
     * Returns the update ID (analogous to a transaction hash).
     *
     * @param actingParty the party submitting the command
     * @param commands    list of Ledger API commands to execute atomically
     * @return update ID from the committed transaction
     */
    public String submitAndWait(String actingParty, List<Command> commands) {
        CommandsSubmission submission = CommandsSubmission.create(
                        applicationId,
                        java.util.UUID.randomUUID().toString(),
                        commands)
                .withActAs(actingParty)
                .withSynchronizerId(synchronizerId);

        return commandClient()
                .submitAndWaitForTransaction(submission)
                .map(TransactionTree::getUpdateId)
                .blockingGet();
    }

    /**
     * Pings the Ledger API by requesting the ledger end offset.
     * Used by {@link de.makibytes.registerwerk.application.chain.RpcNodeHealthService}.
     *
     * @return absolute offset string if healthy
     * @throws RuntimeException if the participant is unreachable
     */
    public String getLedgerEnd() {
        return transactionsClient()
                .getLedgerEnd()
                .blockingGet()
                .getOffset();
    }

    public String getApplicationId() { return applicationId; }
    public String getSynchronizerId() { return synchronizerId; }
    public String getLedgerApiUrl() { return ledgerApiUrl; }

    @Override
    public void close() {
        try {
            delegate.close();
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Error closing Canton gRPC channel for {}: {}", ledgerApiUrl, e.getMessage());
        }
    }
}
