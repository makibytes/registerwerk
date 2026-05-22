package de.makibytes.registerwerk.deployment.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AssetBondTermsRepository extends JpaRepository<AssetBondTerms, UUID> {
}
