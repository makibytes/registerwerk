package de.makibytes.registerwerk.wallet.internal;

/** Vendor/profile adapter for the deliberately small PKCS#11 surface Registerwerk uses. */
interface Pkcs11Adapter {
    HsmProperties.Profile profile();
    String digestSignatureAlgorithm(HsmProperties properties);
}
