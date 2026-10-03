package de.makibytes.registerwerk.indexer.internal;

import de.makibytes.registerwerk.deployment.api.IndexedTransferLookup;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** {@link IndexedTransferLookup} over {@code token_transfer}: FINALIZED rows only, ORPHANED / PROVISIONAL never count. */
@Component
class IndexedTransferLookupService implements IndexedTransferLookup {

    private static final String ZERO = "0x0000000000000000000000000000000000000000";

    private final TokenTransferRepository transfers;

    @PersistenceContext
    private EntityManager entityManager;

    IndexedTransferLookupService(TokenTransferRepository transfers) {
        this.transfers = transfers;
    }

    @Override
    @Transactional(readOnly = true)
    public List<IndexedTransfer> finalizedTransfers(String txHash) {
        if (txHash == null || txHash.isBlank()) {
            return List.of();
        }
        return transfers.findByTxHashIgnoreCaseAndFinalityStatus(txHash, FinalityLevel.FINALIZED).stream()
                .map(t -> new IndexedTransfer(t.getDeploymentId(), t.getFromAddress(), t.getToAddress(), t.getAmount(),
                        isMint(t), isBurn(t)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal finalizedBalance(UUID deploymentId, String walletAddress) {
        if (deploymentId == null || walletAddress == null) {
            return BigDecimal.ZERO;
        }
        String wallet = walletAddress.toLowerCase(Locale.ROOT);
        BigDecimal in = entityManager.createQuery(
                        "SELECT COALESCE(SUM(t.amount), 0) FROM TokenTransfer t WHERE t.deploymentId = :d "
                                + "AND t.finalityStatus = :f AND LOWER(t.toAddress) = :w", BigDecimal.class)
                .setParameter("d", deploymentId).setParameter("f", FinalityLevel.FINALIZED).setParameter("w", wallet)
                .getSingleResult();
        BigDecimal out = entityManager.createQuery(
                        "SELECT COALESCE(SUM(t.amount), 0) FROM TokenTransfer t WHERE t.deploymentId = :d "
                                + "AND t.finalityStatus = :f AND LOWER(t.fromAddress) = :w", BigDecimal.class)
                .setParameter("d", deploymentId).setParameter("f", FinalityLevel.FINALIZED).setParameter("w", wallet)
                .getSingleResult();
        return in.subtract(out);
    }

    private static boolean isMint(TokenTransfer t) {
        return t.getEventType() == TokenTransfer.EventType.MINT || isZero(t.getFromAddress());
    }

    private static boolean isBurn(TokenTransfer t) {
        return t.getEventType() == TokenTransfer.EventType.BURN || isZero(t.getToAddress());
    }

    private static boolean isZero(String address) {
        return address != null && address.equalsIgnoreCase(ZERO);
    }
}
