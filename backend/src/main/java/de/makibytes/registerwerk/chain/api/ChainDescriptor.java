package de.makibytes.registerwerk.chain.api;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;

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
