package de.makibytes.registerwerk.screening.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OpenSanctionsAdapter.categoryOf unit tests")
class OpenSanctionsAdapterCategoryTest {

    @Test
    @DisplayName("a role.pep topic is categorized as PEP, not SANCTIONS")
    void pepTopic_categorizedAsPep() {
        Map<String, Object> entry = Map.of("topics", List.of("role.pep"));
        assertThat(OpenSanctionsAdapter.categoryOf(entry)).isEqualTo(HitCategory.PEP.name());
    }

    @Test
    @DisplayName("a sanction topic is categorized as SANCTIONS")
    void sanctionTopic_categorizedAsSanctions() {
        Map<String, Object> entry = Map.of("topics", List.of("sanction"));
        assertThat(OpenSanctionsAdapter.categoryOf(entry)).isEqualTo(HitCategory.SANCTIONS.name());
    }

    @Test
    @DisplayName("PEP takes priority over a co-occurring sanction topic")
    void pepAndSanction_pepTakesPriority() {
        Map<String, Object> entry = Map.of("topics", List.of("sanction", "role.pep"));
        assertThat(OpenSanctionsAdapter.categoryOf(entry)).isEqualTo(HitCategory.PEP.name());
    }

    @Test
    @DisplayName("an unrecognized non-empty topic list falls back to ADVERSE_MEDIA")
    void unrecognizedTopics_fallsBackToAdverseMedia() {
        Map<String, Object> entry = Map.of("topics", List.of("crime.fraud"));
        assertThat(OpenSanctionsAdapter.categoryOf(entry)).isEqualTo(HitCategory.ADVERSE_MEDIA.name());
    }

    @Test
    @DisplayName("a missing topics field defaults to SANCTIONS (preserves pre-existing behavior)")
    void missingTopics_defaultsToSanctions() {
        Map<String, Object> entry = Map.of("id", "Q123");
        assertThat(OpenSanctionsAdapter.categoryOf(entry)).isEqualTo(HitCategory.SANCTIONS.name());
    }

    @Test
    @DisplayName("an empty topics list defaults to SANCTIONS")
    void emptyTopics_defaultsToSanctions() {
        Map<String, Object> entry = Map.of("topics", List.of());
        assertThat(OpenSanctionsAdapter.categoryOf(entry)).isEqualTo(HitCategory.SANCTIONS.name());
    }
}
