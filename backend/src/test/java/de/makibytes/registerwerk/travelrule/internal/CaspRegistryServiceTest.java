package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.travelrule.api.CaspAuthorizationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifies the MiCA transitional-period cutoff (Reg (EU) 2023/1114):
 * TRANSITIONAL counterparties are permitted before 1 July 2026 and blocked
 * from that date; NOT_AUTHORIZED/REVOKED are always blocked; expired
 * authorizations are blocked; unknown counterparties pass with a warning.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CaspRegistryService MiCA cutoff unit tests")
class CaspRegistryServiceTest {

    private static final LocalDate CUTOFF = LocalDate.of(2026, 7, 1);

    @Mock
    private CaspAuthorizationRepository repository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private CaspRegistryService serviceAt(LocalDate today) {
        Clock fixed = Clock.fixed(
                Instant.parse(today + "T12:00:00Z"), ZoneOffset.UTC);
        CaspRegistryService service = new CaspRegistryService(repository, fixed, eventPublisher, jdbc);
        ReflectionTestUtils.setField(service, "micaEnforcementDate", CUTOFF);
        return service;
    }

    private void entryWithStatus(CaspAuthorizationStatus status, LocalDate validUntil) {
        CaspAuthorization casp = new CaspAuthorization();
        casp.setVaspDid("did:example:counterparty");
        casp.setLegalName("Counterparty CASP GmbH");
        casp.setStatus(status);
        casp.setValidUntil(validUntil);
        when(repository.findByVaspDidIgnoreCase(anyString())).thenReturn(Optional.of(casp));
    }

    @Test
    @DisplayName("TRANSITIONAL counterparty is permitted before the cutoff")
    void transitional_beforeCutoff_permitted() {
        entryWithStatus(CaspAuthorizationStatus.TRANSITIONAL, null);
        assertThatCode(() -> serviceAt(LocalDate.of(2026, 6, 10))
                .assertCounterpartyPermitted("did:example:counterparty"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TRANSITIONAL counterparty is blocked on the cutoff date — no grandfathering")
    void transitional_onCutoff_blocked() {
        entryWithStatus(CaspAuthorizationStatus.TRANSITIONAL, null);
        assertThatThrownBy(() -> serviceAt(CUTOFF)
                .assertCounterpartyPermitted("did:example:counterparty"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transitional");
    }

    @Test
    @DisplayName("NOT_AUTHORIZED counterparty is blocked even before the cutoff")
    void notAuthorized_alwaysBlocked() {
        entryWithStatus(CaspAuthorizationStatus.NOT_AUTHORIZED, null);
        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 1, 15))
                .assertCounterpartyPermitted("did:example:counterparty"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not authorized");
    }

    @Test
    @DisplayName("AUTHORIZED counterparty with expired validity is blocked")
    void authorized_expired_blocked() {
        entryWithStatus(CaspAuthorizationStatus.AUTHORIZED, LocalDate.of(2026, 5, 31));
        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 6, 10))
                .assertCounterpartyPermitted("did:example:counterparty"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("AUTHORIZED counterparty with valid authorization is permitted after the cutoff")
    void authorized_valid_permitted() {
        entryWithStatus(CaspAuthorizationStatus.AUTHORIZED, null);
        assertThatCode(() -> serviceAt(LocalDate.of(2026, 8, 1))
                .assertCounterpartyPermitted("did:example:counterparty"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("unknown counterparty is permitted only before the enforcement date")
    void unknownCounterparty_permittedBeforeCutoffOnly() {
        assertThatCode(() -> serviceAt(LocalDate.of(2026, 6, 1))
                .assertCounterpartyPermitted("did:example:unknown"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("unknown counterparty is blocked after the enforcement date (previously waved through)")
    void unknownCounterparty_blockedAfterCutoff() {
        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 8, 1))
                .assertCounterpartyPermitted("did:example:unknown"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in the CASP authorization register");
    }

    @Test
    @DisplayName("ESMA LEI-only REVOKED row is found through the provider's LEI and blocks (previously permitted: DID lookup missed)")
    void leiOnlyRevokedRow_blocksProviderDid() {
        CaspAuthorization casp = new CaspAuthorization();
        casp.setVaspDid("lei:529900T8BM49AURSDO55");
        casp.setLei("529900T8BM49AURSDO55");
        casp.setLegalName("Revoked CASP AG");
        casp.setStatus(CaspAuthorizationStatus.REVOKED);
        when(repository.findByVaspDidIgnoreCase("did:web:revoked.example")).thenReturn(Optional.empty());
        when(repository.findByLeiIgnoreCase("529900T8BM49AURSDO55")).thenReturn(Optional.of(casp));

        assertThatThrownBy(() -> serviceAt(LocalDate.of(2026, 8, 1)).assertCounterpartyPermitted(
                new de.makibytes.registerwerk.travelrule.api.TravelRuleProtocolPort.VaspInfo(
                        "did:web:revoked.example", "Revoked CASP AG", "DE", null, "529900T8BM49AURSDO55")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not authorized");
        // provider DID is remembered on the LEI row
        org.mockito.Mockito.verify(repository).save(casp);
    }

    @Test
    @DisplayName("third-country row needs reviewer, second approver and an unexpired review")
    void thirdCountryReview_requiresFourEyesAndExpiry() {
        CaspAuthorization row = new CaspAuthorization();
        row.setVaspDid("did:example:swiss");
        row.setLegalName("Swiss VASP");
        row.setStatus(CaspAuthorizationStatus.THIRD_COUNTRY_REVIEWED);
        row.setValidUntil(LocalDate.of(2027, 1, 1));
        CaspRegistryService service = serviceAt(LocalDate.of(2026, 8, 1));

        assertThatThrownBy(() -> service.upsert(row, java.util.UUID.randomUUID(), "COMPLIANCE_OFFICER", null))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        when(repository.findByVaspDidIgnoreCase("did:example:swiss")).thenReturn(Optional.of(row));
        assertThatCode(() -> serviceAt(LocalDate.of(2026, 8, 1)).assertCounterpartyPermitted("did:example:swiss"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> serviceAt(LocalDate.of(2027, 2, 1)).assertCounterpartyPermitted("did:example:swiss"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("lifting a REVOKED status needs a REGISTRY_ADMIN second approver; deleting a blocking row likewise")
    void liftingBlock_needsAdminApprover() {
        java.util.UUID approver = java.util.UUID.randomUUID();
        CaspAuthorization stored = new CaspAuthorization();
        org.springframework.test.util.ReflectionTestUtils.setField(stored, "id", java.util.UUID.randomUUID());
        stored.setVaspDid("did:example:x");
        stored.setLegalName("X");
        stored.setStatus(CaspAuthorizationStatus.REVOKED);
        when(repository.findByVaspDidIgnoreCase("did:example:x")).thenReturn(Optional.of(stored));
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class), any(Object[].class)))
                .thenReturn(0);
        CaspAuthorization incoming = new CaspAuthorization();
        incoming.setVaspDid("did:example:x");
        incoming.setLegalName("X");
        incoming.setStatus(CaspAuthorizationStatus.AUTHORIZED);
        CaspRegistryService service = serviceAt(LocalDate.of(2026, 8, 1));

        assertThatThrownBy(() -> service.upsert(incoming, java.util.UUID.randomUUID(), "COMPLIANCE_OFFICER", approver))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> service.delete(stored.getId(), java.util.UUID.randomUUID(), "COMPLIANCE_OFFICER", approver))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
