package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.travelrule.api.CaspAuthorizationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
    private CaspRegisterImportWriter writer;

    private CaspRegisterImportService importService;

    @BeforeEach
    void setUp() {
        importService = new CaspRegisterImportService(writer);
    }

    @Test
    @DisplayName("imports a semicolon CSV with ESMA-style 'Authorised' spelling")
    void importsEsmaStyleCsv() {
        when(writer.upsert(any(), any(), anyString())).thenReturn(false);
        String csv = """
            legal_name;vasp_did;lei;home_member_state;status;valid_from
            Beispiel CASP GmbH;did:example:casp1;529900T8BM49AURSDO55;de;Authorised;2026-01-15
            """;

        var result = importService.importCsv(csv, "test", UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        ArgumentCaptor<CaspAuthorization> captor = ArgumentCaptor.forClass(CaspAuthorization.class);
        verify(writer).upsert(captor.capture(), any(), anyString());
        CaspAuthorization entry = captor.getValue();
        assertThat(entry.getStatus()).isEqualTo(CaspAuthorizationStatus.AUTHORIZED);
        assertThat(entry.getHomeMemberState()).isEqualTo("DE");
        assertThat(entry.getValidFrom()).hasToString("2026-01-15");
    }

    @Test
    @DisplayName("synthesizes lei:<LEI> identifier when vasp_did is missing")
    void synthesizesLeiIdentifier() {
        when(writer.upsert(any(), any(), anyString())).thenReturn(false);
        String csv = """
            legal_name,lei,status
            Other CASP S.A.,724500A4FBF8B1FE7G53,Withdrawn
            """;

        var result = importService.importCsv(csv, "test", UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(result.created()).isEqualTo(1);
        ArgumentCaptor<CaspAuthorization> captor = ArgumentCaptor.forClass(CaspAuthorization.class);
        verify(writer).upsert(captor.capture(), any(), anyString());
        assertThat(captor.getValue().getVaspDid()).isEqualTo("lei:724500A4FBF8B1FE7G53");
        assertThat(captor.getValue().getStatus()).isEqualTo(CaspAuthorizationStatus.REVOKED);
    }

    @Test
    @DisplayName("bad rows are reported per line and do not block good rows")
    void badRowsReportedGoodRowsProceed() {
        when(writer.upsert(any(), any(), anyString())).thenReturn(false);
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
    @DisplayName("a failed database row is isolated and later rows still commit")
    void failedWrite_doesNotBlockLaterRows() {
        when(writer.upsert(any(), any(), anyString()))
                .thenThrow(new RuntimeException("constraint violation"))
                .thenReturn(false);
        String csv = """
            legal_name;vasp_did;status
            Broken CASP;did:example:broken;Authorised
            Good CASP;did:example:good;Authorised
            """;

        var result = importService.importCsv(csv, "test", UUID.randomUUID(), "REGISTRY_ADMIN");

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).singleElement().asString().contains("constraint violation");
    }

    @Test
    @DisplayName("existing vaspDid counts as updated, not created")
    void existingEntry_countsAsUpdated() {
        when(writer.upsert(any(), any(), anyString())).thenReturn(true);
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
        verify(writer, never()).upsert(any(), any(), anyString());
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
