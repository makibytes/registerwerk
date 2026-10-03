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
    /**
     * Reasons whose target binding is enforced; {@code *} = all. Exists only to roll back a bad deploy. Rolling
     * it back also unbinds the body, but never the single-use consumption of the approver token.
     */
    private List<String> bindTargetReasons = new ArrayList<>(List.of("*"));
    /**
     * Reasons for which the canonical request body is part of the bound target; {@code *} = all, which is
     * the default. An approver reviews amounts, destinations and override notes, so an approval that left the
     * body unbound would let the initiator change them afterwards (C2).
     */
    private List<String> bindBodyReasons = new ArrayList<>(List.of("*"));
    /**
     * Explicit opt-out from body binding, for the few reasons whose payload is not JSON (a multipart
     * upload, a CSV) and therefore has no canonical form an approver could reproduce. Their method, path and
     * query stay bound. Every entry weakens the four-eyes check: keep this list short and justified.
     */
    private List<String> bodyOptOutReasons = new ArrayList<>();
    private int maxBodyBytes = 1_048_576;

    boolean bindsTarget(String reason) {
        return bindTargetReasons.stream().map(String::trim).anyMatch(r -> r.equals("*") || r.equals(reason));
    }

    boolean bindsBody(String reason) {
        return bindsTarget(reason)
                && bindBodyReasons.stream().map(String::trim).anyMatch(r -> r.equals("*") || r.equals(reason))
                && bodyOptOutReasons.stream().map(String::trim).noneMatch(r -> r.equals(reason));
    }

    public long getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(long windowSeconds) { this.windowSeconds = windowSeconds; }
    public List<String> getBindTargetReasons() { return bindTargetReasons; }
    public void setBindTargetReasons(List<String> bindTargetReasons) { this.bindTargetReasons = bindTargetReasons; }
    public List<String> getBindBodyReasons() { return bindBodyReasons; }
    public void setBindBodyReasons(List<String> bindBodyReasons) { this.bindBodyReasons = bindBodyReasons; }
    public List<String> getBodyOptOutReasons() { return bodyOptOutReasons; }
    public void setBodyOptOutReasons(List<String> bodyOptOutReasons) { this.bodyOptOutReasons = bodyOptOutReasons; }
    public int getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(int maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
}
