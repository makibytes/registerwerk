package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.travelrule.api.Ivms101;
import de.makibytes.registerwerk.travelrule.api.TravelRuleGate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Default {@link TravelRuleGate} implementation delegating to {@link TravelRuleService}.
 *
 * <p>Enriches the IVMS-101 payload with the registered holder's identity
 * (legal name, LEI, country) resolved from the asset holder registry — TFR
 * Art. 14(1) requires the originator's name and registration identifier to
 * accompany the transfer, not merely the wallet address. The beneficiary side
 * is enriched when the wallet is also a registered holder (intra-registry
 * transfers); for external beneficiaries the counterpart CASP holds the
 * identity and only the account number is transmitted.
 */
@Component
class TravelRuleGateImpl implements TravelRuleGate {

    private final TravelRuleService service;
    private final HolderIdentityResolver identityResolver;

    TravelRuleGateImpl(TravelRuleService service, HolderIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @Override
    public void enforceOutbound(UUID assetId, String fromWallet, String toWallet, BigDecimal amountEur) {
        Ivms101.IdentityPayload originatorIdentity =
                identityResolver.resolve(assetId, fromWallet).orElse(null);
        Ivms101.IdentityPayload beneficiaryIdentity =
                identityResolver.resolve(assetId, toWallet).orElse(null);

        Ivms101.TravelRuleMessage payload = new Ivms101.TravelRuleMessage(
                null,
                List.of(new Ivms101.Originator(originatorIdentity, fromWallet)),
                null,
                List.of(new Ivms101.Beneficiary(beneficiaryIdentity, toWallet)),
                null);
        service.checkAndSend(assetId, fromWallet, toWallet, amountEur, payload);
    }
}
