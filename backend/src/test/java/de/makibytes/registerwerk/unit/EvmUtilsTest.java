package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.blockchain.api.EvmUtils;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvmUtilsTest {

    @Test
    void uuidToBytes32_producesA32ByteArray() {
        byte[] result = EvmUtils.uuidToBytes32(UUID.randomUUID());
        assertThat(result).hasSize(32);
    }

    @Test
    void uuidToBytes32_placesMostAndLeastSignificantBitsInTheHighAndLow16Bytes() {
        UUID known = new UUID(0x0102030405060708L, 0x090A0B0C0D0E0F10L);

        byte[] result = EvmUtils.uuidToBytes32(known);

        // Bytes 0-15 must be zero (left-padding).
        for (int i = 0; i < 16; i++) {
            assertThat(result[i]).as("byte %d should be zero-padded", i).isZero();
        }
        // Bytes 16-23: most significant bits, big-endian.
        assertThat(result[16]).isEqualTo((byte) 0x01);
        assertThat(result[17]).isEqualTo((byte) 0x02);
        assertThat(result[23]).isEqualTo((byte) 0x08);
        // Bytes 24-31: least significant bits, big-endian.
        assertThat(result[24]).isEqualTo((byte) 0x09);
        assertThat(result[31]).isEqualTo((byte) 0x10);
    }

    @Test
    void uuidToBytes32_isDeterministicForTheSameUuid() {
        UUID uuid = UUID.randomUUID();
        assertThat(EvmUtils.uuidToBytes32(uuid)).isEqualTo(EvmUtils.uuidToBytes32(uuid));
    }

    @Test
    void uuidToBytes32_differsForDifferentUuids() {
        assertThat(EvmUtils.uuidToBytes32(UUID.randomUUID()))
                .isNotEqualTo(EvmUtils.uuidToBytes32(UUID.randomUUID()));
    }

    @Test
    void uuidToBytes32_handlesAllZeroUuid() {
        byte[] result = EvmUtils.uuidToBytes32(new UUID(0L, 0L));
        assertThat(result).containsOnly((byte) 0);
    }
}
