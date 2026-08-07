package de.makibytes.registerwerk.dora.web;

import de.makibytes.registerwerk.dora.api.IctIncident;
import de.makibytes.registerwerk.dora.api.ThirdPartyProvider;
import de.makibytes.registerwerk.dora.internal.DoraService;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DoraController authority-template export unit tests (Track 7-1)")
class DoraControllerTest {

    @Mock private DoraService doraService;

    private DoraController controller;

    private DoraController controller() {
        return controller != null ? controller : (controller = new DoraController(doraService));
    }

    @Test
    @DisplayName("exportIncidentAuthorityReport renders a key/value CSV covering the DORA Art. 19 fields")
    void exportIncidentAuthorityReport_rendersCsv() {
        UUID incidentId = UUID.randomUUID();
        IctIncident incident = new IctIncident();
        ReflectionTestUtils.setField(incident, "id", incidentId);
        incident.setCategory(IctIncident.Category.RANSOMWARE);
        incident.setSeverity(IctIncident.Severity.MAJOR);
        incident.setStatus(IctIncident.Status.INVESTIGATING);
        incident.setTitle("Ransomware on backup host");
        incident.setDetectedAt(Instant.parse("2026-08-01T10:00:00Z"));
        incident.setClassificationDeadline(Instant.parse("2026-08-01T14:00:00Z"));
        when(doraService.getIncident(incidentId)).thenReturn(incident);

        ResponseEntity<String> response = controller().exportIncidentAuthorityReport(incidentId);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        String csv = response.getBody();
        assertThat(csv).contains("Incident reference", incidentId.toString(), "RANSOMWARE", "MAJOR",
                "Ransomware on backup host", "2026-08-01T14:00:00Z");
    }

    @Test
    @DisplayName("exportIncidentAuthorityReport propagates not-found for an unknown incident")
    void exportIncidentAuthorityReport_unknownIncident_throws() {
        UUID incidentId = UUID.randomUUID();
        when(doraService.getIncident(incidentId)).thenThrow(new EntityNotFoundException("IctIncident", incidentId));

        assertThatThrownBy(() -> controller().exportIncidentAuthorityReport(incidentId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("exportProviderRegister renders one CSV row per provider with Y/N flags")
    void exportProviderRegister_rendersCsv() {
        ThirdPartyProvider provider = new ThirdPartyProvider();
        provider.setName("CloudCo AG");
        provider.setCategory("Cloud hosting");
        provider.setCriticality(ThirdPartyProvider.Criticality.CRITICAL);
        provider.setLei("529900T8BM49AURSDO55");
        provider.setCountry("DE");
        provider.setContractStart(LocalDate.of(2024, 1, 1));
        provider.setContractEnd(LocalDate.of(2027, 1, 1));
        provider.setSubOutsourcing(true);
        provider.setSubOutsourcingDetails("Uses a sub-processor for backups");
        provider.setSlaAvailabilityPct(new BigDecimal("99.95"));
        provider.setRtoHours(4);
        provider.setRpoHours(1);
        provider.setNotifiedAuthority(true);
        when(doraService.listProviders()).thenReturn(List.of(provider));

        ResponseEntity<String> response = controller().exportProviderRegister();

        String csv = response.getBody();
        assertThat(csv).contains("CloudCo AG", "CRITICAL", "529900T8BM49AURSDO55", "DE", "99.95");
        // subOutsourcing=true and notifiedAuthority=true both render as the literal "Y" flag.
        assertThat(csv).contains(",Y,");
    }

    @Test
    @DisplayName("exportProviderRegister renders an empty body for an unset register")
    void exportProviderRegister_noProviders_headerOnly() {
        when(doraService.listProviders()).thenReturn(List.of());

        ResponseEntity<String> response = controller().exportProviderRegister();

        assertThat(response.getBody()).contains("name,category,criticality");
    }
}
