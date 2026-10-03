package de.makibytes.registerwerk.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EnvelopeCipher")
class EnvelopeCipherTest {

    private static final EnvelopeCipher.KeyWrapper KEK = new EnvelopeCipher.KeyWrapper() {
        @Override public String name() { return "TEST-KEK"; }
        @Override public byte[] wrap(byte[] dek) { return xor(dek); }
        @Override public byte[] unwrap(byte[] wrapped) { return xor(wrapped); }
        private byte[] xor(byte[] in) {
            byte[] out = in.clone();
            for (int i = 0; i < out.length; i++) out[i] ^= (byte) 0x33;
            return out;
        }
    };

    private final EnvelopeCipher cipher = new EnvelopeCipher(KEK);

    @Test
    @DisplayName("round-trips and binds the AAD")
    void roundTrip() {
        String stored = cipher.encrypt("JBSWY3DPEHPK3PXP", "totp-secret:u1");
        assertThat(stored).startsWith(EnvelopeCipher.PREFIX);
        assertThat(cipher.decrypt(stored, "totp-secret:u1")).isEqualTo("JBSWY3DPEHPK3PXP");
        assertThatThrownBy(() -> cipher.decrypt(stored, "totp-secret:u2")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("H14: a stored value without the enc: prefix is refused, never returned as a 'legacy plaintext' secret")
    void plaintextIsRefused() {
        assertThatThrownBy(() -> cipher.decrypt("JBSWY3DPEHPK3PXP", "totp-secret:u1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt("", "totp-secret:u1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(null, "totp-secret:u1")).isInstanceOf(IllegalStateException.class);
    }
}
