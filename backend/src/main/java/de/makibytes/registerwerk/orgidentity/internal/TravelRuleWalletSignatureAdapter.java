package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.orgidentity.api.WalletSignatureVerifier;
import de.makibytes.registerwerk.travelrule.api.WalletSignaturePort;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Serves the travelrule module's {@link WalletSignaturePort} from the shared wallet-signature verifier. */
@Component
class TravelRuleWalletSignatureAdapter implements WalletSignaturePort {

    private final WalletSignatureVerifier verifier;

    TravelRuleWalletSignatureAdapter(WalletSignatureVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public void verifyPersonalSign(UUID chainConfigId, String message, String signatureHex, String claimedWallet) {
        verifier.verifyPersonalSign(chainConfigId, message, signatureHex, claimedWallet);
    }
}
