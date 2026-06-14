package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.travelrule.api.Ivms101;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("HolderIdentityResolver IVMS-101 enrichment unit tests")
class HolderIdentityResolverTest {

    @Mock
    private JdbcTemplate jdbc;

    @InjectMocks
    private HolderIdentityResolver resolver;

    private final UUID assetId = UUID.randomUUID();

    @Test
    @DisplayName("registered holder resolves to LegalPerson with LEI and country")
    void registeredHolder_resolvesFullIdentity() {
        Map<String, Object> row = new HashMap<>();
        row.put("current_name", "Beispiel Emittent GmbH");
        row.put("registration_country", "DE");
        row.put("lei_code", "529900T8BM49AURSDO55");
        row.put("entity_number", "RW-000042");
        when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class))).thenReturn(List.of(row));

        Optional<Ivms101.IdentityPayload> result = resolver.resolve(assetId, "0xAbC123");

        assertThat(result).isPresent();
        Ivms101.IdentityPayload identity = result.get();
        assertThat(identity.person().legalPerson().name().get(0).legalPersonName())
                .isEqualTo("Beispiel Emittent GmbH");
        assertThat(identity.nationalIdentification().nationalIdentifier())
                .isEqualTo("529900T8BM49AURSDO55");
        assertThat(identity.nationalIdentification().nationalIdentifierType())
                .isEqualTo(Ivms101.NationalIdentifierTypeCode.LEIX);
        assertThat(identity.countryOfResidence().country()).isEqualTo("DE");
    }

    @Test
    @DisplayName("holder without LEI omits national identification instead of sending blanks")
    void holderWithoutLei_omitsNationalId() {
        Map<String, Object> row = new HashMap<>();
        row.put("current_name", "Kleinanleger UG");
        row.put("registration_country", "DE");
        row.put("lei_code", null);
        row.put("entity_number", "RW-000099");
        when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class))).thenReturn(List.of(row));

        Optional<Ivms101.IdentityPayload> result = resolver.resolve(assetId, "0xdef");

        assertThat(result).isPresent();
        assertThat(result.get().nationalIdentification()).isNull();
    }

    @Test
    @DisplayName("unknown wallet resolves empty — external beneficiaries stay account-number-only")
    void unknownWallet_resolvesEmpty() {
        when(jdbc.queryForList(anyString(), any(Object.class), any(Object.class))).thenReturn(List.of());
        assertThat(resolver.resolve(assetId, "0x0000")).isEmpty();
    }

    @Test
    @DisplayName("null or blank inputs short-circuit without touching the database")
    void blankInputs_shortCircuit() {
        assertThat(resolver.resolve(null, "0xabc")).isEmpty();
        assertThat(resolver.resolve(assetId, "  ")).isEmpty();
        assertThat(resolver.resolve(assetId, null)).isEmpty();
    }
}
