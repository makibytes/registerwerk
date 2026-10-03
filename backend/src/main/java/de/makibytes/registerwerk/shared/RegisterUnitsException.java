package de.makibytes.registerwerk.shared;

/**
 * A register-unit flow (coupon / redemption / corporate-action maths, primary-market mint, trading) was refused
 * because the asset has a deployment whose token does not count in WHOLE units (Wave 0b C5).
 *
 * <p>The register's amounts ({@code asset_holder.nominal_amount}, {@code token_transfer.amount}) are the token's raw
 * base units; every one of those flows reads them as whole units. A token with {@code decimals != 0} would be paid,
 * minted or traded 10^decimals times wrong, so the flow fails closed instead. A {@link ComplianceGateException}
 * subtype: it maps to 409 and is recorded as a rejected action in the audit log ("someone tried a forbidden action"),
 * and the corporate-action pipeline surfaces it as a visible, alerting {@code SNAPSHOT_BLOCKED} instead of a log line.
 */
public class RegisterUnitsException extends ComplianceGateException {

    public RegisterUnitsException(String message) {
        super(message);
    }
}
