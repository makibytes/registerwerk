package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.travelrule.api.Ivms101;
import de.makibytes.registerwerk.travelrule.api.TravelRuleProtocolPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * No-op adapter: logs a warning and returns a stub message ID.
 * Replace with TrpAdapter / NotabeneAdapter / SygnaAdapter in production by
 * setting registerwerk.travel-rule.protocol=TRP|NOTABENE|SYGNA.
 */
@Component
@ConditionalOnMissingBean(name = {"trpAdapter", "notabeneAdapter", "sygnaAdapter"})
class NoopTravelRuleProtocolAdapter implements TravelRuleProtocolPort {

    private static final Logger log = LoggerFactory.getLogger(NoopTravelRuleProtocolAdapter.class);

    @Override
    public String protocolName() { return "NOOP"; }

    @Override
    public CompletableFuture<String> send(UUID transferId, Ivms101.TravelRuleMessage payload) {
        log.warn("Travel Rule: no protocol adapter configured — message NOT sent for transferId={}. " +
                 "Configure registerwerk.travel-rule.protocol.", transferId);
        return CompletableFuture.completedFuture("noop-" + transferId);
    }

    @Override
    public Optional<VaspInfo> lookupVasp(String walletAddress) {
        return Optional.empty();
    }
}
