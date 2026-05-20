package de.makibytes.registerwerk.blockchain.api;

import java.util.UUID;

/**
 * Shared EVM encoding utilities.
 */
public final class EvmUtils {

    private EvmUtils() {}

    /**
     * Encodes a UUID as a right-aligned 32-byte array (bytes 16-31 = most significant,
     * bytes 24-31 = least significant — matches Solidity's bytes32 ABI encoding of a UUID).
     */
    public static byte[] uuidToBytes32(UUID uuid) {
        byte[] b = new byte[32];
        long hi = uuid.getMostSignificantBits();
        long lo = uuid.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) {
            b[24 + i] = (byte) (lo & 0xFF); lo >>= 8;
        }
        for (int i = 7; i >= 0; i--) {
            b[16 + i] = (byte) (hi & 0xFF); hi >>= 8;
        }
        return b;
    }
}
