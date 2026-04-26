package de.makibytes.registerwerk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Auto-refresh/sync configuration for token holder data. */
@Configuration
@ConfigurationProperties(prefix = "registerwerk.sync")
public class SyncConfig {

    private int autoRefreshIntervalMinutes = 0;
    private boolean syncOnStartup = false;
    private int batchSize = 50;
    private boolean logSyncOperations = true;

    public int getAutoRefreshIntervalMinutes() { return autoRefreshIntervalMinutes; }
    public void setAutoRefreshIntervalMinutes(int v) { this.autoRefreshIntervalMinutes = v; }

    public boolean isSyncOnStartup() { return syncOnStartup; }
    public void setSyncOnStartup(boolean v) { this.syncOnStartup = v; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int v) { this.batchSize = v; }

    public boolean isLogSyncOperations() { return logSyncOperations; }
    public void setLogSyncOperations(boolean v) { this.logSyncOperations = v; }

    public boolean isAutoRefreshEnabled() { return this.autoRefreshIntervalMinutes > 0; }
}
