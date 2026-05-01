package de.makibytes.registerwerk.application.auth;

import de.makibytes.registerwerk.domain.asset.Asset;
import de.makibytes.registerwerk.domain.asset.AssetDeployment;
import de.makibytes.registerwerk.domain.asset.AssetHolder;
import de.makibytes.registerwerk.domain.entity.AppUser;
import de.makibytes.registerwerk.domain.entity.LegalEntity;
import de.makibytes.registerwerk.domain.enums.*;
import de.makibytes.registerwerk.domain.trading.*;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "registerwerk.seed-demo-data", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final LegalEntityRepository entities;
    private final AppUserRepository users;
    private final AssetRepository assets;
    private final AssetDeploymentRepository deployments;
    private final AssetHolderRepository holders;
    private final TradeListingRepository listings;
    private final TradeExecutionRepository executions;
    private final PasswordEncoder encoder;

    public DemoDataSeeder(
            LegalEntityRepository entities,
            AppUserRepository users,
            AssetRepository assets,
            AssetDeploymentRepository deployments,
            AssetHolderRepository holders,
            TradeListingRepository listings,
            TradeExecutionRepository executions,
            PasswordEncoder encoder) {
        this.entities = entities;
        this.users = users;
        this.assets = assets;
        this.deployments = deployments;
        this.holders = holders;
        this.listings = listings;
        this.executions = executions;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (entities.findByEntityNumber("DEMO-MC-001").isPresent()) {
            log.info("Demo data already present — skipping");
            return;
        }

        log.info("Seeding demo data…");

        // ── Companies ────────────────────────────────────────────────────────

        LegalEntity meridian = entity("DEMO-MC-001", "Meridian Capital AG",
                EntityType.ISSUER, "529900T8BM49AURSDO55", "HRB 12345 B",
                LocalDate.of(2008, 3, 14));

        LegalEntity aurora = entity("DEMO-AF-001", "Aurora Finance GmbH",
                EntityType.ISSUER, "391200XXJH3KFGH78210", "HRB 67890 M",
                LocalDate.of(2015, 7, 1));

        LegalEntity nordbank = entity("DEMO-NI-001", "Nordbank Invest AG",
                EntityType.INVESTOR, "529900W18LQJJN6SJ336", "HRB 11111 B",
                LocalDate.of(2001, 1, 15));

        LegalEntity rheinische = entity("DEMO-RK-001", "Rheinische Kapital GmbH",
                EntityType.INVESTOR, "529900QJE5G0YX1JA741", "HRB 22222 K",
                LocalDate.of(1998, 6, 20));

        LegalEntity elbe = entity("DEMO-EA-001", "Elbe Asset Partners GmbH",
                EntityType.INVESTOR, "529900HNOAA1KXQJUQ27", "HRB 33333 H",
                LocalDate.of(2011, 4, 3));

        LegalEntity frankfurtDigital = entity("DEMO-FD-001", "Frankfurt Digital Fonds AG",
                EntityType.INVESTOR, "5299001QLSJPNRVTPW90", "HRB 44444 F",
                LocalDate.of(2019, 2, 28));

        LegalEntity wuerttemberg = entity("DEMO-WI-001", "Württemberg Invest GmbH",
                EntityType.INVESTOR, "529900HBC3QRQHZ6QF57", "HRB 55555 S",
                LocalDate.of(2005, 9, 12));

        // ── Users ────────────────────────────────────────────────────────────

        user("heinz.weber@meridian-capital.de", "Heinz Weber",
                AppUserRole.COMPANY_ADMIN, meridian.getId());
        user("anna.schreiber@meridian-capital.de", "Anna Schreiber",
                AppUserRole.TRADER, meridian.getId());

        user("thomas.bauer@aurora-finance.de", "Thomas Bauer",
                AppUserRole.COMPANY_ADMIN, aurora.getId());
        user("lisa.hoffmann@aurora-finance.de", "Lisa Hoffmann",
                AppUserRole.TRADER, aurora.getId());

        user("stefan.koch@nordbank-invest.de", "Stefan Koch",
                AppUserRole.COMPANY_ADMIN, nordbank.getId());
        user("maria.braun@nordbank-invest.de", "Maria Braun",
                AppUserRole.TRADER, nordbank.getId());

        user("klaus.fischer@rheinische-kapital.de", "Klaus Fischer",
                AppUserRole.COMPANY_ADMIN, rheinische.getId());
        user("sabine.mueller@rheinische-kapital.de", "Sabine Müller",
                AppUserRole.TRADER, rheinische.getId());

        user("juergen.wagner@elbe-asset-partners.de", "Jürgen Wagner",
                AppUserRole.COMPANY_ADMIN, elbe.getId());
        user("petra.zimmermann@elbe-asset-partners.de", "Petra Zimmermann",
                AppUserRole.TRADER, elbe.getId());

        user("christoph.hartmann@fd-fonds.de", "Christoph Hartmann",
                AppUserRole.COMPANY_ADMIN, frankfurtDigital.getId());
        user("sandra.richter@fd-fonds.de", "Sandra Richter",
                AppUserRole.TRADER, frankfurtDigital.getId());

        user("bernd.lange@wi-invest.de", "Bernd Lange",
                AppUserRole.COMPANY_ADMIN, wuerttemberg.getId());
        user("ute.koenig@wi-invest.de", "Ute König",
                AppUserRole.TRADER, wuerttemberg.getId());

        // ── Assets ───────────────────────────────────────────────────────────

        // Issued bond (end-of-lifecycle, fully distributed)
        Asset greenBond = asset("DEMO-BOND-MC-001",
                "Meridian Green Bond 2024",
                "DE000A3H2XK2",
                meridian.getId(),
                TokenStandard.ERC20,
                AssetStatus.ISSUED,
                Jurisdiction.DE_EWPG,
                OnchainLevel.SIMPLE,
                Map.of(
                        "assetType", "BOND",
                        "currency", "EUR",
                        "nominalValue", 1000,
                        "totalSupply", 10000,
                        "maturityDate", "2029-12-31",
                        "interestRate", 4.5,
                        "description", "Green bond financing renewable energy projects in Germany and Austria"
                ));

        AssetDeployment greenBondDeploy = deployment(greenBond.getId(),
                Chain.ETHEREUM, Network.TESTNET,
                "0x4A2bE89cF7db5e2d698Da900Bd6e3DeC83B7cF11",
                "0x7a3f5bc99e1d38a7406db4e8c1db7e3a4e2d1c0b9a3f5e7d2c4b6a8e0f1d3c5",
                daysAgo(180));

        // Issued equity (ERC-3643 with compliance, being managed)
        Asset equityToken = asset("DEMO-EQ-MC-001",
                "Meridian Digital Equity Series A",
                "DE000A3H2XK3",
                meridian.getId(),
                TokenStandard.ERC3643,
                AssetStatus.ISSUED,
                Jurisdiction.DE_EWPG,
                OnchainLevel.CONTROL,
                Map.of(
                        "assetType", "EQUITY",
                        "currency", "EUR",
                        "nominalValue", 100,
                        "totalSupply", 50000,
                        "description", "Tokenized equity of Meridian Capital AG — Series A preferred shares"
                ));

        AssetDeployment equityDeploy = deployment(equityToken.getId(),
                Chain.POLYGON, Network.TESTNET,
                "0x9C3dF55A7cE1b86B5dFe3b3E1c2A4B6D8F0E2A4C",
                "0x3b9c7d1f5e2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6e8f0a2b4",
                daysAgo(120));

        // Issued fund (Aurora, SPL on Solana)
        Asset solarFund = asset("DEMO-FUND-AF-001",
                "Aurora Solar Infrastructure Fund",
                "DE000A3H9PL5",
                aurora.getId(),
                TokenStandard.SPL,
                AssetStatus.ISSUED,
                Jurisdiction.LU_CSSF,
                OnchainLevel.SIMPLE,
                Map.of(
                        "assetType", "FUND",
                        "currency", "EUR",
                        "navPerToken", 98.50,
                        "totalSupply", 100000,
                        "description", "Open-ended fund investing in utility-scale solar projects across Europe"
                ));

        AssetDeployment solarFundDeploy = deployment(solarFund.getId(),
                Chain.SOLANA, Network.TESTNET,
                "7xKwRtH3mPqVn9dBcF2eYsZ5oLjA4uNgTiXp8vQwE6M",
                "5Jkm3PqR8tNvXcF2eYsZ9oLjA4uNgTiXp8vQwE6MnB7wKxY1dHrC4sFtJgVzPq",
                daysAgo(90));

        // Issued infrastructure note (Aurora, ERC20, active settlement in progress)
        Asset infraNote = asset("DEMO-NOTE-AF-001",
                "Aurora Infrastructure Note 2025-I",
                "DE000A3H9PL6",
                aurora.getId(),
                TokenStandard.ERC20,
                AssetStatus.ISSUED,
                Jurisdiction.DE_EWPG,
                OnchainLevel.SIMPLE,
                Map.of(
                        "assetType", "NOTE",
                        "currency", "EUR",
                        "nominalValue", 500,
                        "totalSupply", 40000,
                        "maturityDate", "2028-06-30",
                        "interestRate", 5.25,
                        "description", "Senior secured note backed by infrastructure assets — first tranche"
                ));

        AssetDeployment infraNoteDeploy = deployment(infraNote.getId(),
                Chain.ETHEREUM, Network.TESTNET,
                "0xB7cF22A4C8D9E1F3A5B7C9D0E2F4A6B8C0D2E4F6",
                "0x1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2",
                daysAgo(30));

        // Asset pending approval (in review, no deployment yet)
        Asset infraBond2025 = asset("DEMO-BOND-MC-002",
                "Meridian Infrastructure Bond 2025",
                "DE000A3H2XK9",
                meridian.getId(),
                TokenStandard.ERC20,
                AssetStatus.PENDING_APPROVAL,
                Jurisdiction.DE_EWPG,
                OnchainLevel.SIMPLE,
                Map.of(
                        "assetType", "BOND",
                        "currency", "EUR",
                        "nominalValue", 1000,
                        "totalSupply", 25000,
                        "maturityDate", "2032-03-31",
                        "interestRate", 5.75,
                        "description", "Bond financing motorway and rail infrastructure in southern Germany — BaFin registration in progress"
                ));

        // Draft asset (early stage, not yet submitted)
        asset("DEMO-COMM-AF-001",
                "Aurora Commodity Access Token",
                null,
                aurora.getId(),
                TokenStandard.ERC1155,
                AssetStatus.DRAFT,
                Jurisdiction.LI_TVTG,
                OnchainLevel.NONE,
                Map.of(
                        "assetType", "COMMODITY",
                        "currency", "EUR",
                        "description", "Multi-class commodity exposure token — TVTG structuring in progress"
                ));

        // Confidential ERC-20 (showcase of Zama fhEVM)
        Asset confEquity = asset("DEMO-CONF-MC-001",
                "Meridian Confidential Equity Token",
                null,
                meridian.getId(),
                TokenStandard.CONF_ERC20,
                AssetStatus.ISSUED,
                Jurisdiction.DE_EWPG,
                OnchainLevel.CONTROL,
                Map.of(
                        "assetType", "EQUITY",
                        "currency", "EUR",
                        "totalSupply", 5000,
                        "description", "Confidential equity token using Zama fhEVM — balances are encrypted on-chain"
                ));

        AssetDeployment confEquityDeploy = deployment(confEquity.getId(),
                Chain.ETHEREUM, Network.TESTNET,
                "0xC8dE33B5D9F1E2A4B6C8D0E2F4A6B8C0D2E4F6A8",
                "0x9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1f0e9d8c7b6a5f4e3d2c1b0a9f8",
                daysAgo(45));

        // ── Holders ──────────────────────────────────────────────────────────

        // Meridian Green Bond holders
        AssetHolder gbNordbank = holder(greenBond.getId(), nordbank.getId(),
                "0x1A2B3C4D5E6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B",
                "0xd4a5e6f7d8c9b0a1e2f3d4c5b6a7e8f9d0c1b2a3",
                bd("5000"), daysAgo(175));

        AssetHolder gbRheinische = holder(greenBond.getId(), rheinische.getId(),
                "0x2B3C4D5E6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B1C",
                "0xe5b6f7a8e9c0d1b2f3e4d5c6b7a8f9e0d1c2b3a4",
                bd("3000"), daysAgo(175));

        AssetHolder gbAurora = holder(greenBond.getId(), aurora.getId(),
                "0x3C4D5E6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B1C2D",
                "0xf6c7a8b9f0d1e2c3a4f5e6d7c8b9a0f1e2d3c4b5",
                bd("2000"), daysAgo(170));

        // Meridian Equity Token holders
        AssetHolder eqElbe = holder(equityToken.getId(), elbe.getId(),
                "0x4D5E6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B1C2D3E",
                "0xa7d8b9c0a1e2f3d4b5c6a7d8e9f0a1b2c3d4e5f6",
                bd("10000"), daysAgo(115));

        AssetHolder eqFrankfurt = holder(equityToken.getId(), frankfurtDigital.getId(),
                "0x5E6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B1C2D3E4F",
                "0xb8e9c0d1b2f3a4e5c6d7b8e9f0a1b2c3d4e5f6a7",
                bd("8000"), daysAgo(115));

        AssetHolder eqWuerttemberg = holder(equityToken.getId(), wuerttemberg.getId(),
                "0x6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B1C2D3E4F5A",
                "0xc9f0d1e2c3a4b5f6d7e8c9f0a1b2c3d4e5f6a7b8",
                bd("12000"), daysAgo(118));

        // Aurora Solar Fund holders
        AssetHolder sfNordbank = holder(solarFund.getId(), nordbank.getId(),
                "ApUVE9cHPeUJPFVnqmzQXP3K8j3tBHdFkLxY7mWoQ8S",
                null,
                bd("15000"), daysAgo(85));

        AssetHolder sfElbe = holder(solarFund.getId(), elbe.getId(),
                "BqWF0dIPfVKQGWyornARYQ4L9k4uCIeGlMzZ8nXpR9T",
                null,
                bd("10000"), daysAgo(85));

        AssetHolder sfRheinische = holder(solarFund.getId(), rheinische.getId(),
                "CrXG1eJQgWLRHXzpsoBSZR5M0l5vDJfHmNaA9oYqS0U",
                null,
                bd("10000"), daysAgo(88));

        // Aurora infra note holders (active settlement — recently acquired)
        AssetHolder inRheinische = holder(infraNote.getId(), rheinische.getId(),
                "0x7A8B9C0D1E2F3A4B5C6D7E8F9A0B1C2D3E4F5A6B",
                "0xd0a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9",
                bd("20000"), daysAgo(25));

        AssetHolder inWuerttemberg = holder(infraNote.getId(), wuerttemberg.getId(),
                "0x8B9C0D1E2F3A4B5C6D7E8F9A0B1C2D3E4F5A6B7C",
                "0xe1b2c3d4e5f6a7b8c9d0e1b2c3d4e5f6a7b8c9d0",
                bd("15000"), daysAgo(25));

        AssetHolder inFrankfurt = holder(infraNote.getId(), frankfurtDigital.getId(),
                "0x9C0D1E2F3A4B5C6D7E8F9A0B1C2D3E4F5A6B7C8D",
                "0xf2c3d4e5f6a7b8c9d0f2c3d4e5f6a7b8c9d0e1b2",
                bd("10000"), daysAgo(28));

        // Meridian Confidential Equity holders
        holder(confEquity.getId(), nordbank.getId(),
                "0xA1B2C3D4E5F6A7B8C9D0A1B2C3D4E5F6A7B8C9D0",
                "0xa3b4c5d6e7f8a9b0c1d2a3b4c5d6e7f8a9b0c1d2",
                bd("1500"), daysAgo(40));

        holder(confEquity.getId(), elbe.getId(),
                "0xB2C3D4E5F6A7B8C9D0A1B2C3D4E5F6A7B8C9D0A1",
                "0xb4c5d6e7f8a9b0c1d2b4c5d6e7f8a9b0c1d2e3f4",
                bd("2000"), daysAgo(40));

        // ── Trade listings (active) ───────────────────────────────────────────

        // Nordbank selling Green Bond — OPEN
        TradeListing listGBNordbank = listing(
                greenBond.getId(), "DEMO-BOND-MC-001",
                "Meridian Green Bond 2024", "DE000A3H2XK2",
                TradingAssetType.BOND, TokenStandard.ERC20, Chain.ETHEREUM,
                nordbank.getId(), gbNordbank.getId(),
                bd("2000"), bd("2000"), bd("1050.00"),
                ListingStatus.OPEN,
                Set.of(PaymentOption.OFFCHAIN_SEPA, PaymentOption.STABLECOIN));

        // Elbe selling Equity Token — PARTIALLY_FILLED (3000 of 5000 sold)
        TradeListing listEQElbe = listing(
                equityToken.getId(), "DEMO-EQ-MC-001",
                "Meridian Digital Equity Series A", "DE000A3H2XK3",
                TradingAssetType.EQUITY, TokenStandard.ERC3643, Chain.POLYGON,
                elbe.getId(), eqElbe.getId(),
                bd("5000"), bd("2000"), bd("120.00"),
                ListingStatus.PARTIALLY_FILLED,
                Set.of(PaymentOption.OFFCHAIN_SEPA));

        // Rheinische selling Solar Fund — OPEN
        TradeListing listSFRheinische = listing(
                solarFund.getId(), "DEMO-FUND-AF-001",
                "Aurora Solar Infrastructure Fund", "DE000A3H9PL5",
                TradingAssetType.FUND, TokenStandard.SPL, Chain.SOLANA,
                rheinische.getId(), sfRheinische.getId(),
                bd("4000"), bd("4000"), bd("98.50"),
                ListingStatus.OPEN,
                Set.of(PaymentOption.OFFCHAIN_SEPA, PaymentOption.CBMT));

        // Wuerttemberg selling Infra Note — OPEN (active settlement showcase)
        listing(
                infraNote.getId(), "DEMO-NOTE-AF-001",
                "Aurora Infrastructure Note 2025-I", "DE000A3H9PL6",
                TradingAssetType.NOTE, TokenStandard.ERC20, Chain.ETHEREUM,
                wuerttemberg.getId(), inWuerttemberg.getId(),
                bd("5000"), bd("5000"), bd("502.50"),
                ListingStatus.OPEN,
                Set.of(PaymentOption.OFFCHAIN_SEPA, PaymentOption.STABLECOIN, PaymentOption.CBMT));

        // ── Trade executions (historical) ────────────────────────────────────

        // Frankfurt bought 3000 equity from Elbe's partially-filled listing
        execution(listEQElbe.getId(),
                TradingAssetType.EQUITY, TokenStandard.ERC3643, Chain.POLYGON,
                equityToken.getId(), "DEMO-EQ-MC-001", "Meridian Digital Equity Series A", "DE000A3H2XK3",
                elbe.getId(), eqElbe.getId(),
                frankfurtDigital.getId(), eqFrankfurt.getId(),
                OrderType.MARKET, bd("3000"), bd("3000"), bd("118.00"),
                PaymentOption.OFFCHAIN_SEPA, SettlementStatus.SETTLED,
                "0xE3F4A5B6C7D8A1B2C3D4E5F6A7B8C9D0E1F2A3B4",
                daysAgo(15), daysAgo(15));

        // Nordbank bought 1000 Aurora Solar Fund (past filled listing — simulate history)
        TradeListing pastSFListing = listing(
                solarFund.getId(), "DEMO-FUND-AF-001",
                "Aurora Solar Infrastructure Fund", "DE000A3H9PL5",
                TradingAssetType.FUND, TokenStandard.SPL, Chain.SOLANA,
                elbe.getId(), sfElbe.getId(),
                bd("2000"), bd("0"), bd("96.00"),
                ListingStatus.FILLED,
                Set.of(PaymentOption.OFFCHAIN_SEPA));

        execution(pastSFListing.getId(),
                TradingAssetType.FUND, TokenStandard.SPL, Chain.SOLANA,
                solarFund.getId(), "DEMO-FUND-AF-001", "Aurora Solar Infrastructure Fund", "DE000A3H9PL5",
                elbe.getId(), sfElbe.getId(),
                nordbank.getId(), sfNordbank.getId(),
                OrderType.LIMIT, bd("2000"), bd("2000"), bd("96.00"),
                PaymentOption.OFFCHAIN_SEPA, SettlementStatus.SETTLED,
                "9xKz7YvRmT4jNpWoQ2bFcGhDsAuE8iLX5eMnPtVwHsJ",
                daysAgo(75), daysAgo(74));

        // Rheinische bought 1500 Green Bond from a previous (now-filled) listing by Nordbank
        TradeListing pastGBListing = listing(
                greenBond.getId(), "DEMO-BOND-MC-001",
                "Meridian Green Bond 2024", "DE000A3H2XK2",
                TradingAssetType.BOND, TokenStandard.ERC20, Chain.ETHEREUM,
                nordbank.getId(), gbNordbank.getId(),
                bd("1500"), bd("0"), bd("1020.00"),
                ListingStatus.FILLED,
                Set.of(PaymentOption.OFFCHAIN_SEPA, PaymentOption.STABLECOIN));

        execution(pastGBListing.getId(),
                TradingAssetType.BOND, TokenStandard.ERC20, Chain.ETHEREUM,
                greenBond.getId(), "DEMO-BOND-MC-001", "Meridian Green Bond 2024", "DE000A3H2XK2",
                nordbank.getId(), gbNordbank.getId(),
                rheinische.getId(), gbRheinische.getId(),
                OrderType.MARKET, bd("1500"), bd("1500"), bd("1020.00"),
                PaymentOption.OFFCHAIN_SEPA, SettlementStatus.SETTLED,
                "0xF4A5B6C7D8E9F0A1B2C3D4E5F6A7B8C9D0E1F2A3",
                daysAgo(160), daysAgo(160));

        // Wuerttemberg bought Aurora Infra Note from Frankfurt (pending settlement — recent)
        execution(listGBNordbank.getId(),
                TradingAssetType.NOTE, TokenStandard.ERC20, Chain.ETHEREUM,
                infraNote.getId(), "DEMO-NOTE-AF-001", "Aurora Infrastructure Note 2025-I", "DE000A3H9PL6",
                frankfurtDigital.getId(), inFrankfurt.getId(),
                wuerttemberg.getId(), inWuerttemberg.getId(),
                OrderType.MARKET, bd("3000"), bd("3000"), bd("501.00"),
                PaymentOption.STABLECOIN, SettlementStatus.PENDING,
                "0xA5B6C7D8E9F0A1B2C3D4E5F6A7B8C9D0E1F2A3B4",
                daysAgo(2), null);

        log.info("Demo data seeded: 7 companies, 14 users, 7 assets, 4 active listings, 4 trade executions");
    }

    // ── Builder helpers ───────────────────────────────────────────────────────

    private LegalEntity entity(String number, String name, EntityType type,
                               String lei, String regNumber, LocalDate incorporated) {
        LegalEntity e = new LegalEntity();
        e.setEntityNumber(number);
        e.setCurrentName(name);
        e.setType(type);
        e.setStatus(EntityStatus.ACTIVE);
        e.setKycStatus(KycStatus.APPROVED);
        e.setKycExpiryDate(LocalDate.now().plusYears(2));
        e.setLeiCode(lei);
        e.setRegistrationNumber(regNumber);
        e.setRegistrationCountry("DE");
        e.setIncorporationDate(incorporated);
        return entities.save(e);
    }

    private AppUser user(String email, String fullName, AppUserRole role, java.util.UUID entityId) {
        return users.findByEmailIgnoreCase(email).orElseGet(() -> {
            AppUser u = new AppUser();
            u.setEmail(email);
            u.setFullName(fullName);
            u.setPasswordHash(encoder.encode("demo1234!"));
            u.setRole(role);
            u.setLegalEntityId(entityId);
            u.setAuthProvider(UserAuthProvider.LOCAL);
            u.setEnabled(true);
            return users.save(u);
        });
    }

    private Asset asset(String number, String name, String isin,
                        java.util.UUID issuerId, TokenStandard standard,
                        AssetStatus status, Jurisdiction jurisdiction,
                        OnchainLevel level, Map<String, Object> publicData) {
        Asset a = new Asset();
        a.setAssetNumber(number);
        a.setName(name);
        a.setIsin(isin);
        a.setIssuerId(issuerId);
        a.setTokenStandard(standard);
        a.setStatus(status);
        a.setJurisdiction(jurisdiction);
        a.setOnchainLevel(level);
        a.setPublicData(publicData);
        if (status == AssetStatus.ISSUED) {
            a.setLastHolderSyncTime(daysAgo(1));
        }
        return assets.save(a);
    }

    private AssetDeployment deployment(java.util.UUID assetId, Chain chain, Network network,
                                       String address, String tx, Instant deployedAt) {
        AssetDeployment d = new AssetDeployment();
        d.setAssetId(assetId);
        d.setChain(chain);
        d.setNetwork(network);
        d.setContractAddress(address);
        d.setDeployedByTx(tx.length() > 66 ? tx.substring(0, 66) : tx);
        d.setDeployedAt(deployedAt);
        d.setDeploymentStatus(AssetDeployment.DeploymentStatus.CONFIRMED);
        return deployments.save(d);
    }

    private AssetHolder holder(java.util.UUID assetId, java.util.UUID investorId,
                               String wallet, String whitelistTx,
                               BigDecimal amount, Instant acquired) {
        AssetHolder h = new AssetHolder();
        h.setAssetId(assetId);
        h.setInvestorId(investorId);
        h.setWalletAddress(wallet);
        h.setNominalAmount(amount);
        h.setWhitelisted(true);
        h.setWhitelistTxHash(whitelistTx);
        h.setAcquisitionDate(acquired.atZone(java.time.ZoneOffset.UTC).toLocalDate());
        return holders.save(h);
    }

    private TradeListing listing(java.util.UUID assetId, String assetNumber, String assetName,
                                 String isin, TradingAssetType assetType, TokenStandard standard,
                                 Chain chain, java.util.UUID sellerEntityId, java.util.UUID sellerHolderId,
                                 BigDecimal total, BigDecimal available, BigDecimal price,
                                 ListingStatus status, Set<PaymentOption> paymentOptions) {
        TradeListing l = new TradeListing();
        l.setAssetId(assetId);
        l.setAssetNumber(assetNumber);
        l.setAssetName(assetName);
        l.setIsin(isin);
        l.setAssetType(assetType);
        l.setTokenStandard(standard);
        l.setChain(chain);
        l.setSellerEntityId(sellerEntityId);
        l.setSellerHolderId(sellerHolderId);
        l.setQuantityTotal(total);
        l.setQuantityAvailable(available);
        l.setPricePerUnit(price);
        l.setStatus(status);
        l.setVenueCode(TradingVenueCode.SIMULATED);
        l.setAllowedPaymentOptions(paymentOptions);
        return listings.save(l);
    }

    private void execution(java.util.UUID listingId,
                           TradingAssetType assetType, TokenStandard standard, Chain chain,
                           java.util.UUID assetId, String assetNumber, String assetName, String isin,
                           java.util.UUID sellerEntityId, java.util.UUID sellerHolderId,
                           java.util.UUID buyerEntityId, java.util.UUID buyerHolderId,
                           OrderType orderType, BigDecimal requested, BigDecimal executed,
                           BigDecimal unitPrice, PaymentOption payment, SettlementStatus settlement,
                           String walletAddress, Instant createdAt, Instant settledAt) {
        TradeExecution ex = new TradeExecution();
        ex.setListingId(listingId);
        ex.setVenueCode(TradingVenueCode.SIMULATED);
        ex.setAssetType(assetType);
        ex.setTokenStandard(standard);
        ex.setChain(chain);
        ex.setAssetId(assetId);
        ex.setAssetNumber(assetNumber);
        ex.setAssetName(assetName);
        ex.setIsin(isin);
        ex.setSellerEntityId(sellerEntityId);
        ex.setSellerHolderId(sellerHolderId);
        ex.setBuyerEntityId(buyerEntityId);
        ex.setBuyerHolderId(buyerHolderId);
        ex.setOrderType(orderType);
        ex.setRequestedQuantity(requested);
        ex.setExecutedQuantity(executed);
        ex.setUnitPrice(unitPrice);
        ex.setTotalPrice(executed.multiply(unitPrice));
        ex.setPaymentOption(payment);
        ex.setSettlementStatus(settlement);
        ex.setWalletPreferenceMode(WalletPreferenceMode.CUSTOM_ADDRESS);
        ex.setWalletAddress(walletAddress);
        ex.setSettledAt(settledAt);
        executions.save(ex);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }
}
