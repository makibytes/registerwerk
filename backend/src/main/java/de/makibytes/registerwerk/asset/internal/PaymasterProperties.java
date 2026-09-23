package de.makibytes.registerwerk.asset.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * Voucher-issuer settings for {@code EwpgPaymaster} (contracts/src/ecosystem/EwpgPaymaster.sol).
 *
 * <p>{@link #voucherSignerKey} is the dev-mode signing key whose address each on-chain policy
 * registers as its {@code policySigner}. It must never be the claim-signing (trusted-issuer)
 * wallet: a voucher key can spend sponsorship budget, a claim key vouches for KYC — keeping them
 * apart is domain separation, on top of the {@code VOUCHER_TYPEHASH} tag in the digest itself.
 * Production moves this key behind the KMS/HSM track; blank disables voucher issuance
 * (fail closed — the customer UI then falls back to a self-paid transaction).
 */
@Component
@ConfigurationProperties(prefix = "registerwerk.paymaster")
public class PaymasterProperties {

    /** Hex secp256k1 private key of the voucher signer. Blank = sponsorship unavailable. */
    private String voucherSignerKey = "";

    /** How long a signed voucher stays valid ({@code validUntil = now + this}). */
    private long voucherValiditySeconds = 300;

    /** Highest {@code maxFeePerGas} a voucher is issued for; signed into the voucher as its cap. */
    private BigInteger maxFeePerGasCapWei = BigInteger.valueOf(50_000_000_000L);

    /** Upper bound on the summed gas limits of one sponsored UserOperation. */
    private long maxTotalGas = 3_000_000;

    /**
     * Share of a policy's {@code monthlyCapEth} one legal entity may consume per month (0..1),
     * so a single organisation cannot exhaust an issuer's budget for every other holder.
     */
    private BigDecimal entityMonthlyCapShare = new BigDecimal("0.10");

    /** EwpgPaymaster address per chain identifier (e.g. {@code ethereum-testnet}). */
    private Map<String, String> addresses = new HashMap<>();

    /** Paymaster address for the chain identifier, or null when none is configured. */
    public String addressFor(String chainIdentifier) {
        if (chainIdentifier == null) return null;
        String address = addresses.get(chainIdentifier.toLowerCase().replace('_', '-'));
        return address == null || address.isBlank() ? null : address;
    }

    public boolean isVoucherSigningEnabled() {
        return voucherSignerKey != null && !voucherSignerKey.isBlank();
    }

    public String getVoucherSignerKey() { return voucherSignerKey; }
    public void setVoucherSignerKey(String voucherSignerKey) { this.voucherSignerKey = voucherSignerKey; }

    public long getVoucherValiditySeconds() { return voucherValiditySeconds; }
    public void setVoucherValiditySeconds(long voucherValiditySeconds) { this.voucherValiditySeconds = voucherValiditySeconds; }

    public BigInteger getMaxFeePerGasCapWei() { return maxFeePerGasCapWei; }
    public void setMaxFeePerGasCapWei(BigInteger maxFeePerGasCapWei) { this.maxFeePerGasCapWei = maxFeePerGasCapWei; }

    public long getMaxTotalGas() { return maxTotalGas; }
    public void setMaxTotalGas(long maxTotalGas) { this.maxTotalGas = maxTotalGas; }

    public BigDecimal getEntityMonthlyCapShare() { return entityMonthlyCapShare; }
    public void setEntityMonthlyCapShare(BigDecimal entityMonthlyCapShare) { this.entityMonthlyCapShare = entityMonthlyCapShare; }

    public Map<String, String> getAddresses() { return addresses; }
    public void setAddresses(Map<String, String> addresses) { this.addresses = addresses; }
}
