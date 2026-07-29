package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.travelrule.api.CaspAuthorizationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CaspRegisterImportService CSV import unit tests")
class CaspRegisterImportServiceTest {

    @Mock
    private CaspRegistryService registryService;

    @Mock
    private CaspAuthorizationRepository repository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private CaspRegisterImportService importService;

    @Test
    @DisplayName("imports a semicolon CSV with ESMA-style 'Authorised' spelling")
    void importsEsmaStyleCsv() {
        when(repository.findByVaspDidIgnoreCase(anyString())).thenReturn(Optional.empty());
        String csv = """
            legal_name;vasp_did;lei;home_member_state;status;valid_from
            Beispiel CASP GmbH;did:example:casp1;529900T8BM49AURSDO55;de;Authorised;2026-01-15
            """;

        var result = importService.importCsv(csv, "test", UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        ArgumentCaptor<CaspAuthorization> captor = ArgumentCaptor.forClass(CaspAuthorization.class);
        verify(registryService).upsert(captor.capture());
        CaspAuthorization entry = captor.getValue();
        assertThat(entry.getStatus()).isEqualTo(CaspAuthorizationStatus.AUTHORIZED);
        assertThat(entry.getHomeMemberState()).isEqualTo("DE");
        assertThat(entry.getValidFrom()).hasToString("2026-01-15");
    }

    @Test
    @DisplayName("synthesizes lei:<LEI> identifier when vasp_did is missing")
    void synthesizesLeiIdentifier() {
        when(repository.findByVaspDidIgnoreCase(anyString())).thenReturn(Optional.empty());
        String csv = """
            legal_name,lei,status
            Other CASP S.A.,724500A4FBF8B1FE7G53,Withdrawn
            """;

        var result = importService.importCsv(csv, "test", UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(result.created()).isEqualTo(1);
        ArgumentCaptor<CaspAuthorization> captor = ArgumentCaptor.forClass(CaspAuthorization.class);
        verify(registryService).upsert(captor.capture());
        assertThat(captor.getValue().getVaspDid()).isEqualTo("lei:724500A4FBF8B1FE7G53");
        assertThat(captor.getValue().getStatus()).isEqualTo(CaspAuthorizationStatus.REVOKED);
    }

    @Test
    @DisplayName("bad rows are reported per line and do not block good rows")
    void badRowsReportedGoodRowsProceed() {
        when(repository.findByVaspDidIgnoreCase(anyString())).thenReturn(Optional.empty());
        String csv = """
            legal_name;vasp_did;status
            Good CASP;did:example:good;Transitional
            ;did:example:noname;Authorised
            Bad Status CASP;did:example:bad;Banana
            """;

        var result = importService.importCsv(csv, "test", UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.errors()).hasSize(2);
        assertThat(result.errors().get(0)).contains("Line 3");
        assertThat(result.errors().get(1)).contains("Banana");
    }

    @Test
    @DisplayName("existing vaspDid counts as updated, not created")
    void existingEntry_countsAsUpdated() {
        when(repository.findByVaspDidIgnoreCase("did:example:casp1"))
                .thenReturn(Optional.of(new CaspAuthorization()));
        String csv = """
            legal_name;vasp_did;status
            Beispiel CASP GmbH;did:example:casp1;Authorised
            """;

        var result = importService.importCsv(csv, "test", UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.created()).isZero();
    }

    @Test
    @DisplayName("missing required header columns aborts the import")
    void missingHeader_aborts() {
        var result = importService.importCsv("name;state\nFoo;DE\n", "test", UUID.randomUUID(), "REGISTRY_ADMIN");
        assertThat(result.created()).isZero();
        assertThat(result.errors().get(0)).contains("Missing required columns");
        verify(registryService, never()).upsert(any());
    }

    @Test
    @DisplayName("quoted fields with embedded delimiters parse correctly")
    void quotedFieldsParse() {
        assertThat(CaspRegisterImportService.splitCsvLine(
                "\"Müller, Schmidt & Co. CASP\";did:x;Authorised", ';'))
                .containsExactly("Müller, Schmidt & Co. CASP", "did:x", "Authorised");
    }

    @Test
    @DisplayName("unknown status values are rejected, not guessed")
    void unknownStatus_rejected() {
        assertThatThrownBy(() -> CaspRegisterImportService.mapStatus("MAYBE"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
