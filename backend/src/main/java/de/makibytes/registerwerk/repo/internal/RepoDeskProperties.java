package de.makibytes.registerwerk.repo.internal;

import de.makibytes.registerwerk.shared.api.RepoDeskCapability;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "registerwerk.repo-desk")
public class RepoDeskProperties implements RepoDeskCapability {
    private boolean enabled;
    private boolean releaseApproved;
    /** Minimum time a borrower gets between a margin call and its deadline (T5-09 interim). */
    private int minMarginCureHours = 24;
    /** Time between a default notice and the earliest default declaration (T5-09 interim). */
    private int defaultGraceHours = 24;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isReleaseApproved() { return releaseApproved; }
    public void setReleaseApproved(boolean releaseApproved) { this.releaseApproved = releaseApproved; }
    public int getMinMarginCureHours() { return minMarginCureHours; }
    public void setMinMarginCureHours(int v) { this.minMarginCureHours = v; }
    public int getDefaultGraceHours() { return defaultGraceHours; }
    public void setDefaultGraceHours(int v) { this.defaultGraceHours = v; }
    public boolean isReleased() { return enabled && releaseApproved; }

    public void requireReleased() {
        if (!isReleased()) {
            throw new IllegalStateException("Repo Desk is not enabled and release-approved");
        }
    }
}
