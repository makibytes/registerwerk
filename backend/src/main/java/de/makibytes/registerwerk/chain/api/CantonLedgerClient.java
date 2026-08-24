package de.makibytes.registerwerk.chain.api;

import com.daml.ledger.api.v2.CommandServiceGrpc;
import com.daml.ledger.api.v2.CommandServiceOuterClass;
import com.daml.ledger.api.v2.StateServiceGrpc;
import com.daml.ledger.api.v2.StateServiceOuterClass;
import com.daml.ledger.api.v2.UpdateServiceGrpc;
import com.daml.ledger.api.v2.admin.PartyManagementServiceGrpc;
import com.daml.ledger.api.v2.admin.PartyManagementServiceOuterClass;
import com.daml.ledger.javaapi.data.Command;
import com.daml.ledger.javaapi.data.CommandsSubmission;
import com.daml.ledger.javaapi.data.CreatedEvent;
import com.daml.ledger.javaapi.data.EventFormat;
import com.daml.ledger.javaapi.data.Filter;
import com.daml.ledger.javaapi.data.GetUpdatesRequest;
import com.daml.ledger.javaapi.data.GetUpdatesResponse;
import com.daml.ledger.javaapi.data.Transaction;
import com.daml.ledger.javaapi.data.TransactionFormat;
import com.daml.ledger.javaapi.data.TransactionShape;
import com.daml.ledger.javaapi.data.UpdateFormat;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Thin wrapper around the generated Ledger API v2 gRPC services used by Registerwerk.
 * Mirrors the role of Solanaj's {@code RpcClient} in the Solana integration — one instance
 * per Canton participant endpoint.
 *
 * <p>Construct via {@link CantonClientFactory}; manage lifecycle through
 * {@link de.makibytes.registerwerk.application.blockchain.BlockchainClientRegistry}.
 */
public class CantonLedgerClient implements CantonLedgerEndpoint {

    private static final Logger log = LoggerFactory.getLogger(CantonLedgerClient.class);

    private final ManagedChannel channel;
    private final String applicationId;
    private final String synchronizerId;
    private final String ledgerApiUrl;

    CantonLedgerClient(
            ManagedChannel channel,
            String applicationId,
            String synchronizerId,
            String ledgerApiUrl) {
        this.channel        = channel;
        this.applicationId  = applicationId;
        this.synchronizerId = synchronizerId;
        this.ledgerApiUrl   = ledgerApiUrl;
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
        return submitAndWaitForTransaction(actingParty, commands).getUpdateId();
    }

    /** Submits atomically and returns the committed transaction including visible events. */
    public Transaction submitAndWaitForTransaction(String actingParty, List<Command> commands) {
        CommandsSubmission submission = CommandsSubmission.create(
                        applicationId, java.util.UUID.randomUUID().toString(),
                        Optional.empty(), commands)
                .withActAs(actingParty);
        var commandProto = submission.toProto().toBuilder()
                .setSynchronizerId(synchronizerId)
                .build();
        var request = CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.newBuilder()
                .setCommands(commandProto)
                .setTransactionFormat(transactionFormatFor(actingParty).toProto())
                .build();
        var response = CommandServiceGrpc.newBlockingStub(channel)
                .submitAndWaitForTransaction(request);
        return Transaction.fromProto(response.getTransaction());
    }

    /**
     * Submits a create command and extracts the durable contract ID from the committed ledger
     * transaction. An update ID is not a contract ID and cannot later be used in an exercise.
     */
    public CommittedContract submitAndWaitForCreatedContract(
            String actingParty,
            List<Command> commands,
            com.daml.ledger.javaapi.data.Identifier expectedTemplate) {
        Transaction transaction = submitAndWaitForTransaction(actingParty, commands);
        List<CreatedEvent> matches = transaction.getEvents().stream()
                .filter(CreatedEvent.class::isInstance)
                .map(CreatedEvent.class::cast)
                .filter(event -> event.getTemplateId().getModuleName()
                        .equals(expectedTemplate.getModuleName()))
                .filter(event -> event.getTemplateId().getEntityName()
                        .equals(expectedTemplate.getEntityName()))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("Canton submission " + transaction.getUpdateId()
                    + " created " + matches.size() + " visible " + expectedTemplate.getModuleName()
                    + ":" + expectedTemplate.getEntityName() + " contracts; expected exactly one");
        }
        return new CommittedContract(transaction.getUpdateId(), matches.getFirst().getContractId());
    }

    public record CommittedContract(String updateId, String contractId) {}

    /** Allocates a locally hosted party through the current Ledger API v2 admin service. */
    public String allocateParty(String partyIdHint) {
        var request = PartyManagementServiceOuterClass.AllocatePartyRequest.newBuilder()
                .setPartyIdHint(partyIdHint)
                .setSynchronizerId(synchronizerId)
                .setUserId(applicationId)
                .build();
        var response = PartyManagementServiceGrpc.newBlockingStub(channel).allocateParty(request);
        if (!response.hasPartyDetails() || response.getPartyDetails().getParty().isBlank()) {
            throw new IllegalStateException("Canton allocateParty returned no party identity");
        }
        return response.getPartyDetails().getParty();
    }

    /** Cancellable server-stream subscription over transaction-shaped ledger updates. */
    public Subscription subscribeTransactions(
            long beginExclusive,
            Consumer<Transaction> onTransaction,
            Consumer<Throwable> onError) {
        Filter anyParty = wildcardFilter();
        EventFormat events = new EventFormat(Map.of(), Optional.of(anyParty), true);
        TransactionFormat transactions =
                new TransactionFormat(events, TransactionShape.LEDGER_EFFECTS);
        GetUpdatesRequest request = new GetUpdatesRequest(
                beginExclusive, Optional.empty(),
                new UpdateFormat(Optional.of(transactions), Optional.empty(), Optional.empty()));

        Context.CancellableContext context = Context.current().withCancellation();
        context.run(() -> UpdateServiceGrpc.newStub(channel).getUpdates(
                request.toProto(), new StreamObserver<>() {
                    @Override
                    public void onNext(com.daml.ledger.api.v2.UpdateServiceOuterClass.GetUpdatesResponse value) {
                        GetUpdatesResponse.fromProto(value).getTransaction().ifPresent(onTransaction);
                    }

                    @Override public void onError(Throwable error) { onError.accept(error); }

                    @Override public void onCompleted() {
                        onError.accept(new IllegalStateException("Canton update stream completed"));
                    }
                }));
        return () -> context.cancel(null);
    }

    private static TransactionFormat transactionFormatFor(String actingParty) {
        EventFormat events = new EventFormat(
                Map.of(actingParty, wildcardFilter()), Optional.empty(), true);
        return new TransactionFormat(events, TransactionShape.LEDGER_EFFECTS);
    }

    private static Filter wildcardFilter() {
        return new Filter() {
            @Override
            public com.daml.ledger.api.v2.TransactionFilterOuterClass.Filters toProto() {
                return com.daml.ledger.api.v2.TransactionFilterOuterClass.Filters.newBuilder()
                        .addCumulative(com.daml.ledger.api.v2.TransactionFilterOuterClass.CumulativeFilter
                                .newBuilder()
                                .setWildcardFilter(Filter.Wildcard.HIDE_CREATED_EVENT_BLOB.toProto()))
                        .build();
            }
        };
    }

    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override void close();
    }

    /**
     * Pings the Ledger API by requesting the ledger end offset.
     * Used by {@link de.makibytes.registerwerk.application.chain.RpcNodeHealthService}.
     *
     * @return absolute offset string if healthy
     * @throws RuntimeException if the participant is unreachable
     */
    public String getLedgerEnd() {
        long offset = StateServiceGrpc.newBlockingStub(channel)
                .getLedgerEnd(StateServiceOuterClass.GetLedgerEndRequest.getDefaultInstance())
                .getOffset();
        return Long.toString(offset);
    }

    public String getApplicationId() { return applicationId; }
    public String getSynchronizerId() { return synchronizerId; }
    public String getLedgerApiUrl() { return ledgerApiUrl; }

    @Override
    public void close() {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Error closing Canton gRPC channel for {}: {}", ledgerApiUrl, e.getMessage());
        }
    }
}
