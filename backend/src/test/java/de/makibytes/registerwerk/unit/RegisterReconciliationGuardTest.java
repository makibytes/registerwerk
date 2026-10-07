package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.HolderSyncStatus;
import de.makibytes.registerwerk.asset.api.RegisterReconciliationGuard;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.shared.RegisterNotReconciledException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RegisterReconciliationGuard (9A-05, interim T9-01)")
class RegisterReconciliationGuardTest {

    private Asset asset(HolderSyncStatus status) {
        Asset a = new Asset();
        a.setId(UUID.randomUUID());
        a.setHolderSyncStatus(status);
        return a;
    }

    @Test
    @DisplayName("a BLOCKED register refuses disclosure and names the last sync and the unmapped wallets (409 gate exception)")
    void blockedRefuses() {
        Asset a = asset(HolderSyncStatus.BLOCKED);
        a.setLastSuccessfulHolderSyncAt(Instant.parse("2026-09-01T10:15:00Z"));
        a.setHolderSyncUnmappedWallets("0xdeadbeef");
        assertThatThrownBy(() -> RegisterReconciliationGuard.requireReconciled(a, "Register statement"))
                .isInstanceOf(RegisterNotReconciledException.class)
                .isInstanceOf(ComplianceGateException.class)
                .hasMessageContaining("Register statement refused")
                .hasMessageContaining("2026-09-01 10:15 UTC")
                .hasMessageContaining("0xdeadbeef")
                .satisfies(e -> assertThat(((RegisterNotReconciledException) e).getAssetId()).isEqualTo(a.getId()));
    }

    @Test
    @DisplayName("OK, never-synced and null assets are not refused (staleness is a stamp, not a refusal)")
    void okAndNeverSyncedAreNotRefused() {
        assertThatCode(() -> RegisterReconciliationGuard.requireReconciled(asset(HolderSyncStatus.OK), "x")).doesNotThrowAnyException();
        assertThatCode(() -> RegisterReconciliationGuard.requireReconciled(null, "x")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the stamp never claims a reconciliation that did not happen")
    void stampVariants() {
        Asset never = asset(HolderSyncStatus.OK);
        assertThat(RegisterReconciliationGuard.stamp(never)).contains("No on-chain reconciliation recorded");
        Asset synced = asset(HolderSyncStatus.OK);
        synced.setLastSuccessfulHolderSyncAt(Instant.parse("2026-09-01T10:15:00Z"));
        assertThat(RegisterReconciliationGuard.stamp(synced)).isEqualTo("Register reconciled with the chain as of 2026-09-01 10:15 UTC");
        Asset blocked = asset(HolderSyncStatus.BLOCKED);
        blocked.setLastSuccessfulHolderSyncAt(Instant.parse("2026-09-01T10:15:00Z"));
        assertThat(RegisterReconciliationGuard.stamp(blocked)).startsWith("Register NOT reconciled").contains("2026-09-01 10:15 UTC");
    }
}
