package de.makibytes.registerwerk.blockchain.api;

import io.reactivex.Flowable;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.BatchRequest;
import org.web3j.protocol.core.BatchResponse;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.exceptions.ClientConnectionException;
import org.web3j.protocol.websocket.events.Notification;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Per-call failover across the ranked RPC nodes of one chain (P4B-8). Reads that fail with a
 * transport error are retried on the next-ranked node (bounded: {@link #MAX_ATTEMPTS} attempts in
 * total) instead of waiting for the next health round. JSON-RPC level errors are answers, not
 * transport failures, and are never retried. Writes ({@code eth_sendRawTransaction}) go to the
 * top-ranked node only, never to a second one: the durable outbox owns re-broadcast. A write the node
 * accepted is reported so the registry can pin the following reads to that node (read-your-writes).
 */
public final class FailoverWeb3jService implements Web3jService {

    public static final int MAX_ATTEMPTS = 3;

    /** One routable node: its id and a service bound to its endpoint. */
    public record Target(UUID nodeId, Web3jService service) {}

    private final Supplier<List<Target>> ranking;
    private final Consumer<UUID> onTransportFailure;
    private final Consumer<UUID> onWriteAccepted;

    public FailoverWeb3jService(Supplier<List<Target>> ranking, Consumer<UUID> onTransportFailure,
                                Consumer<UUID> onWriteAccepted) {
        this.ranking = ranking;
        this.onTransportFailure = onTransportFailure;
        this.onWriteAccepted = onWriteAccepted;
    }

    static boolean isWrite(Request<?, ?> request) {
        String m = request.getMethod();
        return "eth_sendRawTransaction".equals(m) || "eth_sendTransaction".equals(m);
    }

    private static boolean isTransport(Throwable t) {
        Throwable c = t;
        while ((c instanceof CompletionException || c instanceof java.util.concurrent.ExecutionException)
                && c.getCause() != null) {
            c = c.getCause();
        }
        return c instanceof IOException || c instanceof ClientConnectionException;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public <T extends Response> T send(Request request, Class<T> type) throws IOException {
        List<Target> targets = ranking.get();
        boolean write = isWrite(request);
        int limit = write ? 1 : Math.min(MAX_ATTEMPTS, targets.size());
        IOException lastIo = null;
        RuntimeException lastRuntime = null;
        for (int i = 0; i < limit; i++) {
            Target t = targets.get(i);
            try {
                T response = t.service().send(request, type);
                if (write && !response.hasError()) onWriteAccepted.accept(t.nodeId());
                return response;
            } catch (IOException e) {
                onTransportFailure.accept(t.nodeId());
                lastIo = e;
            } catch (ClientConnectionException e) {
                onTransportFailure.accept(t.nodeId());
                lastRuntime = e;
            }
        }
        if (lastIo != null) throw lastIo;
        throw lastRuntime;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public <T extends Response> CompletableFuture<T> sendAsync(Request request, Class<T> type) {
        List<Target> targets = ranking.get();
        boolean write = isWrite(request);
        return attempt(request, type, targets, 0, write ? 1 : Math.min(MAX_ATTEMPTS, targets.size()), write);
    }

    @SuppressWarnings("rawtypes")
    private <T extends Response> CompletableFuture<T> attempt(Request request, Class<T> type,
            List<Target> targets, int index, int limit, boolean write) {
        Target t = targets.get(index);
        return t.service().sendAsync(request, type).handle((response, error) -> {
            if (error == null) {
                if (write && !response.hasError()) onWriteAccepted.accept(t.nodeId());
                return CompletableFuture.completedFuture(response);
            }
            if (isTransport(error)) {
                onTransportFailure.accept(t.nodeId());
                if (index + 1 < limit) return attempt(request, type, targets, index + 1, limit, write);
            }
            return CompletableFuture.<T>failedFuture(error);
        }).thenCompose(f -> f);
    }

    @Override
    public BatchResponse sendBatch(BatchRequest batch) throws IOException {
        List<Target> targets = ranking.get();
        IOException last = null;
        for (int i = 0; i < Math.min(MAX_ATTEMPTS, targets.size()); i++) {
            try {
                return targets.get(i).service().sendBatch(batch);
            } catch (IOException e) {
                onTransportFailure.accept(targets.get(i).nodeId());
                last = e;
            }
        }
        throw last;
    }

    @Override
    public CompletableFuture<BatchResponse> sendBatchAsync(BatchRequest batch) {
        return ranking.get().getFirst().service().sendBatchAsync(batch);
    }

    @Override
    public <T extends Notification<?>> Flowable<T> subscribe(Request request, String unsubscribeMethod, Class<T> type) {
        return ranking.get().getFirst().service().subscribe(request, unsubscribeMethod, type);
    }

    @Override
    public void close() {
        // the per-node services are owned by the registry
    }
}
