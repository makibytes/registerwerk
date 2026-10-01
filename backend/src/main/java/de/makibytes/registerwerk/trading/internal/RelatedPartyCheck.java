package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.endpoint.api.AddressEndpoint;
import de.makibytes.registerwerk.endpoint.api.AddressEndpointRepository;
import de.makibytes.registerwerk.kyc.api.BeneficialOwner;
import de.makibytes.registerwerk.kyc.api.BeneficialOwnerRepository;
import de.makibytes.registerwerk.kyc.api.NaturalPerson;
import de.makibytes.registerwerk.kyc.api.NaturalPersonRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Wash-trade / self-dealing hook (Phase 5, 5A-06 / parked T5-04): are two trading entities linked?
 * Signals, all derived from data the registry already holds:
 * <ul>
 *   <li>{@code SHARED_BENEFICIAL_OWNER} - the same natural person (same record, same tax id, or the
 *       same name + date of birth) is a current beneficial owner of both;</li>
 *   <li>{@code SHARED_MEMBER} - one app user has a bound member wallet in both entities;</li>
 *   <li>{@code SHARED_WALLET} - a bound member wallet or address endpoint of one entity is the
 *       trade wallet / register wallet used on the other side, or both entities hold the same one.</li>
 * </ul>
 * There is no corporate-group model in the registry yet, so group membership beyond shared UBOs is
 * not detected; that limit is disclosed in the docs. The result is a control input, not a legal finding.
 */
@Component
class RelatedPartyCheck {

    static final String SHARED_BENEFICIAL_OWNER = "SHARED_BENEFICIAL_OWNER";
    static final String SHARED_MEMBER = "SHARED_MEMBER";
    static final String SHARED_WALLET = "SHARED_WALLET";

    private final BeneficialOwnerRepository beneficialOwnerRepository;
    private final NaturalPersonRepository naturalPersonRepository;
    private final OrgMemberWalletRepository memberWalletRepository;
    private final AddressEndpointRepository endpointRepository;

    RelatedPartyCheck(BeneficialOwnerRepository beneficialOwnerRepository,
                      NaturalPersonRepository naturalPersonRepository,
                      OrgMemberWalletRepository memberWalletRepository,
                      AddressEndpointRepository endpointRepository) {
        this.beneficialOwnerRepository = beneficialOwnerRepository;
        this.naturalPersonRepository = naturalPersonRepository;
        this.memberWalletRepository = memberWalletRepository;
        this.endpointRepository = endpointRepository;
    }

    /**
     * @param buyerWallet  the wallet the buyer will receive on (may be null)
     * @param sellerWallet the seller's register wallet (may be null)
     * @return reason codes; empty = unrelated
     */
    List<String> check(UUID buyerEntityId, UUID sellerEntityId, String buyerWallet, String sellerWallet) {
        Set<String> reasons = new java.util.LinkedHashSet<>();
        if (buyerEntityId == null || sellerEntityId == null) {
            return List.of();
        }
        if (buyerEntityId.equals(sellerEntityId)) {
            return List.of("SAME_ENTITY");
        }
        if (!java.util.Collections.disjoint(personKeys(buyerEntityId), personKeys(sellerEntityId))) {
            reasons.add(SHARED_BENEFICIAL_OWNER);
        }
        List<OrgMemberWallet> buyerMembers = memberWalletRepository.findActiveByLegalEntityId(buyerEntityId);
        List<OrgMemberWallet> sellerMembers = memberWalletRepository.findActiveByLegalEntityId(sellerEntityId);
        Set<UUID> buyerUsers = buyerMembers.stream().map(OrgMemberWallet::getAppUserId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (sellerMembers.stream().map(OrgMemberWallet::getAppUserId).filter(java.util.Objects::nonNull)
                .anyMatch(buyerUsers::contains)) {
            reasons.add(SHARED_MEMBER);
        }
        Set<String> buyerWallets = walletsOf(buyerEntityId, buyerMembers, buyerWallet);
        Set<String> sellerWallets = walletsOf(sellerEntityId, sellerMembers, sellerWallet);
        if (!java.util.Collections.disjoint(buyerWallets, sellerWallets)) {
            reasons.add(SHARED_WALLET);
        }
        return List.copyOf(reasons);
    }

    private Set<String> walletsOf(UUID entityId, List<OrgMemberWallet> members, String tradeWallet) {
        Set<String> wallets = new HashSet<>();
        members.forEach(w -> add(wallets, w.getWalletAddress()));
        endpointRepository.findByOwnerTypeAndOwnerId(AddressEndpoint.OwnerType.ENTITY, entityId)
                .forEach(e -> add(wallets, e.getAddress()));
        add(wallets, tradeWallet);
        return wallets;
    }

    private static void add(Set<String> set, String address) {
        if (address != null && !address.isBlank()) {
            set.add(address.trim().toLowerCase(Locale.ROOT));
        }
    }

    /** Identity keys of an entity's current beneficial owners (record id, tax id, name + DOB). */
    private Set<String> personKeys(UUID entityId) {
        Set<String> keys = new HashSet<>();
        for (BeneficialOwner bo : beneficialOwnerRepository.findByEntityIdAndCeasedAtIsNull(entityId)) {
            keys.add("id:" + bo.getNaturalPersonId());
            naturalPersonRepository.findById(bo.getNaturalPersonId()).filter(p -> !p.isRedacted())
                    .ifPresent(p -> addPersonKeys(keys, p));
        }
        return keys;
    }

    private static void addPersonKeys(Set<String> keys, NaturalPerson p) {
        if (p.getTaxId() != null && !p.getTaxId().isBlank()) {
            keys.add("tax:" + norm(p.getTaxIdCountry()) + ":" + norm(p.getTaxId()));
        }
        if (p.getGivenName() != null && p.getFamilyName() != null && p.getDateOfBirth() != null) {
            keys.add("nd:" + norm(p.getGivenName()) + ":" + norm(p.getFamilyName()) + ":" + p.getDateOfBirth());
        }
    }

    private static String norm(String v) {
        return v == null ? "" : v.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
