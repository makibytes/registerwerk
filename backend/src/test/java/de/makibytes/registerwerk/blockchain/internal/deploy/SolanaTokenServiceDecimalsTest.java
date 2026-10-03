package de.makibytes.registerwerk.blockchain.internal.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SolanaTokenServiceDecimalsTest {

    @Test
    @DisplayName("C5: SPL / Token-2022 mints are initialised with 0 decimals (register amounts are whole units)")
    void mintsAreInitialisedWithZeroDecimals() {
        assertThat(SolanaTokenService.REGISTER_UNIT_DECIMALS).isZero();
    }
}
