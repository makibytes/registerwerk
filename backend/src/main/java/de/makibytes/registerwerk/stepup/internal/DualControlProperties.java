package de.makibytes.registerwerk.stepup.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** {@code registerwerk.auth.step-up.dual-control.*} (K3, 6-08). */
@Component
@ConfigurationProperties(prefix = "registerwerk.auth.step-up.dual-control")
public class DualControlProperties {

    /** How long after minting an approver token is accepted. Short on purpose: the approver reviews one concrete request. */
    private long windowSeconds = 300;
    /** Reasons whose target binding is enforced; {@code *} = all. Exists only to roll back a bad deploy. */
    private List<String> bindTargetReasons = new ArrayList<>(List.of("*"));
    /** Reasons for which the canonical request body is part of the bound target. */
    private List<String> bindBodyReasons = new ArrayList<>();
    private int maxBodyBytes = 1_048_576;

    boolean bindsTarget(String reason) {
        return bindTargetReasons.stream().map(String::trim).anyMatch(r -> r.equals("*") || r.equals(reason));
    }

    boolean bindsBody(String reason) {
        return bindsTarget(reason) && bindBodyReasons.stream().map(String::trim).anyMatch(r -> r.equals(reason));
    }

    public long getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(long windowSeconds) { this.windowSeconds = windowSeconds; }
    public List<String> getBindTargetReasons() { return bindTargetReasons; }
    public void setBindTargetReasons(List<String> bindTargetReasons) { this.bindTargetReasons = bindTargetReasons; }
    public List<String> getBindBodyReasons() { return bindBodyReasons; }
    public void setBindBodyReasons(List<String> bindBodyReasons) { this.bindBodyReasons = bindBodyReasons; }
    public int getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(int maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
}
