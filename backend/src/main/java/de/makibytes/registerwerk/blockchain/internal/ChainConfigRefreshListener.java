package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.chain.api.ChainConfigUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class ChainConfigRefreshListener {
    private static final Logger log = LoggerFactory.getLogger(ChainConfigRefreshListener.class);
    private final BlockchainClientRegistry blockchainClientRegistry;
    ChainConfigRefreshListener(BlockchainClientRegistry blockchainClientRegistry) {
        this.blockchainClientRegistry = blockchainClientRegistry;
    }
    @EventListener
    void onChainConfigUpdated(ChainConfigUpdatedEvent event) {
        log.info("Chain config updated, refreshing blockchain client registry.");
        blockchainClientRegistry.refresh();
    }
}
