package de.makibytes.registerwerk.deployment.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AssetBondTermsRepository extends JpaRepository<AssetBondTerms, UUID> {

    List<AssetBondTerms> findByBondStatus(BondStatus bondStatus);

    @Query("SELECT t FROM AssetBondTerms t WHERE t.bondStatus = 'ACTIVE' AND t.maturityDate <= :today")
    List<AssetBondTerms> findMaturedButNotTransitioned(@Param("today") LocalDate today);
}
