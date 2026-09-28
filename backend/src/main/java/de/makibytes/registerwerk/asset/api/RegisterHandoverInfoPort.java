package de.makibytes.registerwerk.asset.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * T3-07: who took over the register of a {@link AssetStatus#TRANSFERRED_OUT} asset, so customer
 * facing 409 responses can say "register transferred to X on Y". Implemented by the
 * {@code registertransfer} module (which owns the handover record).
 */
public interface RegisterHandoverInfoPort {

    Optional<Handover> completedHandover(UUID assetId);

    record Handover(String successorName, Instant completedAt) {
        public String describe() {
            return "register transferred to " + successorName + (completedAt != null ? " on " + completedAt.toString().substring(0, 10) : "");
        }
    }
}
