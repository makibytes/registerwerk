package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.internal.TradingVenueCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "registerwerk.trading")
public class TradingProperties {

    private boolean enabled = true;
    private Map<TradingVenueCode, VenueProperties> venues = new EnumMap<>(TradingVenueCode.class);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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
