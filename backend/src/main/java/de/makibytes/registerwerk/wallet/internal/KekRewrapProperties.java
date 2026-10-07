package de.makibytes.registerwerk.wallet.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** {@code registerwerk.kek.rewrap.*}: the nightly job that re-wraps every envelope secret onto the active KEK version. */
@Component
@ConfigurationProperties(prefix = "registerwerk.kek.rewrap")
public class KekRewrapProperties {

    /** Re-wrap automatically after a rotation. The inventory gauge keeps running when this is off. */
    private boolean enabled = true;
    private String cron = "0 30 3 * * *";
    /** Rows read per page; each row is its own transaction, so a failure never rolls back a neighbour. */
    private int batchSize = 100;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = Math.max(1, batchSize); }
}
