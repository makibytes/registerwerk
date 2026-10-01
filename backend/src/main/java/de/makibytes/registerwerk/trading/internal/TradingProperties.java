package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.api.TradingVenueCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
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

    /**
     * 5A-01: the DEMO instant path. When {@code true}, a SIMULATED listing whose seller ticked
     * "allow instant settlement" moves the register inside the buyer's request with NO cash leg
     * (and no chain-deployed asset). Default {@code false}; refused at start-up in production mode.
     */
    private boolean demoInstantSettlement = false;

    /** 5A-06: open (PENDING / AWAITING / PAYMENT_UNRESOLVED) reservations one buyer may hold at once. */
    private int maxOpenReservationsPerBuyer = 3;

    /** 5A-06: hours a buyer is barred from re-reserving a listing after cancelling / letting a reservation lapse. */
    private long reservationCooldownHours = 24;

    /** 5A-03: hours after which a PAYMENT_UNRESOLVED trade is counted as "aged" (gauge + alert log). */
    private long unresolvedAlertHours = 24;

    /**
     * 5C-06 interim (parked T5-06): how the operator classifies peer listings. {@code DEMO_ONLY} (default)
     * = a demonstration workflow, not an authorised trading venue: refused in production mode.
     * {@code BILATERAL_ONLY} = listings must be addressed to one named counterparty ({@code targetEntityId}).
     * {@code LICENSED_VENUE} = the operator holds the authorisation. Both non-demo values need
     * {@link #legalOpinionRef}. The code cannot decide the legal question; it only refuses to run
     * un-classified in production.
     */
    private VenueClassification venueClassification = VenueClassification.DEMO_ONLY;

    /** Reference of the legal opinion supporting {@link #venueClassification} (mandatory unless DEMO_ONLY). */
    private String legalOpinionRef = "";

    /** True when {@code REGISTERWERK_PRODUCTION_MODE=true} (same switch the readiness checks use). */
    private boolean productionMode = "true".equalsIgnoreCase(System.getenv("REGISTERWERK_PRODUCTION_MODE"));

    /** 5A-06: buyer and seller linked by a shared beneficial owner / member / wallet may trade only if {@code true}. */
    private boolean allowRelatedPartyTrades = false;

    /** 5A-06 (T5-04): refuse listings/trades deviating from the last unrelated price by more than this many bps; 0 = off. */
    private int maxPriceDeviationBps = 0;

    /** 5A-02 (T5-03): ISO 4217 currencies accepted for fiat payment options (SEPA / CBMT / PONTES). */
    private List<String> fiatCurrencies = List.of("EUR");

    public enum VenueClassification { DEMO_ONLY, BILATERAL_ONLY, LICENSED_VENUE }

    public VenueClassification getVenueClassification() {
        return venueClassification;
    }

    public void setVenueClassification(VenueClassification venueClassification) {
        this.venueClassification = venueClassification != null ? venueClassification : VenueClassification.DEMO_ONLY;
    }

    public String getLegalOpinionRef() {
        return legalOpinionRef;
    }

    public void setLegalOpinionRef(String legalOpinionRef) {
        this.legalOpinionRef = legalOpinionRef;
    }

    public boolean isProductionMode() {
        return productionMode;
    }

    public void setProductionMode(boolean productionMode) {
        this.productionMode = productionMode;
    }

    public boolean isAllowRelatedPartyTrades() {
        return allowRelatedPartyTrades;
    }

    public void setAllowRelatedPartyTrades(boolean allowRelatedPartyTrades) {
        this.allowRelatedPartyTrades = allowRelatedPartyTrades;
    }

    public int getMaxPriceDeviationBps() {
        return maxPriceDeviationBps;
    }

    public void setMaxPriceDeviationBps(int maxPriceDeviationBps) {
        this.maxPriceDeviationBps = maxPriceDeviationBps;
    }

    public List<String> getFiatCurrencies() {
        return fiatCurrencies;
    }

    public void setFiatCurrencies(List<String> fiatCurrencies) {
        this.fiatCurrencies = fiatCurrencies;
    }

    public boolean isDemoInstantSettlement() {
        return demoInstantSettlement;
    }

    public void setDemoInstantSettlement(boolean demoInstantSettlement) {
        this.demoInstantSettlement = demoInstantSettlement;
    }

    public int getMaxOpenReservationsPerBuyer() {
        return maxOpenReservationsPerBuyer;
    }

    public void setMaxOpenReservationsPerBuyer(int maxOpenReservationsPerBuyer) {
        this.maxOpenReservationsPerBuyer = maxOpenReservationsPerBuyer;
    }

    public long getReservationCooldownHours() {
        return reservationCooldownHours;
    }

    public void setReservationCooldownHours(long reservationCooldownHours) {
        this.reservationCooldownHours = reservationCooldownHours;
    }

    public long getUnresolvedAlertHours() {
        return unresolvedAlertHours;
    }

    public void setUnresolvedAlertHours(long unresolvedAlertHours) {
        this.unresolvedAlertHours = unresolvedAlertHours;
    }

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
