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

    List<AppUser> findByLegalEntityIdOrderByFullNameAscEmailAsc(UUID legalEntityId);

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
}
