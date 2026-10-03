package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntry;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import de.makibytes.registerwerk.shared.RegisterClock;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Shared fixtures for the corporate-action unit tests. */
final class CorporateActionTestSupport {

    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private CorporateActionTestSupport() {}

    /** Register clock in the JVM's zone — "today" equals {@code LocalDate.now()} in the tests. */
    static RegisterClock systemRegisterClock() {
        return new RegisterClock(Clock.systemDefaultZone(), ZoneId.systemDefault());
    }

    /** An executor whose REQUIRES_NEW transactions go to a mock manager: items run inline and the manager records
     *  the begin / commit / rollback of each (H8). */
    static IsolatedTransactionExecutor directTransactions() {
        return new IsolatedTransactionExecutor(Mockito.mock(PlatformTransactionManager.class));
    }

    /** Register clock pinned to noon (Berlin) of {@code today}. */
    static RegisterClock registerClockAt(LocalDate today) {
        Instant noon = today.atTime(12, 0).atZone(BERLIN).toInstant();
        return new RegisterClock(Clock.fixed(noon, BERLIN), BERLIN);
    }

    /** The resolver outcome the snapshot would get for these rows, taken at their current nominal. */
    static RecordDatePositionResolver.Resolution positionsOf(List<AssetHolder> holders) {
        return new RecordDatePositionResolver.Resolution(holders.stream()
                .map(h -> new RecordDatePositionResolver.Position(h.getId(), h.getInvestorId(), h.getWalletAddress(),
                        h.getHolderKind(), h.getNominalAmount() != null ? h.getNominalAmount() : BigDecimal.ZERO))
                .toList(), Optional.empty(), List.of());
    }

    /** A snapshotted entry as {@code snapshotEntriesAndCompute} writes it. */
    static CorporateActionEntry entry(UUID actionId, String wallet, String nominal, String entitlement) {
        CorporateActionEntry e = new CorporateActionEntry();
        org.springframework.test.util.ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
        e.setCorporateActionId(actionId);
        e.setAssetHolderId(UUID.randomUUID());
        e.setInvestorId(UUID.randomUUID());
        e.setWalletAddress(wallet);
        e.setNominalAtRecord(new BigDecimal(nominal));
        e.setEntitlementAmount(new BigDecimal(entitlement));
        return e;
    }

    /** The digest the service computes for these entries. */
    static String digest(CorporateAction ca, List<CorporateActionEntry> entries) {
        return CorporateActionPayoutDigest.of(ca, entries);
    }

    /** Marks {@code ca} as computed over {@code entries} (digest stored, as the snapshot does). */
    static void computed(CorporateAction ca, List<CorporateActionEntry> entries) {
        ca.setStatus(CorporateAction.Status.COMPUTED);
        ca.setPayoutDigest(digest(ca, entries));
    }

    /** Both parties have signed the amounts as they are now. */
    static void signedOff(CorporateAction ca, List<CorporateActionEntry> entries, UUID issuer, UUID operator) {
        computed(ca, entries);
        ca.setIssuerAttestedBy(issuer);
        ca.setIssuerAttestedAt(Instant.now());
        ca.setIssuerAttestedDigest(ca.getPayoutDigest());
        ca.setDualControlApproverId(operator);
        ca.setDualControlApprovedAt(Instant.now());
        ca.setOperatorConfirmedDigest(ca.getPayoutDigest());
    }
}
