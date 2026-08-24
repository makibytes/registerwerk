package de.makibytes.registerwerk.chain.api;

import java.util.UUID;

/**
 * Whether {@code blockchain.internal.ChaincacheDurableStreamManager} currently holds a live
 * durable-event WebSocket connection for a chain — the same state backing the
 * {@code registerwerk_chaincache_stream_connected} gauge, exposed as a port owned by this module
 * (rather than {@code blockchain.api}) so {@code chain.internal.RpcNodeService} can depend on it
 * without creating a {@code chain -> blockchain} edge on top of the pre-existing
 * {@code blockchain -> chain} one: {@code blockchain.internal} implements a {@code chain.api} port,
 * the same direction it already depends in for {@link ChainConfigRepository}/{@link RpcNodeRepository}.
 */
public interface ChaincacheStreamStatus {

    /**
     * @return {@code true} if this instance currently has an open durable-stream connection for
     *         {@code chainConfigId}; {@code false} if it doesn't (never connected, disconnected, or
     *         the chain doesn't use chaincache at all) — callers don't need to distinguish those
     *         cases, they all mean "not delivering events right now".
     */
    boolean isConnected(UUID chainConfigId);
}
