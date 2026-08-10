package de.makibytes.registerwerk.repo.internal;

import de.makibytes.registerwerk.repo.api.RepoDeskCapability;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "registerwerk.repo-desk")
public class RepoDeskProperties implements RepoDeskCapability {
    private boolean enabled;
    private boolean releaseApproved;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isReleaseApproved() { return releaseApproved; }
    public void setReleaseApproved(boolean releaseApproved) { this.releaseApproved = releaseApproved; }
    public boolean isReleased() { return enabled && releaseApproved; }

    public void requireReleased() {
        if (!isReleased()) {
            throw new IllegalStateException("Repo Desk is not enabled and release-approved");
        }
    }
}
