package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.internal.tx.EvmSignedSubmission.ErrorClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class BroadcastErrorClassifierTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Prepared transaction submission error: replacement transaction underpriced|UNDERPRICED",
            "transaction underpriced|UNDERPRICED",
            "max fee per gas less than block base fee: address 0xabc, maxFeePerGas: 1 baseFee: 9|BASE_FEE",
            "insufficient funds for gas * price + value|INSUFFICIENT_FUNDS",
            "nonce too low: next nonce 8, tx nonce 7|NONCE_LOW",
            "intrinsic gas too low|INVALID",
            "invalid sender|INVALID",
            "exceeds block gas limit|INVALID",
            "Connection reset by peer|TRANSPORT",
            "HTTP 503 Service Unavailable|TRANSPORT",
            "something nobody has ever seen|OTHER"
    })
    void classifiesNodeMessages(String message, ErrorClass expected) {
        assertThat(BroadcastErrorClassifier.classify(message)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"INVALID,true", "NONCE_LOW,true", "UNDERPRICED,false", "TRANSPORT,false", "OTHER,false"})
    void permanentClassesCannotSucceedByRetryingIdenticalBytes(ErrorClass errorClass, boolean permanent) {
        assertThat(BroadcastErrorClassifier.isPermanent(errorClass)).isEqualTo(permanent);
    }

    @ParameterizedTest
    @CsvSource({"UNDERPRICED,true", "BASE_FEE,true", "INSUFFICIENT_FUNDS,false", "NONCE_LOW,false"})
    void onlyFeeClassesAreFixedByAHigherFee(ErrorClass errorClass, boolean feeRelated) {
        assertThat(BroadcastErrorClassifier.isFeeRelated(errorClass)).isEqualTo(feeRelated);
    }

    @org.junit.jupiter.api.Test
    void nullAndBlankMessagesAreOther() {
        assertThat(BroadcastErrorClassifier.classify(null)).isEqualTo(ErrorClass.OTHER);
        assertThat(BroadcastErrorClassifier.classify("  ")).isEqualTo(ErrorClass.OTHER);
    }
}
