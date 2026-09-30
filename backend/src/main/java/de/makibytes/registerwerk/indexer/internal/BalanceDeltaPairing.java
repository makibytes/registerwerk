package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.indexer.api.TokenTransfer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Turns the per-owner net balance change of ONE instrument inside ONE atomic ledger transaction
 * into register movements. Shared by the Solana (pre/post token balances) and Canton (holding
 * create/archive) ingesters, both of which observe balances rather than transfer events.
 *
 * <p>Positive and negative deltas are matched into {@code TRANSFER} legs (largest first, amounts
 * split where the sides differ); what remains unmatched is issuance ({@code MINT}, positive
 * residual) or redemption ({@code BURN}, negative residual). The legs always reproduce the per-owner
 * deltas exactly, so the register's balance arithmetic is correct even when the classification of
 * a leg (for example a Token-2022 transfer fee showing up as a small burn) is a judgement call.
 * A split (100 -> 60 to B, 40 change to A) nets to a single {@code A -> B 60} leg; a merge of two
 * holdings of one owner nets to no leg at all.
 */
final class BalanceDeltaPairing {

    /** One register movement; {@code from}/{@code to} are {@code null} on the mint/burn side. */
    record Leg(String from, String to, BigDecimal amount, TokenTransfer.EventType type) {}

    private BalanceDeltaPairing() {}

    static List<Leg> pair(Map<String, BigDecimal> deltaByOwner) {
        List<Map.Entry<String, BigDecimal>> positives = new ArrayList<>();
        List<Map.Entry<String, BigDecimal>> negatives = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : deltaByOwner.entrySet()) {
            int sign = e.getValue().signum();
            if (sign > 0) {
                positives.add(Map.entry(e.getKey(), e.getValue()));
            } else if (sign < 0) {
                negatives.add(Map.entry(e.getKey(), e.getValue().negate()));
            }
        }
        Comparator<Map.Entry<String, BigDecimal>> order = Comparator
                .<Map.Entry<String, BigDecimal>, BigDecimal>comparing(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey);
        positives.sort(order);
        negatives.sort(order);

        List<Leg> legs = new ArrayList<>();
        int p = 0;
        int n = 0;
        BigDecimal posLeft = positives.isEmpty() ? BigDecimal.ZERO : positives.get(0).getValue();
        BigDecimal negLeft = negatives.isEmpty() ? BigDecimal.ZERO : negatives.get(0).getValue();
        while (p < positives.size() && n < negatives.size()) {
            BigDecimal amount = posLeft.min(negLeft);
            legs.add(new Leg(negatives.get(n).getKey(), positives.get(p).getKey(), amount,
                    TokenTransfer.EventType.TRANSFER));
            posLeft = posLeft.subtract(amount);
            negLeft = negLeft.subtract(amount);
            if (posLeft.signum() == 0) {
                p++;
                posLeft = p < positives.size() ? positives.get(p).getValue() : BigDecimal.ZERO;
            }
            if (negLeft.signum() == 0) {
                n++;
                negLeft = n < negatives.size() ? negatives.get(n).getValue() : BigDecimal.ZERO;
            }
        }
        while (p < positives.size()) {
            legs.add(new Leg(null, positives.get(p).getKey(), posLeft, TokenTransfer.EventType.MINT));
            p++;
            posLeft = p < positives.size() ? positives.get(p).getValue() : BigDecimal.ZERO;
        }
        while (n < negatives.size()) {
            legs.add(new Leg(negatives.get(n).getKey(), null, negLeft, TokenTransfer.EventType.BURN));
            n++;
            negLeft = n < negatives.size() ? negatives.get(n).getValue() : BigDecimal.ZERO;
        }
        return legs;
    }
}
