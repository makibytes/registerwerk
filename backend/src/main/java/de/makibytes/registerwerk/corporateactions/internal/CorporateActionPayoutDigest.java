package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntry;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The fingerprint of what a corporate action will pay (Wave 0b C6): SHA-256 over the action's per-unit terms, its
 * total and rounding residual, and every entry (holder row, wallet, nominal at record date, entitlement, whether it
 * is held look-through). The issuer's attestation and the operator's confirmation are bound to it, so neither can
 * approve amounts that were later changed - re-snapshotting or recomputing the action yields a different digest and
 * voids both.
 *
 * <p>Deliberately NOT part of the digest: anything decided at settlement time (a holder held back by a screening or
 * Sperrvermerk gate, settlement tx hashes). Those do not change what was computed; they only change who is paid now.
 * Numbers are normalised ({@code stripTrailingZeros().toPlainString()}) so the digest does not depend on the scale
 * the database happens to return.
 */
final class CorporateActionPayoutDigest {

    private CorporateActionPayoutDigest() {}

    static String of(CorporateAction action, List<CorporateActionEntry> entries) {
        StringBuilder canonical = new StringBuilder(256 + entries.size() * 160)
                .append("rw-ca-payout-v1\n")
                .append("action=").append(action.getId()).append('\n')
                .append("type=").append(action.getActionType()).append('\n')
                .append("currency=").append(action.getCurrency()).append('\n')
                .append("amountPerUnit=").append(num(action.getAmountPerUnit())).append('\n')
                .append("total=").append(num(action.getTotalAmount())).append('\n')
                .append("residual=").append(num(action.getRoundingResidual())).append('\n');
        entries.stream()
                .sorted(Comparator.comparing((CorporateActionEntry e) -> String.valueOf(e.getAssetHolderId()))
                        .thenComparing(e -> String.valueOf(e.getWalletAddress())))
                .forEach(e -> canonical
                        .append("entry=").append(e.getAssetHolderId())
                        .append('|').append(uuid(e.getInvestorId()))
                        .append('|').append(e.getWalletAddress() == null ? "" : e.getWalletAddress().toLowerCase(Locale.ROOT))
                        .append('|').append(num(e.getNominalAtRecord()))
                        .append('|').append(num(e.getEntitlementAmount()))
                        .append('|').append(e.getPayoutStatus() == CorporateActionEntry.PayoutStatus.HELD_LOOK_THROUGH
                                ? "HELD_LOOK_THROUGH" : "PAYABLE")
                        .append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String num(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static String uuid(UUID id) {
        return id == null ? "" : id.toString();
    }
}
