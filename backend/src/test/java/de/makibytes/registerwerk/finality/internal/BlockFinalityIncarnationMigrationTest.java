package de.makibytes.registerwerk.finality.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V17 block-finality incarnation migration")
class BlockFinalityIncarnationMigrationTest {

    @Test
    @DisplayName("migration preserves rows and replaces height uniqueness with incarnation/canonical invariants")
    void migrationDefinesIncarnationAndCanonicalConstraints() throws IOException {
        String sql = Files.readString(
                Path.of("src/main/resources/db/migration/V17__block_finality_incarnations.sql"),
                StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("DROP CONSTRAINT uq_block_finality")
                .contains("ADD COLUMN canonical BOOLEAN NOT NULL DEFAULT TRUE")
                .contains("ADD COLUMN orphaned_at TIMESTAMPTZ")
                .contains("UNIQUE NULLS NOT DISTINCT (chain_config_id, block_number, block_hash)")
                .contains("ck_block_finality_normalized_hex_hash")
                .contains("WHERE canonical")
                .contains("trg_block_finality_immutable_identity")
                .doesNotContain("DELETE FROM block_finality")
                .doesNotContain("TRUNCATE block_finality");
    }

    @Test
    @DisplayName("active quarantine references the immutable episode and is explicitly resolvable only")
    void quarantineMigrationDefinesFailClosedSnapshot() throws IOException {
        String sql = Files.readString(
                Path.of("src/main/resources/db/migration/V19__active_chain_quarantine.sql"),
                StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE chain_quarantine")
                .contains("FOREIGN KEY (chain_config_id, reorg_id)")
                .contains("REFERENCES chain_reorg_episode(chain_config_id, reorg_id)")
                .contains("active           BOOLEAN      NOT NULL DEFAULT TRUE")
                .contains("resolved_at      TIMESTAMPTZ")
                .contains("FINALITY_VIOLATION")
                .contains("UNRESOLVED_ANCESTRY")
                .doesNotContain("ON DELETE CASCADE");
    }

    @Test
    @DisplayName("chain-effect migration adds canonical source identities and monotonic LIFO order")
    void chainEffectMigrationDefinesIncarnationAndOrder() throws IOException {
        String sql = Files.readString(
                Path.of("src/main/resources/db/migration/V20__chain_effect_incarnation_order.sql"),
                StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("block_hash = lower(block_hash)")
                .contains("block_hash !~ '^0[xX][0-9A-Fa-f]+$'")
                .contains(":block=")
                .contains(":occurrence=")
                .contains("CREATE SEQUENCE chain_effect_journal_sequence_seq AS BIGINT")
                .contains("journal_sequence SET DEFAULT nextval('chain_effect_journal_sequence_seq')")
                .contains("UNIQUE (journal_sequence)")
                .contains("V20 cannot normalize chain_effect source identities without data loss")
                .contains("remove only a proven duplicate")
                .doesNotContain("lower(block_hash) IN");
    }

    @Test
    @DisplayName("legacy terminal projections without exact provenance are returned to verification")
    void domainCausalityMigrationFailsClosedOnUnmatchedLegacyRows() throws IOException {
        String sql = Files.readString(
                Path.of("src/main/resources/db/migration/V21__domain_effect_causality.sql"),
                StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("UPDATE org_registration\nSET status = 'PENDING'")
                .contains("UPDATE org_member_wallet\nSET status = 'PENDING'")
                .contains("UPDATE permission_grant\nSET status = 'PENDING'")
                .contains("UPDATE ecosystem_trusted_issuer\nSET status = 'PENDING'")
                .contains("SET identity_address = '0x-PENDING-ONCHAINID-'")
                .contains("UPDATE onchain_claim\nSET confirmed = FALSE")
                .contains("SET registration_confirmed = FALSE")
                .contains("removed_at = COALESCE(removed_at, now())")
                .contains("SET removal_confirmed = FALSE");
    }
}
