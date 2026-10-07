package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("VaultRevertReasons — forward-pricing revert (T1-07)")
class VaultRevertReasonsTest {

    private static final String SELECTOR =
            Hash.sha3String("NavNotStruckAfterDealingPoint(uint256,uint256,uint256)").substring(0, 10);

    private static String word(long value) {
        return String.format("%064x", BigInteger.valueOf(value));
    }

    @Test
    @DisplayName("a revert named NavNotStruckAfterDealingPoint is 'waiting for the next NAV strike' (409), not a generic failure")
    void namedError_isAWaitingConditionNotAFailure() {
        RuntimeException e = VaultRevertReasons.translate(
                "execution reverted: NavNotStruckAfterDealingPoint(7, 1800014400, 1799971200)", "fulfilment");

        assertThat(e).isInstanceOf(InvalidStateTransitionException.class);
        assertThat(e.getMessage()).contains("waiting for the next NAV strike");
    }

    @Test
    @DisplayName("the revert data (selector + three words) is decoded into the request, its dealing point and the last strike")
    void selectorWithData_isDecodedIntoTheMessage() {
        String reason = "execution reverted: custom error " + SELECTOR + ":" + word(7) + word(1_800_014_400L)
                + word(1_799_971_200L);

        RuntimeException e = VaultRevertReasons.translate(reason, "fulfilment");

        assertThat(e).isInstanceOf(InvalidStateTransitionException.class);
        assertThat(e.getMessage())
                .contains("waiting for the next NAV strike")
                .contains("#7")
                .contains("2027-01-15T12:00:00Z")
                .contains("2027-01-15T00:00:00Z");
    }

    @Test
    @DisplayName("revert data without a NAV strike yet (navStruckAt = 0) says that no NAV was ever struck")
    void neverStruck_isSaid() {
        String reason = "execution reverted " + SELECTOR + word(9) + word(1_800_014_400L) + word(0);

        assertThat(VaultRevertReasons.translate(reason, "fulfilment").getMessage())
                .contains("waiting for the next NAV strike").contains("no NAV has been struck");
    }

    @Test
    @DisplayName("the bare selector (a node that gives no data) is still recognised")
    void bareSelector_isRecognised() {
        RuntimeException e = VaultRevertReasons.translate("execution reverted: custom error " + SELECTOR, "fulfilment");

        assertThat(e).isInstanceOf(InvalidStateTransitionException.class);
        assertThat(e.getMessage()).contains("waiting for the next NAV strike");
    }

    @Test
    @DisplayName("an unrelated custom error stays a generic would-revert failure")
    void otherCustomErrors_stayGeneric() {
        RuntimeException e = VaultRevertReasons.translate("execution reverted: custom error 0x12345678", "fulfilment");

        assertThat(e).isInstanceOf(IllegalStateException.class);
        assertThat(e.getMessage()).doesNotContain("waiting for the next NAV strike");
    }

    @Test
    @DisplayName("the legacy 'NAV not struck' string revert keeps its own message")
    void legacyNavNotStruck_unchanged() {
        assertThat(VaultRevertReasons.translate("execution reverted: EwpgERC4626: NAV not struck", "fulfilment")
                .getMessage()).contains("No NAV has been struck on the vault");
    }
}
