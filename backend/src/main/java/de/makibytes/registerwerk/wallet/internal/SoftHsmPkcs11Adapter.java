package de.makibytes.registerwerk.wallet.internal;

import org.springframework.stereotype.Component;

@Component
final class SoftHsmPkcs11Adapter implements Pkcs11Adapter {
    @Override public HsmProperties.Profile profile() { return HsmProperties.Profile.SOFTHSM; }
    @Override public String digestSignatureAlgorithm(HsmProperties properties) { return "NONEwithECDSA"; }
}
