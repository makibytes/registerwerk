package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.api.TradingVenueCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "registerwerk.trading")
public class TradingProperties {

    private boolean enabled = true;
    private Map<TradingVenueCode, VenueProperties> venues = new EnumMap<>(TradingVenueCode.class);

    /** Hours a trade may sit PENDING before {@code TradingService.timeoutStuckPendingTrades}
     *  marks it FAILED. Previously there was no timeout at all — a stuck PENDING trade stayed
     *  PENDING forever with no signal that it needed attention. */
    private long pendingTimeoutHours = 72;

    /**
     * T3-09 interim guard: whether the simulated venue may list and settle an asset that has a
     * CONFIRMED chain deployment. Settlement only rewrites the register rows (there is no on-chain
     * leg yet — Phase 5), and the holder sync then resets the seller to its on-chain balance, so
     * the register would exceed the supply. Default {@code false}; the demo stack turns it on and
     * the trading desk then shows a "simulated settlement" notice.
     */
    private boolean offchainSettlementOnDeployedAssets = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getPendingTimeoutHours() {
        return pendingTimeoutHours;
    }

    public void setPendingTimeoutHours(long pendingTimeoutHours) {
        this.pendingTimeoutHours = pendingTimeoutHours;
    }

    public boolean isOffchainSettlementOnDeployedAssets() {
        return offchainSettlementOnDeployedAssets;
    }

    public void setOffchainSettlementOnDeployedAssets(boolean offchainSettlementOnDeployedAssets) {
        this.offchainSettlementOnDeployedAssets = offchainSettlementOnDeployedAssets;
    }

    public Map<TradingVenueCode, VenueProperties> getVenues() {
        return venues;
    }

    public void setVenues(Map<TradingVenueCode, VenueProperties> venues) {
        this.venues = venues;
    }

    public VenueProperties venue(TradingVenueCode code) {
        return venues.computeIfAbsent(code, ignored -> new VenueProperties());
    }

    public static class VenueProperties {
        private boolean enabled = true;
        private String baseUrl;
        private String apiKey;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public boolean isConfigured() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }
}
