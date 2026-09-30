package de.makibytes.registerwerk.wallet.api;

import de.makibytes.registerwerk.wallet.api.OperatorWallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperatorWalletRepository extends JpaRepository<OperatorWallet, UUID> {

    Optional<OperatorWallet> findByName(String name);

    List<OperatorWallet> findByType(OperatorWallet.WalletType type);

    boolean existsByType(OperatorWallet.WalletType type);

    // ── P4C-5 soft delete. OperatorWallet carries @SQLRestriction("deleted_at IS NULL"), so every
    // derived/JPQL query above only sees live wallets; tombstones are reached via native queries.

    @Query(value = "SELECT * FROM operator_wallet WHERE id = :id AND deleted_at IS NOT NULL", nativeQuery = true)
    Optional<OperatorWallet> findDeletedById(@Param("id") UUID id);

    @Query(value = "SELECT * FROM operator_wallet WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff", nativeQuery = true)
    List<OperatorWallet> findDeletedBefore(@Param("cutoff") java.time.Instant cutoff);

    /** Address (any case) of a live or tombstoned wallet — a deleted key is still "the registry's key" for forensics. */
    @Query(value = "SELECT COUNT(*) FROM operator_wallet WHERE lower(address) = lower(:address) AND deleted_at IS NULL", nativeQuery = true)
    long countLiveByAddress(@Param("address") String address);

    /** Tombstones the wallet; the name is freed for reuse by suffixing the id. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE operator_wallet SET deleted_at = now(), deleted_by = :by, deleted_approver_id = :approver, "
            + "name = left(name, 100) || '#deleted-' || substr(cast(id AS text), 1, 8), updated_at = now() "
            + "WHERE id = :id AND deleted_at IS NULL", nativeQuery = true)
    int softDelete(@Param("id") UUID id, @Param("by") UUID by, @Param("approver") UUID approver);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE operator_wallet SET deleted_at = NULL, deleted_by = NULL, deleted_approver_id = NULL, "
            + "name = :name, updated_at = now() WHERE id = :id AND deleted_at IS NOT NULL", nativeQuery = true)
    int restore(@Param("id") UUID id, @Param("name") String name);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM operator_wallet WHERE id = :id AND deleted_at IS NOT NULL", nativeQuery = true)
    int purge(@Param("id") UUID id);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM wallet_bootstrap_marker)", nativeQuery = true)
    boolean bootstrapCompleted();

    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO wallet_bootstrap_marker (id) VALUES (1) ON CONFLICT DO NOTHING", nativeQuery = true)
    void markBootstrapCompleted();
}
