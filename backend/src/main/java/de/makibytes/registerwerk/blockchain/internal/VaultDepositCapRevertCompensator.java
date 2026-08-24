package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.deployment.api.AssetVaultState;
import de.makibytes.registerwerk.deployment.api.AssetVaultStateRepository;
import de.makibytes.registerwerk.finality.api.BlockIdentity;
import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Restores the previous vault deposit cap when its exact confirming block is retracted. */
@Component
class VaultDepositCapRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "VAULT_DEPOSIT_CAP_CONFIRMED";

    private final AssetVaultStateRepository repository;

    VaultDepositCapRevertCompensator(AssetVaultStateRepository repository) {
        this.repository = repository;
    }

    @Override
    public String effectType() {
        return EFFECT_TYPE;
    }

    @Override
    public CompensationCategory category() {
        return CompensationCategory.INVERSE_FLIP;
    }

    @Override
    public CompensationOutcome compensate(ChainEffectRecord effect) {
        AssetVaultState state = repository.findByAssetIdForUpdate(effect.entityId()).orElse(null);
        if (state == null) {
            return new CompensationOutcome.NotApplicable(
                    "AssetVaultState " + effect.entityId() + " no longer exists");
        }
        if (!effect.chainConfigId().equals(state.getDepositCapChainConfigId())
                || !BlockIdentity.sameIncarnation(
                        state.getDepositCapBlockNumber(), state.getDepositCapBlockHash(),
                        effect.blockNumber(), effect.blockHash())
                || !BlockIdentity.sameHash(effect.txHash(), state.getDepositCapConfirmedTxHash())) {
            return new CompensationOutcome.NotApplicable(
                    "Deposit cap has been superseded by a different block occurrence");
        }

        BigInteger expectedAfter = cap(effect.afterState());
        if (expectedAfter == null || !expectedAfter.equals(state.getDepositCap())) {
            return new CompensationOutcome.Failed(
                    "Deposit cap projection no longer matches the journaled post-image", null);
        }
        boolean hasPendingCap = state.getPendingDepositCap() != null;
        boolean hasPendingTx = state.getDepositCapTxHash() != null;
        if (hasPendingCap != hasPendingTx) {
            return new CompensationOutcome.Failed(
                    "Deposit cap has a partial pending intent", null);
        }

        BigInteger before = cap(effect.beforeState());
        PreviousOccurrence previous;
        try {
            previous = previousOccurrence(effect.beforeState());
        } catch (IllegalArgumentException ex) {
            return new CompensationOutcome.Failed(
                    "Deposit cap journal has malformed previous provenance", ex);
        }
        state.setDepositCap(before);
        // Keep the newest retracted intent pending. During an LIFO unwind, compensating cap2 first
        // puts cap2 here; compensating older cap1 must restore cap1's pre-image without replacing
        // the user's later desired value with cap1.
        if (!hasPendingCap) {
            state.setPendingDepositCap(expectedAfter);
            state.setDepositCapTxHash(effect.txHash());
        }
        state.setDepositCapChainConfigId(previous.chainConfigId());
        state.setDepositCapBlockNumber(previous.blockNumber());
        state.setDepositCapBlockHash(previous.blockHash());
        state.setDepositCapConfirmedTxHash(previous.txHash());
        repository.save(state);
        return new CompensationOutcome.Compensated(
                "Restored the previous deposit cap for asset " + effect.entityId());
    }

    private static BigInteger cap(Map<String, Object> state) {
        if (state == null || state.get("depositCap") == null) {
            return null;
        }
        return new BigInteger(state.get("depositCap").toString());
    }

    private static PreviousOccurrence previousOccurrence(Map<String, Object> state) {
        if (state == null) {
            return PreviousOccurrence.EMPTY;
        }
        UUID chainConfigId = uuid(state.get("chainConfigId"));
        Long blockNumber = longValue(state.get("blockNumber"));
        String blockHash = text(state.get("blockHash"));
        String txHash = text(state.get("txHash"));

        boolean hasAnyBlockIdentity = chainConfigId != null || blockNumber != null || blockHash != null || txHash != null;
        boolean hasCompleteBlockIdentity = chainConfigId != null && blockNumber != null && blockHash != null;
        if (hasAnyBlockIdentity && !hasCompleteBlockIdentity) {
            throw new IllegalArgumentException("partial block identity");
        }
        return new PreviousOccurrence(chainConfigId, blockNumber, blockHash, txHash);
    }

    private static UUID uuid(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("chainConfigId is not text");
        }
        return UUID.fromString(text);
    }

    private static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        long parsed;
        if (value instanceof BigInteger integer) {
            parsed = integer.longValueExact();
        } else if (value instanceof BigDecimal decimal) {
            parsed = decimal.longValueExact();
        } else if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            parsed = ((Number) value).longValue();
        } else if (value instanceof String text) {
            parsed = Long.parseLong(text);
        } else {
            throw new IllegalArgumentException("blockNumber is not an integer");
        }
        if (parsed < 0) {
            throw new IllegalArgumentException("negative blockNumber");
        }
        return parsed;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("provenance value is not non-blank text");
        }
        return text;
    }

    private record PreviousOccurrence(UUID chainConfigId, Long blockNumber, String blockHash, String txHash) {
        private static final PreviousOccurrence EMPTY = new PreviousOccurrence(null, null, null, null);
    }
}
