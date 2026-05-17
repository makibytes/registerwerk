package de.makibytes.registerwerk.wallet.api;

import de.makibytes.registerwerk.wallet.api.WalletChainDefault;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletChainDefaultRepository extends JpaRepository<WalletChainDefault, UUID> {

    /** Eager-fetches both chainConfig and wallet to avoid LazyInitializationException in controllers. */
    @Query("SELECT d FROM WalletChainDefault d JOIN FETCH d.chainConfig JOIN FETCH d.wallet WHERE d.chainConfigId = :chainConfigId")
    Optional<WalletChainDefault> findByChainConfigId(UUID chainConfigId);

    @Query("SELECT d FROM WalletChainDefault d JOIN FETCH d.chainConfig JOIN FETCH d.wallet WHERE d.wallet.id = :walletId")
    List<WalletChainDefault> findByWallet_Id(UUID walletId);

    @Query("SELECT d FROM WalletChainDefault d JOIN FETCH d.chainConfig JOIN FETCH d.wallet")
    List<WalletChainDefault> findAllWithAssociations();

    @Query("SELECT d FROM WalletChainDefault d JOIN FETCH d.chainConfig JOIN FETCH d.wallet WHERE d.chainConfig.chainType = de.makibytes.registerwerk.chain.api.ChainConfig.ChainType.EVM")
    List<WalletChainDefault> findAllEvmDefaults();

    @Query("SELECT d FROM WalletChainDefault d JOIN FETCH d.chainConfig JOIN FETCH d.wallet WHERE d.chainConfig.chainType = de.makibytes.registerwerk.chain.api.ChainConfig.ChainType.SOLANA")
    List<WalletChainDefault> findAllSolanaDefaults();

    @Query("SELECT d FROM WalletChainDefault d JOIN FETCH d.chainConfig JOIN FETCH d.wallet WHERE d.chainConfig.identifier = :identifier")
    Optional<WalletChainDefault> findByChainIdentifier(String identifier);
}
