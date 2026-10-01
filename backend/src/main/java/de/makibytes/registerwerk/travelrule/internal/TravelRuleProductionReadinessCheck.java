package de.makibytes.registerwerk.travelrule.internal;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Prevents development-only Travel Rule transport from reaching production. */
@Component
class TravelRuleProductionReadinessCheck {

    private static final Logger log = LoggerFactory.getLogger(TravelRuleProductionReadinessCheck.class);

    private final TravelRuleProperties properties;
    private final String inboxApiKey;
    private final boolean productionMode;

    TravelRuleProductionReadinessCheck(
            TravelRuleProperties properties,
            @Value("${registerwerk.travel-rule.inbox-api-key:}") String inboxApiKey,
            @Value("${REGISTERWERK_PRODUCTION_MODE:false}") boolean productionMode) {
        this.properties = properties;
        this.inboxApiKey = inboxApiKey;
        this.productionMode = productionMode;
    }

    @PostConstruct
    void check() {
        String protocol = properties.getProtocol() == null
                ? "NOOP"
                : properties.getProtocol().trim().toUpperCase(Locale.ROOT);
        if (!productionMode) {
            if ("NOOP".equals(protocol)) {
                log.warn("Travel Rule protocol is NOOP; this is allowed only outside production");
            }
            return;
        }

        TravelRuleProperties.OwnVasp own = properties.getOwnVasp();
        if (own.identifier() == null || isBlank(own.getLegalName())) {
            throw new IllegalStateException("REGISTERWERK_TRAVEL_RULE_OWN_VASP_DID (or _LEI) and "
                    + "_OWN_VASP_LEGAL_NAME must be set in production mode (originatingVasp of every message)");
        }
        if (properties.getTrp().isAllowInsecureEndpoints()) {
            throw new IllegalStateException("travel-rule.trp.allow-insecure-endpoints must not be set in production");
        }
        if (properties.isLegacySharedKey()) {
            throw new IllegalStateException("REGISTERWERK_TRAVEL_RULE_LEGACY_SHARED_KEY must not be enabled in production");
        }
        switch (protocol) {
            case "NOTABENE" -> {
                TravelRuleProperties.Notabene config = properties.getNotabene();
                if (!config.isConfigured() || isBlank(config.getVaspDid())) {
                    throw new IllegalStateException(
                            "NOTABENE Travel Rule transport requires an API key and VASP DID in production");
                }
            }
            case "TRP" -> {
                TravelRuleProperties.Trp config = properties.getTrp();
                if (!config.isConfigured()
                        || isBlank(config.getMtlsCertPath())
                        || isBlank(config.getMtlsKeyPath())) {
                    throw new IllegalStateException(
                            "TRP Travel Rule transport requires an endpoint and mTLS certificate/key in production");
                }
            }
            default -> throw new IllegalStateException(
                    "REGISTERWERK_TRAVEL_RULE_PROTOCOL must be TRP or NOTABENE in production mode");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
