package de.makibytes.registerwerk.wallet.internal;

import org.springframework.stereotype.Component;

/** Utimaco CryptoServer adapter. The vendor library is selected by the PKCS#11 config file. */
@Component
final class UtimacoPkcs11Adapter implements Pkcs11Adapter {
    @Override public HsmProperties.Profile profile() { return HsmProperties.Profile.UTIMACO; }
    @Override public String digestSignatureAlgorithm(HsmProperties properties) { return "NONEwithECDSA"; }
}
