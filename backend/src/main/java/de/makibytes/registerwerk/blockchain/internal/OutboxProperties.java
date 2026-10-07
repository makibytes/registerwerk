package de.makibytes.registerwerk.blockchain.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Tunables of the durable EVM outbox recovery (P4B-4). Defaults follow the approved plan: retry
 * back-off 15 s to 5 min, page at 10 min, re-broadcast stored bytes after 10 min, replacement fee
 * bump of at least 15 % (nodes require 10 %).
 */
@Component
@ConfigurationProperties(prefix = "registerwerk.outbox")
public class OutboxProperties {

    /** First retry delay after a failed broadcast; doubles per attempt. */
    private Duration backoffBase = Duration.ofSeconds(15);
    private Duration backoffMax = Duration.ofMinutes(5);

    /** Rows dispatched per signer and cycle (head-of-line: the first failure stops that signer). */
    private int perSignerBatch = 10;
    /** Upper bound on rows considered per dispatcher cycle, across all signers. */
    private int batchLimit = 200;

    /** Failed broadcast attempts with a fee-related class before an allow-listed call is re-priced. */
    private int autoRepriceAfterAttempts = 4;

    /**
     * Function names whose stuck PREPARED payloads may be re-priced without an operator. Anything
     * whose name starts with one of {@link #REGULATORY_PREFIXES} is never re-priced automatically,
     * whatever this list says (parked T4-03: a regulatory operation is only replaced or cancelled
     * by an operator under step-up and 4-eyes).
     */
    private List<String> autoRepriceMethods = List.of(
            "registerIdentity", "batchRegisterIdentity", "updateIdentity", "updateCountry",
            "addClaim", "removeClaim", "addKey", "removeKey", "revokeClaimBySignature",
            "addTrustedIssuer", "removeTrustedIssuer",
            "updateManifest", "registerDapp", "registerOrg", "definePermission", "grantToOrg", "grantToRole",
            "setMemberRoles", "addMember", "setNavPerShare", "acceptOwnership");

    /** A BROADCAST row unknown to the node for this long has its stored bytes re-broadcast. */
    private Duration rebroadcastAfter = Duration.ofMinutes(10);

    /** A PREPARED/BROADCAST row older than this without a receipt is "stuck": alert and list it. */
    private Duration stuckAfter = Duration.ofMinutes(10);

    /** Minimum fee increase of a replacement over the transaction it replaces. */
    private int replacementBumpPercent = 15;

    /**
     * A nonce lease ahead of the chain's pending count is only capped back to the chain value once
     * the lease has not moved for this long (an immediate-path transaction in flight legitimately
     * makes the lease lead for a moment).
     */
    private Duration leaseRepairGrace = Duration.ofMinutes(10);

    /**
     * How long a direct (non-outbox) send stays in the {@code evm_direct_submission} ledger that protects its
     * nonce from lease repair (H10). A transaction nobody has mined or dropped after this long is long gone.
     */
    private Duration directLedgerRetention = Duration.ofDays(7);

    /** Prefixes of function names that are regulatory operations (never auto re-priced). */
    public static final List<String> REGULATORY_PREFIXES = List.of(
            "forced", "forceBurn", "burn", "batchBurn", "batchForced", "freeze", "unfreeze",
            "setAddressFrozen", "recovery", "confidentialBurn", "confidentialForced", "pause", "unpause");

    public boolean mayAutoReprice(String methodName) {
        if (methodName == null) {
            return false;
        }
        String lower = methodName.toLowerCase(Locale.ROOT);
        for (String prefix : REGULATORY_PREFIXES) {
            if (lower.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return autoRepriceMethods.contains(methodName);
    }

    /** Exponential back-off before the {@code attempt}-th retry (1-based), capped. */
    public Duration backoff(int attempt) {
        long factor = 1L << Math.min(Math.max(attempt - 1, 0), 20);
        Duration d = backoffBase.multipliedBy(factor);
        return d.compareTo(backoffMax) > 0 ? backoffMax : d;
    }

    public Duration getBackoffBase() { return backoffBase; }
    public void setBackoffBase(Duration backoffBase) { this.backoffBase = backoffBase; }
    public Duration getBackoffMax() { return backoffMax; }
    public void setBackoffMax(Duration backoffMax) { this.backoffMax = backoffMax; }
    public int getPerSignerBatch() { return perSignerBatch; }
    public void setPerSignerBatch(int perSignerBatch) { this.perSignerBatch = perSignerBatch; }
    public int getBatchLimit() { return batchLimit; }
    public void setBatchLimit(int batchLimit) { this.batchLimit = batchLimit; }
    public int getAutoRepriceAfterAttempts() { return autoRepriceAfterAttempts; }
    public void setAutoRepriceAfterAttempts(int autoRepriceAfterAttempts) { this.autoRepriceAfterAttempts = autoRepriceAfterAttempts; }
    public List<String> getAutoRepriceMethods() { return autoRepriceMethods; }
    public void setAutoRepriceMethods(List<String> autoRepriceMethods) { this.autoRepriceMethods = List.copyOf(autoRepriceMethods); }
    public Duration getRebroadcastAfter() { return rebroadcastAfter; }
    public void setRebroadcastAfter(Duration rebroadcastAfter) { this.rebroadcastAfter = rebroadcastAfter; }
    public Duration getStuckAfter() { return stuckAfter; }
    public void setStuckAfter(Duration stuckAfter) { this.stuckAfter = stuckAfter; }
    public int getReplacementBumpPercent() { return replacementBumpPercent; }
    public void setReplacementBumpPercent(int replacementBumpPercent) { this.replacementBumpPercent = replacementBumpPercent; }
    public Duration getDirectLedgerRetention() { return directLedgerRetention; }
    public void setDirectLedgerRetention(Duration directLedgerRetention) { this.directLedgerRetention = directLedgerRetention; }
    public Duration getLeaseRepairGrace() { return leaseRepairGrace; }
    public void setLeaseRepairGrace(Duration leaseRepairGrace) { this.leaseRepairGrace = leaseRepairGrace; }
}
