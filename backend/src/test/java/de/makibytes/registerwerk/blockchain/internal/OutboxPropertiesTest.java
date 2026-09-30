package de.makibytes.registerwerk.blockchain.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPropertiesTest {

    private final OutboxProperties props = new OutboxProperties();

    @Test
    void backoffGrowsFrom15SecondsToFiveMinutesAndStaysCapped() {
        assertThat(props.backoff(1)).isEqualTo(Duration.ofSeconds(15));
        assertThat(props.backoff(2)).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.backoff(3)).isEqualTo(Duration.ofSeconds(60));
        assertThat(props.backoff(6)).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.backoff(500)).isEqualTo(Duration.ofMinutes(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"registerIdentity", "addClaim", "updateManifest", "registerDapp", "setNavPerShare"})
    void allowListedNonRegulatoryCallsMayBeRepricedAutomatically(String method) {
        assertThat(props.mayAutoReprice(method)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"forcedTransfer", "forcedTransferSingle", "batchForcedTransfer", "forceBurn", "burn",
            "freezeAddress", "unfreezeAddress", "setAddressFrozen", "recoveryAddress", "pause", "mint",
            "cancelNonce", "somethingUnknown"})
    void regulatoryAndUnlistedCallsAreNeverRepricedAutomatically(String method) {
        assertThat(props.mayAutoReprice(method)).isFalse();
    }

    @Test
    void regulatoryPrefixWinsEvenIfSomeoneAllowListsTheMethod() {
        props.setAutoRepriceMethods(List.of("forcedTransfer", "freezeAddress", "registerIdentity"));
        assertThat(props.mayAutoReprice("forcedTransfer")).isFalse();
        assertThat(props.mayAutoReprice("freezeAddress")).isFalse();
        assertThat(props.mayAutoReprice("registerIdentity")).isTrue();
    }
}
