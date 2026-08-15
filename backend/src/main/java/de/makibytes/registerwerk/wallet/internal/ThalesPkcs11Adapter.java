package de.makibytes.registerwerk.wallet.internal;

import org.springframework.stereotype.Component;

/** Thales Luna adapter. The Luna client library is selected by the SunPKCS11 config file. */
@Component
final class ThalesPkcs11Adapter implements Pkcs11Adapter {
    @Override public HsmProperties.Profile profile() { return HsmProperties.Profile.THALES; }
    @Override public String digestSignatureAlgorithm(HsmProperties properties) { return "NONEwithECDSA"; }
}
