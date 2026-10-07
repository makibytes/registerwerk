package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.endpoint.api.AddressEndpointRepository;
import de.makibytes.registerwerk.kyc.api.BeneficialOwner;
import de.makibytes.registerwerk.kyc.api.BeneficialOwnerRepository;
import de.makibytes.registerwerk.kyc.api.NaturalPerson;
import de.makibytes.registerwerk.kyc.api.NaturalPersonRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelatedPartyCheckTest {

    private final BeneficialOwnerRepository owners = mock(BeneficialOwnerRepository.class);
    private final NaturalPersonRepository persons = mock(NaturalPersonRepository.class);
    private final OrgMemberWalletRepository members = mock(OrgMemberWalletRepository.class);
    private final AddressEndpointRepository endpoints = mock(AddressEndpointRepository.class);
    private final RelatedPartyCheck check = new RelatedPartyCheck(owners, persons, members, endpoints);
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    private BeneficialOwner owner(UUID personId) {
        BeneficialOwner bo = mock(BeneficialOwner.class);
        when(bo.getNaturalPersonId()).thenReturn(personId);
        return bo;
    }

    @Test
    void commonUboByTaxIdLinksTwoEntitiesEvenWithDifferentPersonRecords() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        BeneficialOwner o1 = owner(p1);
        BeneficialOwner o2 = owner(p2);
        when(owners.findByEntityIdAndCeasedAtIsNull(a)).thenReturn(List.of(o1));
        when(owners.findByEntityIdAndCeasedAtIsNull(b)).thenReturn(List.of(o2));
        for (UUID p : List.of(p1, p2)) {
            NaturalPerson np = mock(NaturalPerson.class);
            when(np.getTaxId()).thenReturn("12 345");
            when(np.getTaxIdCountry()).thenReturn("DE");
            when(persons.findById(p)).thenReturn(Optional.of(np));
        }
        assertThat(check.check(a, b, null, null)).containsExactly(RelatedPartyCheck.SHARED_BENEFICIAL_OWNER);
    }

    @Test
    void sharedMemberAndWalletAreDetected() {
        UUID user = UUID.randomUUID();
        OrgMemberWallet wa = new OrgMemberWallet();
        wa.setAppUserId(user);
        wa.setWalletAddress("0xAbC");
        OrgMemberWallet wb = new OrgMemberWallet();
        wb.setAppUserId(user);
        wb.setWalletAddress("0xdef");
        when(members.findActiveByLegalEntityId(a)).thenReturn(List.of(wa));
        when(members.findActiveByLegalEntityId(b)).thenReturn(List.of(wb));
        assertThat(check.check(a, b, null, "0xABC")).containsExactly(RelatedPartyCheck.SHARED_MEMBER, RelatedPartyCheck.SHARED_WALLET);
    }

    @Test
    void unrelatedEntitiesYieldNothing() {
        assertThat(check.check(a, b, "0x1", "0x2")).isEmpty();
    }
}
