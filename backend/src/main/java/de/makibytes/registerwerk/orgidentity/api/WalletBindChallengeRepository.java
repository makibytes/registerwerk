package de.makibytes.registerwerk.orgidentity.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletBindChallengeRepository extends JpaRepository<WalletBindChallenge, UUID> {

    @Query("""
            SELECT c FROM WalletBindChallenge c
            WHERE c.legalEntityId = :legalEntityId
              AND c.chainConfigId = :chainConfigId
              AND lower(c.walletAddress) = lower(:walletAddress)
              AND c.usedAt IS NULL
            ORDER BY c.createdAt DESC
            LIMIT 1
            """)
    Optional<WalletBindChallenge> findOpenChallenge(
            @Param("legalEntityId") UUID legalEntityId,
            @Param("chainConfigId") UUID chainConfigId,
            @Param("walletAddress") String walletAddress);
}
