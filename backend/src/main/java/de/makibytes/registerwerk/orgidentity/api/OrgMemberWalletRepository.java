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

    /**
     * Active wallets belonging to the effective legal entity. Entity scope is essential for
     * company-wide views and for operator impersonation, whose JWT subject remains the operator.
     */
    @Query("""
            SELECT w FROM OrgMemberWallet w
            WHERE w.status = de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus.ACTIVE
              AND w.orgRegistrationId IN (
                  SELECT r.id FROM OrgRegistration r WHERE r.legalEntityId = :legalEntityId
              )
            """)
    List<OrgMemberWallet> findActiveByLegalEntityId(@Param("legalEntityId") UUID legalEntityId);

    Optional<OrgMemberWallet> findByIdAndOrgRegistrationId(UUID id, UUID orgRegistrationId);

    long countByOrgRegistrationIdAndStatus(UUID orgRegistrationId, MemberWalletStatus status);

    /**
     * A binding that may still exist on-chain. Pending/failed removal is intentionally included
     * for uniqueness, while authorization callers still require {@code status == ACTIVE}.
     */
    @Query("""
            SELECT w FROM OrgMemberWallet w
            WHERE w.chainConfigId = :chainConfigId
              AND lower(w.walletAddress) = lower(:walletAddress)
              AND w.status IN (de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus.PENDING,
                               de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus.ACTIVE,
                               de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus.REMOVAL_PENDING,
                               de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus.REMOVAL_FAILED)
            """)
    Optional<OrgMemberWallet> findLiveBinding(
            @Param("chainConfigId") UUID chainConfigId,
            @Param("walletAddress") String walletAddress);
}
