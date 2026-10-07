package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.customer.api.ClientCategory;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.kyc.api.PartyEligibilityGate;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LenderEligibilityService (T2-20 / T5-13)")
class LenderEligibilityServiceTest {

    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final PartyEligibilityGate gate = mock(PartyEligibilityGate.class);
    private final UUID entityId = UUID.randomUUID();

    private LenderEligibilityService service(boolean production) {
        MockEnvironment env = new MockEnvironment();
        if (production) env.setProperty("registerwerk.production-mode", "true");
        return new LenderEligibilityService(entities, gate, env);
    }

    private void entity(ClientCategory category) {
        LegalEntity e = new LegalEntity();
        e.setClientCategory(category);
        when(entities.findById(entityId)).thenReturn(Optional.of(e));
    }

    @Test
    @DisplayName("production: an entity that is not APPROVED / not ACTIVE is refused as a lender")
    void productionRefusesUnapproved() {
        entity(ClientCategory.PROFESSIONAL);
        when(gate.check(any(), any())).thenReturn(List.of("KYC is not APPROVED (status PENDING)"));
        assertThatThrownBy(() -> service(true).requireLender(entityId, "supplying to a lending market"))
                .isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("KYC is not APPROVED");
    }

    @Test
    @DisplayName("production: an entity with an unresolved screening hit is refused as a lender")
    void productionRefusesUnscreened() {
        entity(ClientCategory.ELIGIBLE_COUNTERPARTY);
        when(gate.check(any(), any())).thenReturn(List.of("has an unresolved sanctions-screening result"));
        assertThatThrownBy(() -> service(true).requireLender(entityId, "withdrawing"))
                .isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("sanctions-screening");
    }

    @Test
    @DisplayName("production: RETAIL, unclassified and unknown entities are refused as lenders")
    void productionRefusesRetailUnclassifiedUnknown() {
        when(gate.check(any(), any())).thenReturn(List.of());
        entity(ClientCategory.RETAIL);
        assertThatThrownBy(() -> service(true).requireLender(entityId, "supplying"))
                .isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("PROFESSIONAL");
        entity(null);
        assertThatThrownBy(() -> service(true).requireLender(entityId, "supplying"))
                .isInstanceOf(ComplianceGateException.class);
        UUID unknown = UUID.randomUUID();
        when(entities.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(true).requireLender(unknown, "supplying"))
                .isInstanceOf(ComplianceGateException.class);
    }

    @Test
    @DisplayName("production: an approved, screened PROFESSIONAL or ELIGIBLE_COUNTERPARTY entity is admitted")
    void productionAdmitsEligible() {
        when(gate.check(any(), any())).thenReturn(List.of());
        entity(ClientCategory.PROFESSIONAL);
        assertThatCode(() -> service(true).requireLender(entityId, "supplying")).doesNotThrowAnyException();
        entity(ClientCategory.ELIGIBLE_COUNTERPARTY);
        assertThatCode(() -> service(true).requireLender(entityId, "supplying")).doesNotThrowAnyException();
        assertThat(service(true).status(entityId).eligible()).isTrue();
        assertThat(service(true).status(entityId).productionMode()).isTrue();
    }

    @Test
    @DisplayName("production: status() lists every reason instead of throwing")
    void productionStatusListsReasons() {
        entity(ClientCategory.RETAIL);
        when(gate.check(any(), any())).thenReturn(List.of("KYC is not APPROVED"));
        var status = service(true).status(entityId);
        assertThat(status.eligible()).isFalse();
        assertThat(status.reasons()).hasSize(2);
    }

    @Test
    @DisplayName("demo mode: no gate is applied - retail / unapproved entities may still use Supply & Earn")
    void demoModeUnchanged() {
        entity(ClientCategory.RETAIL);
        when(gate.check(any(), any())).thenReturn(List.of("KYC is not APPROVED"));
        assertThatCode(() -> service(false).requireLender(entityId, "supplying")).doesNotThrowAnyException();
        var status = service(false).status(entityId);
        assertThat(status.productionMode()).isFalse();
        assertThat(status.eligible()).isTrue();
        verify(gate, never()).check(any(), any());
    }

    @Test
    @DisplayName("production: an operator token without a customer entity is not a lender")
    void productionNullEntityRefused() {
        assertThatThrownBy(() -> service(true).requireLender(null, "supplying"))
                .isInstanceOf(ComplianceGateException.class);
    }
}
