package de.makibytes.registerwerk.finality.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Structural guards over the baseline schema for the block-finality / chain-effect model. */
@DisplayName("block-finality schema")
class BlockFinalitySchemaTest {

    @Test
    @DisplayName("block_finality is keyed by incarnation with a canonical-height invariant and an immutable identity")
    void schemaDefinesIncarnationAndCanonicalConstraints() throws IOException {
        String sql = readSchema();

        assertThat(sql)
                .doesNotContain("uq_block_finality ")
                .contains("canonical BOOLEAN NOT NULL DEFAULT TRUE")
                .contains("orphaned_at TIMESTAMPTZ")
                .contains("UNIQUE NULLS NOT DISTINCT (chain_config_id, block_number, block_hash)")
                .contains("ck_block_finality_normalized_hex_hash")
                .contains("WHERE canonical")
                .contains("trg_block_finality_immutable_identity");
    }

    @Test
    @DisplayName("active quarantine references the immutable episode and is explicitly resolvable only")
    void quarantineSchemaDefinesFailClosedSnapshot() throws IOException {
        String sql = readSchema();
        String chainQuarantineTable = sql.substring(
                sql.indexOf("CREATE TABLE chain_quarantine"),
                sql.indexOf(");", sql.indexOf("CREATE TABLE chain_quarantine")));

        assertThat(chainQuarantineTable)
                .contains("FOREIGN KEY (chain_config_id, reorg_id)")
                .contains("REFERENCES chain_reorg_episode(chain_config_id, reorg_id)")
                .contains("active           BOOLEAN      NOT NULL DEFAULT TRUE")
                .contains("resolved_at      TIMESTAMPTZ")
                .contains("FINALITY_VIOLATION")
                .contains("UNRESOLVED_ANCESTRY")
                .doesNotContain("ON DELETE CASCADE");
    }

    @Test
    @DisplayName("chain_effect has a monotonic journal sequence for LIFO compensation order")
    void chainEffectSchemaDefinesMonotonicOrder() throws IOException {
        String sql = readSchema();

        assertThat(sql)
                .contains("CREATE SEQUENCE chain_effect_journal_sequence_seq AS BIGINT")
                .contains("journal_sequence BIGINT NOT NULL DEFAULT nextval('chain_effect_journal_sequence_seq')")
                .contains("UNIQUE (journal_sequence)");
    }

    private static String readSchema() throws IOException {
        return Files.readString(
                Path.of("src/main/resources/db/migration/V1__initial_schema.sql"),
                StandardCharsets.UTF_8);
    }
}
