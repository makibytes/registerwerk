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

    private CaspRegistryService serviceAt(LocalDate today) {
        Clock fixed = Clock.fixed(
                Instant.parse(today + "T12:00:00Z"), ZoneOffset.UTC);
        CaspRegistryService service = new CaspRegistryService(repository, fixed, eventPublisher);
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
    @DisplayName("unknown counterparty is permitted with a warning (non-EU VASPs are out of MiCA scope)")
    void unknownCounterparty_permitted() {
        when(repository.findByVaspDidIgnoreCase(anyString())).thenReturn(Optional.empty());
        assertThatCode(() -> serviceAt(LocalDate.of(2026, 8, 1))
                .assertCounterpartyPermitted("did:example:unknown"))
                .doesNotThrowAnyException();
    }
}
