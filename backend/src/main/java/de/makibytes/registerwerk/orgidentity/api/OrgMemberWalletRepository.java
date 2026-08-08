package de.makibytes.registerwerk.orgidentity.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgMemberWalletRepository extends JpaRepository<OrgMemberWallet, UUID> {

    List<OrgMemberWallet> findByOrgRegistrationIdOrderByCreatedAtDesc(UUID orgRegistrationId);

    List<OrgMemberWallet> findByStatus(MemberWalletStatus status);

    /** Every wallet a given app user has bound, regardless of status — callers filter as needed. */
    List<OrgMemberWallet> findByAppUserId(UUID appUserId);

    Optional<OrgMemberWallet> findByIdAndOrgRegistrationId(UUID id, UUID orgRegistrationId);

    long countByOrgRegistrationIdAndStatus(UUID orgRegistrationId, MemberWalletStatus status);

    /** The live (pending or active) binding of a wallet on a chain, if any. */
    @Query("""
            SELECT w FROM OrgMemberWallet w
            WHERE w.chainConfigId = :chainConfigId
              AND lower(w.walletAddress) = lower(:walletAddress)
              AND w.status IN (de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus.PENDING,
                               de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus.ACTIVE)
            """)
    Optional<OrgMemberWallet> findLiveBinding(
            @Param("chainConfigId") UUID chainConfigId,
            @Param("walletAddress") String walletAddress);
}
