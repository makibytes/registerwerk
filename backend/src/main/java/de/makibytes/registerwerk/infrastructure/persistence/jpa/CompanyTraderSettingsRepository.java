package de.makibytes.registerwerk.infrastructure.persistence.jpa;

import de.makibytes.registerwerk.domain.trading.CompanyTraderSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CompanyTraderSettingsRepository extends JpaRepository<CompanyTraderSettings, UUID> {
}
