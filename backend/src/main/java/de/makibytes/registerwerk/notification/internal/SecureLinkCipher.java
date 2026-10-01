package de.makibytes.registerwerk.notification.internal;


import de.makibytes.registerwerk.shared.EnvelopeCipher;
import de.makibytes.registerwerk.shared.SecureLinkPort;
import de.makibytes.registerwerk.wallet.api.KekProvider;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** {@link SecureLinkPort} over the shared {@link EnvelopeCipher}; the user id is bound as AAD. */
@Component
class SecureLinkCipher implements SecureLinkPort {

    private final EnvelopeCipher cipher;

    SecureLinkCipher(KekProvider kekProvider) {
        this.cipher = new EnvelopeCipher(kekProvider);
    }

    @Override
    public String seal(String link, UUID userId) {
        return cipher.encrypt(link, aad(userId));
    }

    @Override
    public String open(String sealed, UUID userId) {
        return cipher.decrypt(sealed, aad(userId));
    }

    private static String aad(UUID userId) {
        return "notification-link:" + userId;
    }
}
