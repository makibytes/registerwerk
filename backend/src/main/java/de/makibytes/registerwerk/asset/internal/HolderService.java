package de.makibytes.registerwerk.asset.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;

/**
 * Manages asset holder records (investor positions).
 */
@Service
@Transactional
public class HolderService {

    private static final Logger log = LoggerFactory.getLogger(HolderService.class);

    private final AssetHolderRepository assetHolderRepository;

    public HolderService(AssetHolderRepository assetHolderRepository) {
        this.assetHolderRepository = assetHolderRepository;
    }

    /**
     * Adds a new holder record linking an investor to a specific wallet address and nominal amount.
     */
    public AssetHolder addHolder(UUID assetId, UUID investorId, String walletAddress, BigDecimal nominalAmount) {
        AssetHolder holder = new AssetHolder();
        holder.setAssetId(assetId);
        holder.setInvestorId(investorId);
        holder.setWalletAddress(walletAddress);
        holder.setNominalAmount(nominalAmount != null ? nominalAmount : BigDecimal.ZERO);
        holder.setAcquisitionDate(LocalDate.now());
        AssetHolder saved = assetHolderRepository.save(holder);
        log.info("Added holder: assetId={}, investorId={}, wallet={}", assetId, investorId, walletAddress);
        return saved;
    }

    /**
     * Removes a holder record (hard delete).
     */
    public void removeHolder(UUID holderId, UUID actorId) {
        AssetHolder holder = assetHolderRepository.findById(holderId)
            .orElseThrow(() -> new EntityNotFoundException("AssetHolder", holderId));
        assetHolderRepository.delete(holder);
        log.info("Removed holder: holderId={}, by={}", holderId, actorId);
    }

    @Transactional(readOnly = true)
    public Page<AssetHolder> listHolders(UUID assetId, Pageable pageable) {
        return assetHolderRepository.findByAssetId(assetId, pageable);
    }

    @Transactional(readOnly = true)
    public AssetHolder getHolder(UUID holderId) {
        return assetHolderRepository.findById(holderId)
            .orElseThrow(() -> new EntityNotFoundException("AssetHolder", holderId));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<AssetHolder> findByAssetIdAndWalletAddress(UUID assetId, String walletAddress) {
        return assetHolderRepository.findByAssetIdAndWalletAddress(assetId, walletAddress);
    }
}
