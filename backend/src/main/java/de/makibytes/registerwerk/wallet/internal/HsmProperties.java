package de.makibytes.registerwerk.wallet.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Single-instance PKCS#11 configuration. Secrets are supplied through environment variables. */
@Component
@ConfigurationProperties(prefix = "registerwerk.wallet.hsm")
public class HsmProperties {

    public enum Profile { SOFTHSM, THALES, UTIMACO, CUSTOM }

    private boolean enabled;
    private Profile profile = Profile.SOFTHSM;
    private String providerConfig = "";
    private String pin = "";
    private String signatureAlgorithm = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Profile getProfile() { return profile; }
    public void setProfile(Profile profile) { this.profile = profile; }
    public String getProviderConfig() { return providerConfig; }
    public void setProviderConfig(String providerConfig) { this.providerConfig = providerConfig; }
    public String getPin() { return pin; }
    public void setPin(String pin) { this.pin = pin; }
    public String getSignatureAlgorithm() { return signatureAlgorithm; }
    public void setSignatureAlgorithm(String signatureAlgorithm) { this.signatureAlgorithm = signatureAlgorithm; }
}
