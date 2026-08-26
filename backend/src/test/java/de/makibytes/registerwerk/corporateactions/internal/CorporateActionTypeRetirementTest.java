package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the retirement of {@code CorporateAction.ActionType.PLEDGE} — verified before removal to
 * have zero read/write references anywhere in the codebase beyond its own enum declaration, one
 * javadoc mention, and one (now-removed) frontend type-union entry; the real pledge/collateral
 * mechanism lives in the {@code lending} module. Two layers are checked, matching the plan's own
 * "JPA and DB-constraint level" framing: the enum itself (this codebase has no @DataJpaTest/
 * Testcontainers convention anywhere to spin up a live constraint check against, so the DB half is
 * verified by asserting on the actual migration SQL that defines it).
 */
@DisplayName("CorporateAction PLEDGE retirement")
class CorporateActionTypeRetirementTest {

    @Test
    @DisplayName("ActionType has exactly 10 values and does not include PLEDGE")
    void actionTypeEnum_hasTenValuesAndNoPledge() {
        CorporateAction.ActionType[] values = CorporateAction.ActionType.values();

        assertThat(values).hasSize(10);
        assertThat(values).extracting(Enum::name).doesNotContain("PLEDGE");
    }

    @Test
    @DisplayName("ActionType.valueOf(\"PLEDGE\") throws — PLEDGE is not a resolvable enum constant at the JPA level")
    void actionTypeValueOf_rejectsPledge() {
        assertThatThrownBy(() -> CorporateAction.ActionType.valueOf("PLEDGE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Status has exactly 9 values, including the new PROPOSED/REJECTED pre-states")
    void statusEnum_hasNineValuesIncludingProposedAndRejected() {
        CorporateAction.Status[] values = CorporateAction.Status.values();

        assertThat(values).hasSize(9);
        assertThat(values).contains(CorporateAction.Status.PROPOSED, CorporateAction.Status.REJECTED);
    }

    @Test
    @DisplayName("ck_ca_action_type CHECK constraint excludes PLEDGE at the DB level")
    void migration_actionTypeCheckConstraintExcludesPledge() throws IOException {
        String sql = readMigration();

        assertThat(sql).contains("ck_ca_action_type");
        assertThat(sql).doesNotContain("'PLEDGE'");
        for (CorporateAction.ActionType type : CorporateAction.ActionType.values()) {
            assertThat(sql).contains("'" + type.name() + "'");
        }
    }

    @Test
    @DisplayName("ck_ca_status CHECK constraint includes PROPOSED and REJECTED")
    void migration_statusCheckConstraintIncludesProposedAndRejected() throws IOException {
        String sql = readMigration();

        assertThat(sql).contains("ck_ca_status");
        assertThat(sql).contains("'PROPOSED'");
        assertThat(sql).contains("'REJECTED'");
    }

    private static String readMigration() throws IOException {
        Path path = Path.of("src/main/resources/db/migration/V1__initial_schema.sql");
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
