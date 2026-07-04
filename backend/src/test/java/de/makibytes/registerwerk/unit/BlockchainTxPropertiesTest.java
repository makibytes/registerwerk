package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.blockchain.api.BlockchainTxProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BlockchainTxPropertiesTest {

    @Test
    void confirmationsFor_usesDefaultWhenNoOverride() {
        BlockchainTxProperties props = new BlockchainTxProperties();
        props.setDefaultConfirmations(12);
        assertThat(props.confirmationsFor("ETHEREUM")).isEqualTo(12);
    }

    @Test
    void confirmationsFor_usesPerChainOverride() {
        BlockchainTxProperties props = new BlockchainTxProperties();
        props.setDefaultConfirmations(12);
        props.setConfirmationsByChain(Map.of("POLYGON", 128));
        assertThat(props.confirmationsFor("POLYGON")).isEqualTo(128);
        assertThat(props.confirmationsFor("BASE")).isEqualTo(12); // falls back to default
    }

    @Test
    void confirmationsFor_isCaseInsensitive() {
        BlockchainTxProperties props = new BlockchainTxProperties();
        props.setDefaultConfirmations(6);
        props.setConfirmationsByChain(Map.of("polygon", 128));
        assertThat(props.confirmationsFor("POLYGON")).isEqualTo(128);
        assertThat(props.confirmationsFor("Polygon")).isEqualTo(128);
    }

    @Test
    void confirmationsFor_nullChainYieldsDefault() {
        BlockchainTxProperties props = new BlockchainTxProperties();
        props.setDefaultConfirmations(9);
        assertThat(props.confirmationsFor(null)).isEqualTo(9);
    }

    @Test
    void timeoutSeconds_defaultsAndOverrides() {
        BlockchainTxProperties props = new BlockchainTxProperties();
        assertThat(props.getTimeoutSeconds()).isEqualTo(900);
        props.setTimeoutSeconds(300);
        assertThat(props.getTimeoutSeconds()).isEqualTo(300);
    }
}
