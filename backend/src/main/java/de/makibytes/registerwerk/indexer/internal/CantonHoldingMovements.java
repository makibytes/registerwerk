package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.indexer.api.TokenTransfer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Turns the Holding creates and archives of ONE Canton update into register movements (P4D-5).
 *
 * <p>The Daml Token Standard moves value by archiving holdings and creating new ones, and one
 * update commonly does both for the same owner (a split: the sender's 100 is archived, 60 is
 * created for the receiver and 40 as change for the sender; a merge: two holdings archived, one
 * created). Movements are therefore derived from the <em>net</em> change per (instrument, owner)
 * - created minus archived - and paired by {@link BalanceDeltaPairing}: a split books exactly one
 * {@code A -> B 60} transfer, a merge books nothing, and no phantom MINT/BURN pair appears.
 *
 * <p>Deliberately free of Daml types so it is unit-testable without the {@code canton} profile.
 */
final class CantonHoldingMovements {

    record Holding(String contractId, String owner, String instrument, BigDecimal amount) {}

    /** One row to book; {@code index} is unique within the update. */
    record Movement(int index, String instrument, String from, String to, BigDecimal amount,
                    TokenTransfer.EventType type) {}

    /**
     * @param movements     movements to book, in deterministic order
     * @param survivors     holdings created by the update and still open at its end (to snapshot)
     * @param consumed      contract ids of pre-existing holdings archived by the update (snapshots to delete)
     * @param unresolved    archived contract ids no snapshot exists for (holding predates the indexer)
     */
    record Plan(List<Movement> movements, List<Holding> survivors, List<String> consumed, List<String> unresolved) {}

    private CantonHoldingMovements() {}

    static Plan plan(List<Holding> creates, List<String> archivedContractIds,
                     Function<String, Optional<Holding>> snapshotLookup) {
        Map<String, Holding> createdInUpdate = new LinkedHashMap<>();
        for (Holding c : creates) {
            createdInUpdate.put(c.contractId(), c);
        }
        Set<String> ephemeral = new LinkedHashSet<>(); // created and archived inside the same update
        List<Holding> archived = new ArrayList<>();
        List<String> consumed = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (String id : archivedContractIds) {
            if (createdInUpdate.containsKey(id)) {
                ephemeral.add(id);
                continue;
            }
            Optional<Holding> snap = snapshotLookup.apply(id);
            if (snap.isPresent()) {
                archived.add(snap.get());
                consumed.add(id);
            } else {
                unresolved.add(id);
            }
        }
        List<Holding> survivors = creates.stream().filter(c -> !ephemeral.contains(c.contractId())).toList();

        Map<String, Map<String, BigDecimal>> deltas = new LinkedHashMap<>(); // instrument -> owner -> net
        for (Holding c : survivors) {
            deltas.computeIfAbsent(c.instrument(), k -> new LinkedHashMap<>()).merge(c.owner(), c.amount(), BigDecimal::add);
        }
        for (Holding a : archived) {
            deltas.computeIfAbsent(a.instrument(), k -> new LinkedHashMap<>()).merge(a.owner(), a.amount().negate(), BigDecimal::add);
        }

        List<Movement> movements = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, Map<String, BigDecimal>> e : deltas.entrySet()) {
            for (BalanceDeltaPairing.Leg leg : BalanceDeltaPairing.pair(e.getValue())) {
                movements.add(new Movement(index++, e.getKey(), leg.from(), leg.to(), leg.amount(), leg.type()));
            }
        }
        return new Plan(movements, survivors, consumed, unresolved);
    }
}
