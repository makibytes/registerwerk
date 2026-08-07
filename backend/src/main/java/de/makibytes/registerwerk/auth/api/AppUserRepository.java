package de.makibytes.registerwerk.auth.api;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID>, JpaSpecificationExecutor<AppUser> {

    @Query("select u from AppUser u where lower(u.email) = lower(:email)")
    Optional<AppUser> findByEmailIgnoreCase(@Param("email") String email);

    /** Primary lookup for an Entra principal: the token's {@code oid} is stable, its email is not. */
    Optional<AppUser> findByEntraObjectId(UUID entraObjectId);

    /** Primary lookup for a non-Entra OIDC principal: the token's {@code sub} is stable. */
    Optional<AppUser> findByExternalSubject(String externalSubject);

    List<AppUser> findByLegalEntityIdOrderByFullNameAscEmailAsc(UUID legalEntityId);

    /** Every account with any real access, for an access-review campaign snapshot — a disabled
     *  account has nothing to recertify. */
    List<AppUser> findByEnabledTrueOrderByEmailAsc();

    Optional<AppUser> findByIdAndLegalEntityId(UUID id, UUID legalEntityId);

    @Query("""
        select count(u) from AppUser u
        where u.legalEntityId = :legalEntityId
          and u.enabled = true
          and :role member of u.roles
          and (:excludeUserId is null or u.id <> :excludeUserId)
        """)
    long countEnabledUsersByLegalEntityIdAndRole(
            @Param("legalEntityId") UUID legalEntityId,
            @Param("role") AppUserRole role,
            @Param("excludeUserId") UUID excludeUserId);

    @Query("""
        select count(u) from AppUser u
        where u.enabled = true
          and :role member of u.roles
          and (:excludeUserId is null or u.id <> :excludeUserId)
        """)
    long countEnabledUsersWithRole(
            @Param("role") AppUserRole role,
            @Param("excludeUserId") UUID excludeUserId);

    /**
     * Counts enabled, TOTP-enrolled users with {@code role} — i.e. how many people could
     * actually act as the SECOND approver on a {@code requireSecondApprover} 4-eyes action
     * (which mints its step-up token only for TOTP-enrolled REGISTRY_ADMINs). Used by
     * {@code ProductionReadinessCheck} to detect the single-admin 4-eyes deadlock: with
     * fewer than 2, every dual-control endpoint (wallet export/delete, force-burn,
     * forced-transfer, org suspension, dApp approval, Sperrvermerk) is permanently
     * unreachable.
     */
    @Query("""
        select count(u) from AppUser u
        where u.enabled = true
          and u.totpEnabled = true
          and :role member of u.roles
        """)
    long countEnabledTotpEnrolledUsersWithRole(@Param("role") AppUserRole role);
}
