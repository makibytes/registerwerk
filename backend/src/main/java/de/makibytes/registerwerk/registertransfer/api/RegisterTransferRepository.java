package de.makibytes.registerwerk.registertransfer.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RegisterTransferRepository extends JpaRepository<RegisterTransfer, UUID> {

    List<RegisterTransfer> findByAssetIdOrderByInitiatedAtDesc(UUID assetId);

    /** A given asset may have at most one transfer in flight at a time. */
    Optional<RegisterTransfer> findFirstByAssetIdAndStatusNotInOrderByInitiatedAtDesc(
            UUID assetId, List<TransferStatus> terminalStatuses);
}
