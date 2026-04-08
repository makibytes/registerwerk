package de.makibytes.registerwerk.application.blockchain;

import de.makibytes.registerwerk.domain.enums.Chain;
import de.makibytes.registerwerk.domain.enums.Network;

/**
 * Identifies a specific chain + network combination (e.g. ETHEREUM + MAINNET).
 * Records automatically implement equals/hashCode based on all components.
 */
public record ChainDescriptor(Chain chain, Network network) {

    @Override
    public String toString() {
        return chain.name() + "/" + network.name();
    }
}
