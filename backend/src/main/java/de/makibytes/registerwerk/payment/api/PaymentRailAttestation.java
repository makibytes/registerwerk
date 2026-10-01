package de.makibytes.registerwerk.payment.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Binds an operator's MiCAR attestation to the content that was attested. The fingerprint
 * covers every fact a holder relies on (identity of the rail, issuer, authorisation, EMT and
 * par claims, currency, decimals and the token address per chain) so any later change of one
 * of them - including through a code path added in the future - voids the attestation without
 * a field list having to be kept in sync. This is an operator attestation, never an
 * independent verification by Registerwerk.
 */
public final class PaymentRailAttestation {

    private PaymentRailAttestation() {}

    /** Canonical, unambiguous ('|' and '\' escaped) SHA-256 over the attested facts. */
    public static String fingerprint(PaymentRail rail, Map<UUID, String> chainAddresses) {
        String addresses = chainAddresses == null ? "" : chainAddresses.entrySet().stream()
                .map(e -> e.getKey() + ":" + (e.getValue() == null ? "" : e.getValue().toLowerCase(Locale.ROOT)))
                .sorted()
                .collect(Collectors.joining(","));
        String canonical = String.join("|",
                field(rail.getCode()),
                field(rail.getRailType() == null ? null : rail.getRailType().name()),
                field(rail.getCurrency()),
                field(rail.getDecimals() == null ? null : rail.getDecimals().toString()),
                field(rail.getIssuerName()),
                field(rail.getIssuerLei()),
                field(rail.getMicarAuthorization()),
                Boolean.toString(rail.isEmtFlag()),
                field(rail.getWhitePaperUrl()),
                Boolean.toString(rail.isRedemptionAtPar()),
                addresses);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** chainConfigId to token address map of a rail's stored addresses. */
    public static Map<UUID, String> addressMap(List<PaymentRailChainAddress> addresses) {
        Map<UUID, String> map = new LinkedHashMap<>();
        for (PaymentRailChainAddress a : addresses) {
            map.put(a.getChainConfigId(), a.getTokenAddress());
        }
        return map;
    }

    /** True only if the attestation flag is set AND still matches the rail's current content. */
    public static boolean isEffective(PaymentRail rail, Map<UUID, String> chainAddresses) {
        return rail.isMicarVerified()
                && rail.getMicarAttestedFingerprint() != null
                && rail.getMicarAttestedFingerprint().equals(fingerprint(rail, chainAddresses));
    }

    private static String field(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("|", "\\|");
    }
}
