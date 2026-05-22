package de.makibytes.registerwerk.trading.api;

import de.makibytes.registerwerk.trading.api.CompanyTraderSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CompanyTraderSettingsRepository extends JpaRepository<CompanyTraderSettings, UUID> {
}
