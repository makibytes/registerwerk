package de.makibytes.registerwerk.blockchain.internal.deploy;

import de.makibytes.registerwerk.blockchain.internal.deploy.StarknetTokenService.ResourceBound;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the full SNIP-8 Invoke v3 transaction-hash layout (fee-field sub-hash, resource-bound
 * felt packing, DA-mode packing, empty paymaster/deployment-data hashes) against a reference
 * vector from starknet.py {@code tests/unit/hash/transaction_test.py}.
 */
@DisplayName("Starknet Invoke v3 transaction hash")
class StarknetInvokeV3HashTest {

    private static BigInteger felt(String hex) {
        return new BigInteger(hex.replaceFirst("^0x", ""), 16);
    }

    @Test
    @DisplayName("matches the starknet.py reference vector")
    void matchesReferenceVector() {
        // tip=0, paymaster_data=[], account_deployment_data=[], DA modes L1/L1 — the same
        // fixed choices this service submits with.
        BigInteger hash = StarknetTokenService.computeInvokeV3Hash(
                felt("0x35ACD6DD6C5045D18CA6D0192AF46B335A5402C02D41F46E4E77EA2C951D9A3"),
                List.of(
                        felt("0x2"),
                        felt("0x6359ED638DF79B82F2F9DBF92ABBCB41B57F9DD91EAD86B1C85D2DEE192C"),
                        felt("0xB17D8A2731BA7CA1816631E6BE14F0FC1B8390422D649FA27F0FBB0C91EEA8"),
                        felt("0x0"),
                        felt("0x3FE8E4571772BBE0065E271686BD655EFD1365A5D6858981E582F82F2C10313"),
                        felt("0x2FD9126EE011F3A837CEA02E32AE4EE73342D827E216998E5616BAB88D8B7EA"),
                        felt("0x1"),
                        felt("0x2FD9126EE011F3A837CEA02E32AE4EE73342D827E216998E5616BAB88D8B7EA")),
                felt("0x534E5F474F45524C49"),   // chain id
                BigInteger.valueOf(5),           // nonce
                new ResourceBound("L1_GAS", BigInteger.valueOf(100_000), new BigInteger("10000000000000")),
                new ResourceBound("L2_GAS", new BigInteger("10000000000"), new BigInteger("100000000000000000000")),
                new ResourceBound("L1_DATA", BigInteger.valueOf(100_000), new BigInteger("10000000000000")));

        assertThat(hash).isEqualTo(
                felt("0x119386B4AAAEF905BF027D3DD2734474C5E944942BF3FBD8FDB442704D32B8B"));
    }
}
