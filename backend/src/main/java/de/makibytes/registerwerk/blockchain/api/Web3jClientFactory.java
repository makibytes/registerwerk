package de.makibytes.registerwerk.blockchain.api;

import jakarta.annotation.PreDestroy;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.JsonRpc2_0Web3j;
import org.web3j.protocol.http.HttpService;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Factory that creates Web3j clients from RPC URLs.
 *
 * <h3>Why this factory owns the HTTP client and the scheduler</h3>
 * <p>{@code Web3j.build(Web3jService)} — the convenience overload this class used to call —
 * delegates to {@code org.web3j.utils.Async.defaultExecutorService()}, which is <em>not</em> a
 * cached singleton. Decompiling web3j 4.12.3 shows each invocation doing:
 *
 * <pre>
 *   Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors());
 *   Runtime.getRuntime().addShutdownHook(...);
 * </pre>
 *
 * <p>So every client allocated its own processor-sized thread pool <em>and registered a JVM
 * shutdown hook</em>. Shutdown hooks live in {@code ApplicationShutdownHooks.hooks}, a static
 * map — i.e. a GC root. Any client that was built and then dropped stayed permanently reachable,
 * together with its {@code OkHttpClient} and its thread pool. Callers that rebuilt clients on a
 * schedule therefore leaked without bound until the heap was exhausted and G1 spun on every core.
 *
 * <p>Building against an explicitly supplied scheduler avoids {@code Async} entirely: no
 * per-client pool, no shutdown hook. A discarded client is then plain garbage.
 *
 * <h3>Do not call {@code shutdown()} on clients from this factory</h3>
 * <p>{@link JsonRpc2_0Web3j#shutdown()} shuts down the scheduler it was handed and closes the
 * underlying service — both of which are <em>shared</em> here, so calling it would tear down
 * every other client too. To release a client, simply drop the reference. Idle sockets are
 * reclaimed by the shared {@link ConnectionPool}; this factory's {@link #close()} disposes the
 * shared resources at application shutdown.
 */
@Component
public class Web3jClientFactory {

    /**
     * web3j's own default (see {@code JsonRpc2_0Web3j.DEFAULT_BLOCK_TIME}). Only used by the
     * {@code *Flowable} polling APIs, which this application does not use — every call site goes
     * through request/response methods — but the 3-arg {@code build} requires a value.
     */
    private static final long POLLING_INTERVAL_MS = 15_000L;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CALL_TIMEOUT    = Duration.ofSeconds(30);

    /**
     * One scheduler for every client. Sized independently of {@code availableProcessors()} so a
     * container without a CPU limit does not silently inflate it; it only services web3j's
     * polling/timeout bookkeeping, which is not CPU-bound.
     */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "web3j-scheduler");
        t.setDaemon(true);
        return t;
    });

    /**
     * One HTTP client for every RPC endpoint. Sharing it is the documented OkHttp recommendation:
     * connection pool, thread pool and dispatcher are then pooled across endpoints instead of
     * duplicated per client.
     */
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT)
            .callTimeout(CALL_TIMEOUT)
            .connectionPool(new ConnectionPool(16, 5, TimeUnit.MINUTES))
            .build();

    /**
     * Creates a Web3j instance connected to the given RPC endpoint.
     *
     * <p>Cheap: the returned client is a thin wrapper over the shared HTTP client and scheduler.
     * It owns no dedicated threads and registers no shutdown hook, so it may be discarded without
     * cleanup — see the class javadoc for why {@code shutdown()} must not be called on it.
     *
     * @param rpcUrl HTTP(S) URL of the EVM JSON-RPC endpoint
     * @return configured Web3j instance
     */
    public Web3j createClient(String rpcUrl) {
        return Web3j.build(new HttpService(rpcUrl, httpClient, false), POLLING_INTERVAL_MS, scheduler);
    }

    @PreDestroy
    void close() {
        scheduler.shutdownNow();
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}
