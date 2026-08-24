package de.makibytes.registerwerk.wallet.internal;

import org.springframework.stereotype.Component;

@Component
final class CustomPkcs11Adapter implements Pkcs11Adapter {
    @Override public HsmProperties.Profile profile() { return HsmProperties.Profile.CUSTOM; }

    @Override
    public String digestSignatureAlgorithm(HsmProperties properties) {
        if (properties.getSignatureAlgorithm() == null || properties.getSignatureAlgorithm().isBlank()) {
            return "NONEwithECDSA";
        }
        return properties.getSignatureAlgorithm().trim();
    }
}
