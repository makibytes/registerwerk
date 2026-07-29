package de.makibytes.registerwerk.bootstrap;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.GasSponsorshipPolicy;
import de.makibytes.registerwerk.deployment.api.GasSponsorshipPolicyRepository;
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import de.makibytes.registerwerk.dora.api.IctIncident;
import de.makibytes.registerwerk.dora.api.IctIncidentRepository;
import de.makibytes.registerwerk.dora.api.ThirdPartyProvider;
import de.makibytes.registerwerk.dora.api.ThirdPartyProviderRepository;
import de.makibytes.registerwerk.dora.api.ResilienceTest;
import de.makibytes.registerwerk.dora.api.ResilienceTestRepository;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.RpcNode;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.asset.api.*;
import de.makibytes.registerwerk.auth.api.*;
import de.makibytes.registerwerk.customer.api.*;
import de.makibytes.registerwerk.kyc.api.*;
import de.makibytes.registerwerk.chain.api.*;
import de.makibytes.registerwerk.trading.api.*;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.trading.api.TradeListingRepository;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.RpcNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "registerwerk.seed-demo-data", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /**
     * Runs before {@link EcosystemDemoDataSeeder}, which looks up the companies and
     * users seeded here by entity number / email.
     */
    @Override
    public int getOrder() {
        return 0;
    }

    private record NodeDef(String chainIdentifier, String url, String label, boolean enabled) {}

    @org.springframework.beans.factory.annotation.Value("${registerwerk.chains.canton.devnet.ledger-api-url:}")
    private String cantonDevnetLedgerUrl;

    private final LegalEntityRepository entities;
    private final AppUserRepository users;
    private final AssetRepository assets;
    private final AssetDeploymentRepository deployments;
    private final AssetHolderRepository holders;
    private final TradeListingRepository listings;
    private final TradeExecutionRepository executions;
    private final ChainConfigRepository chainConfigs;
    private final RpcNodeRepository rpcNodes;
    private final GasSponsorshipPolicyRepository gasSponsorshipPolicies;
    private final TokenTransferRepository tokenTransfers;
    private final IctIncidentRepository ictIncidents;
    private final ThirdPartyProviderRepository thirdPartyProviders;
    private final ResilienceTestRepository resilienceTests;
    private final PasswordEncoder encoder;
    private final KycDocumentRepository kycDocuments;
    private final KycDocumentContentRepository kycDocumentContents;
    private final KycJurisdictionApprovalRepository kycJurisdictionApprovals;
    private final HolderBlockRepository holderBlocks;

    /** Fixed Base32 TOTP secret shared by every demo-seeded operator user (never used in
     *  production — DefaultAdminSeeder's own admin is never enrolled by this class). Lets a
     *  developer compute a live 6-digit code via
     *  {@code StepUpTokenIssuer.generateTotp(DEMO_TOTP_SECRET, Instant.now().getEpochSecond()/30)}
     *  or add it to any authenticator app, so the step-up + dual-control (4-eyes) flows —
     *  holder blocks, force ops, ASSET_TOKEN_ADMIN grants, corporate-action settlement — are
     *  actually exercisable in demo mode instead of being silently unreachable
     *  (see ProductionReadinessCheck.checkDualControlAvailability). */
    private static final String DEMO_TOTP_SECRET = "JBSWY3DPEHPK3PXP";

    public DemoDataSeeder(
            LegalEntityRepository entities,
            AppUserRepository users,
            AssetRepository assets,
            AssetDeploymentRepository deployments,
            AssetHolderRepository holders,
            TradeListingRepository listings,
            TradeExecutionRepository executions,
            ChainConfigRepository chainConfigs,
            RpcNodeRepository rpcNodes,
            GasSponsorshipPolicyRepository gasSponsorshipPolicies,
            TokenTransferRepository tokenTransfers,
            IctIncidentRepository ictIncidents,
            ThirdPartyProviderRepository thirdPartyProviders,
            ResilienceTestRepository resilienceTests,
            PasswordEncoder encoder,
            KycDocumentRepository kycDocuments,
            KycDocumentContentRepository kycDocumentContents,
            KycJurisdictionApprovalRepository kycJurisdictionApprovals,
            HolderBlockRepository holderBlocks) {
        this.entities = entities;
        this.users = users;
        this.assets = assets;
        this.deployments = deployments;
        this.holders = holders;
        this.listings = listings;
        this.executions = executions;
        this.chainConfigs = chainConfigs;
        this.rpcNodes = rpcNodes;
        this.gasSponsorshipPolicies = gasSponsorshipPolicies;
        this.tokenTransfers = tokenTransfers;
        this.ictIncidents = ictIncidents;
        this.thirdPartyProviders = thirdPartyProviders;
        this.resilienceTests = resilienceTests;
        this.encoder = encoder;
        this.kycDocuments = kycDocuments;
        this.kycDocumentContents = kycDocumentContents;
        this.kycJurisdictionApprovals = kycJurisdictionApprovals;
        this.holderBlocks = holderBlocks;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        syncPublicNodes();

        if (entities.findByEntityNumber("DEMO-MC-001").isPresent()) {
            log.info("Demo business data already present — synced nodes only");
            return;
        }

        log.info("Seeding demo data…");

        // ── Operator users ───────────────────────────────────────────────────
        // DefaultAdminSeeder only ever seeds ONE REGISTRY_ADMIN (from DEFAULT_ADMIN_EMAIL/
        // _PASSWORD), and nothing enrols TOTP for it. Per
        // auth.internal.ProductionReadinessCheck.checkDualControlAvailability, every 4-eyes
        // (Vieraugenprinzip) action — holder blocks, forced transfer/approve/burn,
        // ASSET_TOKEN_ADMIN grants, corporate-action settlement, wallet export/delete, dApp
        // approval — needs a SECOND, distinct, TOTP-enrolled, enabled REGISTRY_ADMIN, or it is
        // silently unreachable. Seed one here (plus a COMPLIANCE_OFFICER and an AUDIT user, for
        // the compliance queue and read-only audit roles) so the whole step-up surface is
        // actually demoable, not just present in code.
        AppUser dualControlAdmin = operatorUser("dual-control.admin@registerwerk-demo.internal",
                "Registry Admin (2nd approver)", AppUserRole.REGISTRY_ADMIN);
        operatorUser("compliance.officer@registerwerk-demo.internal",
                "Compliance Officer (Demo)", AppUserRole.COMPLIANCE_OFFICER);
        operatorUser("audit@registerwerk-demo.internal",
                "Audit (Demo, read-only)", AppUserRole.AUDIT);

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

        // ── KYC baseline (documents + jurisdiction approval) per company ─────
        // Every entity above is created with kycStatus=APPROVED but — until now — zero
        // supporting KycDocuments, which made the KYC checklist / Registerauszug eligibility
        // machinery untestable in demo mode. Seed a representative (not exhaustive) document
        // set plus one DE_EWPG jurisdiction approval per company; all seven were incorporated
        // in Germany (registrationCountry="DE" — see entity()).
        java.util.UUID complianceOfficerId = users.findByEmailIgnoreCase("compliance.officer@registerwerk-demo.internal")
                .map(AppUser::getId).orElse(null);
        for (LegalEntity e : List.of(meridian, aurora, nordbank, rheinische, elbe, frankfurtDigital, wuerttemberg)) {
            seedKycBasics(e, complianceOfficerId);
        }

        // ── Users ────────────────────────────────────────────────────────────
        // Issuer-company admins additionally carry ISSUER (backs the AssetController
        // `hasAnyRole('REGISTRY_ADMIN','ISSUER','COMPANY_ADMIN')` check explicitly rather than
        // relying solely on the COMPANY_ADMIN carve-out, and gives
        // SecurityUtils.primaryRole(auth, "ISSUER") in IssuerTokenController a real role to
        // report). Investor-company trader users additionally carry INVESTOR: without it,
        // nobody at any investor company can pass InvestmentController's
        // `hasAnyRole('INVESTOR','REGISTRY_ADMIN')` class-level check — COMPANY_ADMIN is
        // deliberately NOT in that allowlist (see trading/web/InvestmentController.java and the
        // customer frontend's matching WorkspaceService.ELIGIBILITY map) — so the entire
        // Investor workspace / "My Investments" screen was unreachable by any seeded user.

        userWithRoles("heinz.weber@meridian-capital.de", "Heinz Weber",
                Set.of(AppUserRole.COMPANY_ADMIN, AppUserRole.ISSUER), meridian.getId());
        user("anna.schreiber@meridian-capital.de", "Anna Schreiber",
                AppUserRole.TRADER, meridian.getId());

        userWithRoles("thomas.bauer@aurora-finance.de", "Thomas Bauer",
                Set.of(AppUserRole.COMPANY_ADMIN, AppUserRole.ISSUER), aurora.getId());
        user("lisa.hoffmann@aurora-finance.de", "Lisa Hoffmann",
                AppUserRole.TRADER, aurora.getId());

        user("stefan.koch@nordbank-invest.de", "Stefan Koch",
                AppUserRole.COMPANY_ADMIN, nordbank.getId());
        userWithRoles("maria.braun@nordbank-invest.de", "Maria Braun",
                Set.of(AppUserRole.TRADER, AppUserRole.INVESTOR), nordbank.getId());

        user("klaus.fischer@rheinische-kapital.de", "Klaus Fischer",
                AppUserRole.COMPANY_ADMIN, rheinische.getId());
        userWithRoles("sabine.mueller@rheinische-kapital.de", "Sabine Müller",
                Set.of(AppUserRole.TRADER, AppUserRole.INVESTOR), rheinische.getId());

        user("juergen.wagner@elbe-asset-partners.de", "Jürgen Wagner",
                AppUserRole.COMPANY_ADMIN, elbe.getId());
        userWithRoles("petra.zimmermann@elbe-asset-partners.de", "Petra Zimmermann",
                Set.of(AppUserRole.TRADER, AppUserRole.INVESTOR), elbe.getId());

        user("christoph.hartmann@fd-fonds.de", "Christoph Hartmann",
                AppUserRole.COMPANY_ADMIN, frankfurtDigital.getId());
        userWithRoles("sandra.richter@fd-fonds.de", "Sandra Richter",
                Set.of(AppUserRole.TRADER, AppUserRole.INVESTOR), frankfurtDigital.getId());

        user("bernd.lange@wi-invest.de", "Bernd Lange",
                AppUserRole.COMPANY_ADMIN, wuerttemberg.getId());
        userWithRoles("ute.koenig@wi-invest.de", "Ute König",
                Set.of(AppUserRole.TRADER, AppUserRole.INVESTOR), wuerttemberg.getId());

        // ── Assets ───────────────────────────────────────────────────────────

        // Issued bond (end-of-lifecycle, fully distributed)
        Asset greenBond = asset("DEMO-BOND-MC-001",
                "Meridian Green Bond 2024",
                "DE000A3H2XK1",
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
                "DE000A3H2XL9",
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
                "DE000A3H9PL0",
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
                "DE000A3H9PM8",
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

        // Issued semi-fungible bond on Starknet (Cairo ERC-3525 — slot+value)
        Asset starknetBond = asset("DEMO-BOND-MC-003",
                "Meridian Starknet Note 2026",
                "DE000A3H2XP2",
                meridian.getId(),
                TokenStandard.STARKNET_ERC3525,
                AssetStatus.ISSUED,
                Jurisdiction.DE_EWPG,
                OnchainLevel.SIMPLE,
                Map.of(
                        "assetType", "NOTE",
                        "currency", "EUR",
                        "nominalValue", 1000,
                        "totalSupply", 8000,
                        "maturityDate", "2030-09-30",
                        "interestRate", 4.9,
                        "description", "Semi-fungible note (Cairo ERC-3525) demonstrating slot+value positions on Starknet"
                ));

        deployment(starknetBond.getId(),
                Chain.STARKNET, Network.TESTNET,
                "0x04a2be89cf7db5e2d698da900bd6e3dec83b7cf11a2be89cf7db5e2d698da90",
                "0x07a3f5bc99e1d38a7406db4e8c1db7e3a4e2d1c0b9a3f5e7d2c4b6a8e0f1d3c5",
                daysAgo(45));

        // Issued classic asset on Stellar (Horizon)
        Asset stellarNote = asset("DEMO-NOTE-AF-002",
                "Aurora Stellar Trade Note",
                "DE000A3H9PN6",
                aurora.getId(),
                TokenStandard.STELLAR_ASSET,
                AssetStatus.ISSUED,
                Jurisdiction.LU_CSSF,
                OnchainLevel.SIMPLE,
                Map.of(
                        "assetType", "NOTE",
                        "currency", "EUR",
                        "nominalValue", 250,
                        "totalSupply", 20000,
                        "maturityDate", "2027-12-31",
                        "interestRate", 3.8,
                        "description", "Short-dated trade-finance note issued as a classic Stellar asset (Horizon)"
                ));

        deployment(stellarNote.getId(),
                Chain.STELLAR, Network.TESTNET,
                "GDQP2KPQGKIHYJGXNUIYOMHARUARCA7DJT5FO2FFOOKY3B2WSQHG4W37",
                "3b9c7d1f5e2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6e8f0a2b4c",
                daysAgo(60));

        // Issued fixed-rate bond on Canton (Daml Finance instrument, Daml Token Standard)
        Asset cantonBond = asset("DEMO-BOND-MC-004",
                "Meridian Canton Institutional Bond",
                "DE000A3H2XQ0",
                meridian.getId(),
                TokenStandard.DAML_BOND_FIXED,
                AssetStatus.ISSUED,
                Jurisdiction.LI_TVTG,
                OnchainLevel.SIMPLE,
                Map.of(
                        "assetType", "BOND",
                        "currency", "EUR",
                        "nominalValue", 5000,
                        "totalSupply", 4000,
                        "maturityDate", "2033-06-30",
                        "interestRate", 4.2,
                        "description", "Institutional fixed-rate bond issued via Daml Finance on Canton — synchronized privacy-preserving settlement"
                ));

        deployment(cantonBond.getId(),
                Chain.CANTON, Network.TESTNET,
                "1220a3f5bc99e1d38a7406db4e8c1db7e3a4e2d1c0b9a3f5e7d2c4b6a8e0f1d3",
                "1220a3f5bc99e1d38a7406db4e8c1db7e3a4e2d1c0b9a3f5e7d2c4b6a8e0f1d3c5",
                daysAgo(75));

        // Asset pending approval (in review, no deployment yet)
        Asset infraBond2025 = asset("DEMO-BOND-MC-002",
                "Meridian Infrastructure Bond 2025",
                "DE000A3H2XN5",
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

        // ── Sperrvermerk (§16 eWpG holder block) ─────────────────────────────
        // A pledge notation on Rheinische Kapital's Green Bond position — demonstrates the
        // HolderBlock lifecycle (docs/compliance/sperrvermerk.md) with real data instead of an
        // empty table. Written directly in its terminal ACTIVE state (no on-chain freeze tx —
        // ERC-20 Green Bond has no identity-registry freeze primitive, matching
        // sperrvermerk.md's documented "registry-layer block only" behaviour for non-ERC-3643
        // standards); createdBy/dualControlApproverId both point at the demo dual-control
        // admin since the seeder has only one extra distinct operator identity readily at hand
        // — this is a fixture, not a simulation of the live 4-eyes approval flow itself.
        holderBlock(rheinische.getId(), greenBond.getId(), gbRheinische.getWalletAddress(),
                HolderBlock.BlockType.PFANDRECHT,
                "Pfandvertrag zwischen Rheinische Kapital GmbH und Sparkasse Köln-Bonn vom 2026-05-03, Az. PF-2026-0417",
                dualControlAdmin.getId());

        // ── Trade listings (active) ───────────────────────────────────────────

        // Nordbank selling Green Bond — OPEN
        TradeListing listGBNordbank = listing(
                greenBond.getId(), "DEMO-BOND-MC-001",
                "Meridian Green Bond 2024", "DE000A3H2XK1",
                TradingAssetType.BOND, TokenStandard.ERC20, Chain.ETHEREUM,
                nordbank.getId(), gbNordbank.getId(),
                bd("2000"), bd("2000"), bd("1050.00"),
                ListingStatus.OPEN,
                Set.of(PaymentOption.OFFCHAIN_SEPA, PaymentOption.STABLECOIN));

        // Elbe selling Equity Token — PARTIALLY_FILLED (3000 of 5000 sold)
        TradeListing listEQElbe = listing(
                equityToken.getId(), "DEMO-EQ-MC-001",
                "Meridian Digital Equity Series A", "DE000A3H2XL9",
                TradingAssetType.EQUITY, TokenStandard.ERC3643, Chain.POLYGON,
                elbe.getId(), eqElbe.getId(),
                bd("5000"), bd("2000"), bd("120.00"),
                ListingStatus.PARTIALLY_FILLED,
                Set.of(PaymentOption.OFFCHAIN_SEPA));

        // Rheinische selling Solar Fund — OPEN
        TradeListing listSFRheinische = listing(
                solarFund.getId(), "DEMO-FUND-AF-001",
                "Aurora Solar Infrastructure Fund", "DE000A3H9PL0",
                TradingAssetType.FUND, TokenStandard.SPL, Chain.SOLANA,
                rheinische.getId(), sfRheinische.getId(),
                bd("4000"), bd("4000"), bd("98.50"),
                ListingStatus.OPEN,
                Set.of(PaymentOption.OFFCHAIN_SEPA, PaymentOption.CBMT));

        // Wuerttemberg selling Infra Note — OPEN (active settlement showcase)
        listing(
                infraNote.getId(), "DEMO-NOTE-AF-001",
                "Aurora Infrastructure Note 2025-I", "DE000A3H9PM8",
                TradingAssetType.NOTE, TokenStandard.ERC20, Chain.ETHEREUM,
                wuerttemberg.getId(), inWuerttemberg.getId(),
                bd("5000"), bd("5000"), bd("502.50"),
                ListingStatus.OPEN,
                Set.of(PaymentOption.OFFCHAIN_SEPA, PaymentOption.STABLECOIN, PaymentOption.CBMT));

        // ── Trade executions (historical) ────────────────────────────────────

        // Frankfurt bought 3000 equity from Elbe's partially-filled listing
        execution(listEQElbe.getId(),
                TradingAssetType.EQUITY, TokenStandard.ERC3643, Chain.POLYGON,
                equityToken.getId(), "DEMO-EQ-MC-001", "Meridian Digital Equity Series A", "DE000A3H2XL9",
                elbe.getId(), eqElbe.getId(),
                frankfurtDigital.getId(), eqFrankfurt.getId(),
                OrderType.MARKET, bd("3000"), bd("3000"), bd("118.00"),
                PaymentOption.OFFCHAIN_SEPA, SettlementStatus.SETTLED,
                "0xE3F4A5B6C7D8A1B2C3D4E5F6A7B8C9D0E1F2A3B4",
                daysAgo(15), daysAgo(15));

        // Nordbank bought 1000 Aurora Solar Fund (past filled listing — simulate history)
        TradeListing pastSFListing = listing(
                solarFund.getId(), "DEMO-FUND-AF-001",
                "Aurora Solar Infrastructure Fund", "DE000A3H9PL0",
                TradingAssetType.FUND, TokenStandard.SPL, Chain.SOLANA,
                elbe.getId(), sfElbe.getId(),
                bd("2000"), bd("0"), bd("96.00"),
                ListingStatus.FILLED,
                Set.of(PaymentOption.OFFCHAIN_SEPA));

        execution(pastSFListing.getId(),
                TradingAssetType.FUND, TokenStandard.SPL, Chain.SOLANA,
                solarFund.getId(), "DEMO-FUND-AF-001", "Aurora Solar Infrastructure Fund", "DE000A3H9PL0",
                elbe.getId(), sfElbe.getId(),
                nordbank.getId(), sfNordbank.getId(),
                OrderType.LIMIT, bd("2000"), bd("2000"), bd("96.00"),
                PaymentOption.OFFCHAIN_SEPA, SettlementStatus.SETTLED,
                "9xKz7YvRmT4jNpWoQ2bFcGhDsAuE8iLX5eMnPtVwHsJ",
                daysAgo(75), daysAgo(74));

        // Rheinische bought 1500 Green Bond from a previous (now-filled) listing by Nordbank
        TradeListing pastGBListing = listing(
                greenBond.getId(), "DEMO-BOND-MC-001",
                "Meridian Green Bond 2024", "DE000A3H2XK1",
                TradingAssetType.BOND, TokenStandard.ERC20, Chain.ETHEREUM,
                nordbank.getId(), gbNordbank.getId(),
                bd("1500"), bd("0"), bd("1020.00"),
                ListingStatus.FILLED,
                Set.of(PaymentOption.OFFCHAIN_SEPA, PaymentOption.STABLECOIN));

        execution(pastGBListing.getId(),
                TradingAssetType.BOND, TokenStandard.ERC20, Chain.ETHEREUM,
                greenBond.getId(), "DEMO-BOND-MC-001", "Meridian Green Bond 2024", "DE000A3H2XK1",
                nordbank.getId(), gbNordbank.getId(),
                rheinische.getId(), gbRheinische.getId(),
                OrderType.MARKET, bd("1500"), bd("1500"), bd("1020.00"),
                PaymentOption.OFFCHAIN_SEPA, SettlementStatus.SETTLED,
                "0xF4A5B6C7D8E9F0A1B2C3D4E5F6A7B8C9D0E1F2A3",
                daysAgo(160), daysAgo(160));

        // Wuerttemberg bought Aurora Infra Note from Frankfurt (pending settlement — recent)
        execution(listGBNordbank.getId(),
                TradingAssetType.NOTE, TokenStandard.ERC20, Chain.ETHEREUM,
                infraNote.getId(), "DEMO-NOTE-AF-001", "Aurora Infrastructure Note 2025-I", "DE000A3H9PM8",
                frankfurtDigital.getId(), inFrankfurt.getId(),
                wuerttemberg.getId(), inWuerttemberg.getId(),
                OrderType.MARKET, bd("3000"), bd("3000"), bd("501.00"),
                PaymentOption.STABLECOIN, SettlementStatus.PENDING,
                "0xA5B6C7D8E9F0A1B2C3D4E5F6A7B8C9D0E1F2A3B4",
                daysAgo(2), null);

        // ── Gas sponsorship (ERC-4337 paymaster budgets) ─────────────────────

        // Meridian's default: it sponsors gas for its own customers' transactions on every
        // future deployment unless a specific deployment overrides it below.
        gasSponsorshipPolicy(null, meridian.getId(), GasSponsorshipPolicy.Sponsor.ISSUER, bd("0.5"));

        // Aurora doesn't sponsor gas itself — the operator subsidizes Aurora's customers as
        // a growth incentive, demonstrating the other sponsor type at the issuer-default level.
        gasSponsorshipPolicy(null, aurora.getId(), GasSponsorshipPolicy.Sponsor.OPERATOR, bd("0.2"));

        // Deployment-level override: the operator sponsors gas specifically for the flagship
        // Green Bond deployment (a larger budget, to drive adoption of this issuance),
        // taking precedence over Meridian's own ISSUER-sponsored default above.
        gasSponsorshipPolicy(greenBondDeploy.getId(), null, GasSponsorshipPolicy.Sponsor.OPERATOR, bd("1.0"));

        // ── Indexed transfer history (green bond on Ethereum Sepolia) ────────
        // Mirrors the holder balances above (5000 / 3000 / 2000) so the asset history
        // tab and HolderDataService's transfer aggregation reconcile exactly.
        chainConfigs.findByIdentifier("ETHEREUM_SEPOLIA").ifPresent(sepolia -> {
            String gbContract = greenBondDeploy.getContractAddress();
            String nordbankW  = "0x1A2B3C4D5E6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B";
            String rheinW     = "0x2B3C4D5E6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B1C";
            String auroraW    = "0x3C4D5E6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B1C2D";
            String zero       = "0x0000000000000000000000000000000000000000";
            tokenTransfer(greenBond.getId(), greenBondDeploy.getId(), sepolia.getId(), gbContract,
                    zero, nordbankW, bd("5500"), TokenTransfer.EventType.MINT,
                    "0x71a1b2c3d4e5f60718293a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c01",
                    7_412_100L, 0, daysAgo(175));
            tokenTransfer(greenBond.getId(), greenBondDeploy.getId(), sepolia.getId(), gbContract,
                    zero, rheinW, bd("3000"), TokenTransfer.EventType.MINT,
                    "0x71a1b2c3d4e5f60718293a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c02",
                    7_412_101L, 0, daysAgo(175));
            tokenTransfer(greenBond.getId(), greenBondDeploy.getId(), sepolia.getId(), gbContract,
                    zero, auroraW, bd("1500"), TokenTransfer.EventType.MINT,
                    "0x71a1b2c3d4e5f60718293a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c03",
                    7_448_990L, 0, daysAgo(170));
            tokenTransfer(greenBond.getId(), greenBondDeploy.getId(), sepolia.getId(), gbContract,
                    nordbankW, auroraW, bd("500"), TokenTransfer.EventType.TRANSFER,
                    "0x71a1b2c3d4e5f60718293a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c04",
                    7_991_204L, 2, daysAgo(100));
        });

        // ── DORA (Digital Operational Resilience Act) demo data ──────────────

        ThirdPartyProvider cloudProvider = thirdPartyProvider(
                "NordCloud Infrastructure AG", "CLOUD_HOSTING", ThirdPartyProvider.Criticality.CRITICAL,
                "DE", LocalDate.now().minusYears(2), LocalDate.now().plusMonths(8));
        thirdPartyProvider(
                "OpenSanctions Screening API", "SCREENING_PROVIDER", ThirdPartyProvider.Criticality.IMPORTANT,
                "NL", LocalDate.now().minusYears(1), LocalDate.now().plusMonths(5));

        ictIncident(IctIncident.Category.THIRD_PARTY_FAILURE, IctIncident.Severity.MEDIUM,
                "Elevated RPC latency on Ethereum testnet indexer",
                "GraphNodeSyncService reported degraded sync lag for ~40 minutes; no investor-facing impact.",
                IctIncident.Status.RESOLVED, daysAgo(21));
        ictIncident(IctIncident.Category.DATA_BREACH, IctIncident.Severity.MAJOR,
                "Suspicious login attempts against operator admin console",
                "Brute-force login attempts detected and blocked by LoginAttemptLimiter; no account compromised. "
                + "Reported to BaFin per Art. 19 within the 24h initial-notification window.",
                IctIncident.Status.REPORTED_TO_AUTHORITY, daysAgo(10));

        resilienceTest(ResilienceTest.TestType.VULNERABILITY_SCAN, "Operator portal + public API surface",
                false, null, LocalDate.now().minusMonths(2), LocalDate.now().plusMonths(10),
                ResilienceTest.Result.PASSED, "OWASP ZAP baseline scan — no high/critical findings.",
                "Internal Security Team");
        resilienceTest(ResilienceTest.TestType.TLPT, "T-REX identity registry + EwpgPaymaster (designated critical)",
                true, cloudProvider.getId(), LocalDate.now().minusMonths(8), LocalDate.now().plusMonths(28),
                ResilienceTest.Result.FINDINGS_OPEN,
                "Threat-led penetration test per RTS (EU) 2025/301 — two medium findings remediated, "
                + "one low-severity finding open (rate-limiting on claim issuance endpoint).",
                "Redteam Cyber GmbH");

        log.info("Demo data seeded: 3 operator users (TOTP-enrolled), 7 companies, 14 company users, "
                + "14 KYC documents + 7 jurisdiction approvals, 10 assets, 1 holder block, "
                + "4 active listings, 4 trade executions, 4 indexed transfers, "
                + "3 gas sponsorship policies, 2 ICT incidents, 2 third-party providers, 2 resilience tests");
    }

    private void tokenTransfer(java.util.UUID assetId, java.util.UUID deploymentId,
            java.util.UUID chainConfigId, String contractAddress, String from, String to,
            BigDecimal amount, TokenTransfer.EventType eventType, String txHash,
            long blockNumber, int logIndex, Instant occurredAt) {
        TokenTransfer t = new TokenTransfer();
        t.setAssetId(assetId);
        t.setDeploymentId(deploymentId);
        t.setChainConfigId(chainConfigId);
        t.setContractAddress(contractAddress);
        t.setFromAddress(from);
        t.setToAddress(to);
        t.setAmount(amount);
        t.setEventType(eventType);
        t.setTxHash(txHash);
        t.setBlockNumber(blockNumber);
        t.setLogIndex(logIndex);
        t.setOccurredAt(occurredAt);
        t.setExplorerTxUrl("https://sepolia.etherscan.io/tx/" + txHash);
        tokenTransfers.save(t);
    }

    private ThirdPartyProvider thirdPartyProvider(String name, String category,
            ThirdPartyProvider.Criticality criticality, String country,
            LocalDate contractStart, LocalDate contractEnd) {
        ThirdPartyProvider p = new ThirdPartyProvider();
        p.setName(name);
        p.setCategory(category);
        p.setCriticality(criticality);
        p.setCountry(country);
        p.setContractStart(contractStart);
        p.setContractEnd(contractEnd);
        return thirdPartyProviders.save(p);
    }

    private void ictIncident(IctIncident.Category category, IctIncident.Severity severity,
            String title, String description, IctIncident.Status status, Instant detectedAt) {
        IctIncident incident = new IctIncident();
        incident.setCategory(category);
        incident.setSeverity(severity);
        incident.setTitle(title);
        incident.setDescription(description);
        incident.setDetectedAt(detectedAt);
        incident.setStatus(status);
        if (severity == IctIncident.Severity.MAJOR) {
            incident.setInitialReportDeadline(detectedAt.plus(24, ChronoUnit.HOURS));
            incident.setFinalReportDeadline(detectedAt.plus(30, ChronoUnit.DAYS));
            if (status == IctIncident.Status.REPORTED_TO_AUTHORITY) {
                incident.setInitialReportedAt(detectedAt.plus(6, ChronoUnit.HOURS));
                incident.setAuthorityRef("BAFIN-2026-DORA-0042");
            }
        }
        if (status == IctIncident.Status.RESOLVED) {
            incident.setResolvedAt(detectedAt.plus(40, ChronoUnit.MINUTES));
        }
        ictIncidents.save(incident);
    }

    private void resilienceTest(ResilienceTest.TestType testType, String scope, boolean tlptRequired,
            java.util.UUID thirdPartyProviderId, LocalDate performedAt, LocalDate nextDueDate,
            ResilienceTest.Result result, String findings, String testerName) {
        ResilienceTest test = new ResilienceTest();
        test.setTestType(testType);
        test.setScope(scope);
        test.setTlptRequired(tlptRequired);
        test.setThirdPartyProviderId(thirdPartyProviderId);
        test.setPerformedAt(performedAt);
        test.setNextDueDate(nextDueDate);
        test.setResult(result);
        test.setFindings(findings);
        test.setTesterName(testerName);
        resilienceTests.save(test);
    }

    private void gasSponsorshipPolicy(java.util.UUID assetDeploymentId, java.util.UUID issuerId,
                                      GasSponsorshipPolicy.Sponsor sponsor, BigDecimal monthlyCapEth) {
        GasSponsorshipPolicy policy = new GasSponsorshipPolicy();
        policy.setAssetDeploymentId(assetDeploymentId);
        policy.setIssuerId(issuerId);
        policy.setSponsor(sponsor);
        policy.setMonthlyCapEth(monthlyCapEth);
        policy.setActive(true);
        gasSponsorshipPolicies.save(policy);
    }

    // ── Node seeding ──────────────────────────────────────────────────────────

    private void syncPublicNodes() {
        // Public keyless RPC nodes; verified working 2026-05-07.
        // Demo mode uses this list as the source of truth for both chain_config RPC URLs
        // and the operator-visible rpc_node rows.
        Map<String, List<NodeDef>> defsByChain = demoNodeDefs().stream()
                .collect(Collectors.groupingBy(
                        NodeDef::chainIdentifier,
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayList::new)));

        for (var entry : defsByChain.entrySet()) {
            chainConfigs.findByIdentifier(entry.getKey())
                    .ifPresent(chain -> syncChainNodes(chain, entry.getValue()));
        }
    }

    private List<NodeDef> demoNodeDefs() {
        return List.of(
                // Ethereum Mainnet
                new NodeDef("ETHEREUM_MAINNET", "https://eth.llamarpc.com", "LlamaRPC", true),
                new NodeDef("ETHEREUM_MAINNET", "https://ethereum.publicnode.com", "PublicNode", true),
                new NodeDef("ETHEREUM_MAINNET", "https://eth.drpc.org", "dRPC", true),
                // Ethereum Sepolia
                new NodeDef("ETHEREUM_SEPOLIA", "https://ethereum-sepolia.publicnode.com", "PublicNode", true),
                new NodeDef("ETHEREUM_SEPOLIA", "https://sepolia.drpc.org", "dRPC", true),
                // Polygon Mainnet
                new NodeDef("POLYGON_MAINNET", "https://polygon.publicnode.com", "PublicNode", true),
                new NodeDef("POLYGON_MAINNET", "https://polygon.drpc.org", "dRPC", true),
                // Polygon Amoy
                new NodeDef("POLYGON_AMOY", "https://rpc-amoy.polygon.technology", "Polygon Foundation", true),
                new NodeDef("POLYGON_AMOY", "https://polygon-amoy.publicnode.com", "PublicNode", true),
                // Base Mainnet
                new NodeDef("BASE_MAINNET", "https://mainnet.base.org", "Base", true),
                new NodeDef("BASE_MAINNET", "https://base.llamarpc.com", "LlamaRPC", true),
                // Base Sepolia
                new NodeDef("BASE_SEPOLIA", "https://sepolia.base.org", "Base", true),
                new NodeDef("BASE_SEPOLIA", "https://base-sepolia-rpc.publicnode.com", "PublicNode", true),
                new NodeDef("BASE_SEPOLIA", "https://base-sepolia.drpc.org", "dRPC", true),
                // Solana
                new NodeDef("SOLANA_MAINNET", "https://api.mainnet-beta.solana.com", "Solana Labs", true),
                new NodeDef("SOLANA_DEVNET", "https://api.devnet.solana.com", "Solana Labs", true),
                // Arbitrum One
                new NodeDef("ARBITRUM_MAINNET", "https://arbitrum.publicnode.com", "PublicNode", true),
                new NodeDef("ARBITRUM_MAINNET", "https://arbitrum.drpc.org", "dRPC", true),
                // Arbitrum Sepolia
                new NodeDef("ARBITRUM_SEPOLIA", "https://sepolia-rollup.arbitrum.io/rpc", "Offchain Labs", true),
                new NodeDef("ARBITRUM_SEPOLIA", "https://arbitrum-sepolia.publicnode.com", "PublicNode", true),
                // Avalanche C-Chain
                new NodeDef("AVALANCHE_MAINNET", "https://api.avax.network/ext/bc/C/rpc", "Ava Labs", true),
                new NodeDef("AVALANCHE_MAINNET", "https://avalanche.publicnode.com/ext/bc/C/rpc", "PublicNode", true),
                new NodeDef("AVALANCHE_MAINNET", "https://avalanche.drpc.org", "dRPC", true),
                // Avalanche Fuji
                new NodeDef("AVALANCHE_FUJI", "https://api.avax-test.network/ext/bc/C/rpc", "Ava Labs", true),
                new NodeDef("AVALANCHE_FUJI", "https://avalanche-fuji-c-chain-rpc.publicnode.com", "PublicNode", true),
                // Optimism
                new NodeDef("OPTIMISM_MAINNET", "https://mainnet.optimism.io", "OP Labs", true),
                new NodeDef("OPTIMISM_MAINNET", "https://optimism.publicnode.com", "PublicNode", true),
                new NodeDef("OPTIMISM_MAINNET", "https://optimism.drpc.org", "dRPC", true),
                // Optimism Sepolia
                new NodeDef("OPTIMISM_SEPOLIA", "https://sepolia.optimism.io", "OP Labs", true),
                new NodeDef("OPTIMISM_SEPOLIA", "https://optimism-sepolia.publicnode.com", "PublicNode", true),
                // Fhenix / Inco
                new NodeDef("FHENIX_MAINNET", "https://api.fhenix.zone:7747", "Fhenix", true),
                new NodeDef("FHENIX_HELIUM", "https://api.helium.fhenix.zone:7747", "Fhenix", true),
                new NodeDef("INCO_MAINNET", "https://mainnet.inco.org", "Inco", true),
                new NodeDef("INCO_RIVEST", "https://validator.rivest.inco.org", "Inco", true),
                // Starknet (stub chains — disabled nodes visible in operator UI)
                new NodeDef("STARKNET_MAINNET", "https://rpc.starknet.lava.build", "Lava", false),
                new NodeDef("STARKNET_MAINNET", "https://api.cartridge.gg/x/starknet/mainnet", "Cartridge", false),
                new NodeDef("STARKNET_SEPOLIA", "https://api.cartridge.gg/x/starknet/sepolia", "Cartridge", false),
                // Stellar (Horizon REST API, not JSON-RPC — informational only)
                new NodeDef("STELLAR_MAINNET", "https://horizon.stellar.org", "SDF Horizon", false),
                new NodeDef("STELLAR_TESTNET", "https://horizon-testnet.stellar.org", "SDF Horizon Testnet", false),
                // Canton: operators wire their own participant via CANTON_DEVNET_LEDGER_URL.
                // The devnet rpc_url is populated from that env var at runtime (see below).
                new NodeDef("CANTON_DEVNET", cantonDevnetUrl(), "Local Participant", !cantonDevnetUrl().isBlank())
        );
    }

    private String cantonDevnetUrl() {
        return cantonDevnetLedgerUrl != null ? cantonDevnetLedgerUrl.strip() : "";
    }

    private void syncChainNodes(ChainConfig chain, List<NodeDef> defs) {
        syncChainRpcUrls(chain, defs);

        Map<String, RpcNode> existingByUrl = rpcNodes.findByChainConfig_Identifier(chain.getIdentifier()).stream()
                .collect(Collectors.toMap(
                        RpcNode::getUrl,
                        node -> node,
                        (left, right) -> left,
                        LinkedHashMap::new));

        for (NodeDef def : defs) {
            RpcNode existing = existingByUrl.get(def.url());
            if (existing == null) {
                rpcNode(chain, def.url(), def.label(), def.enabled());
                continue;
            }

            boolean changed = false;
            if (!Objects.equals(existing.getLabel(), def.label())) {
                existing.setLabel(def.label());
                changed = true;
            }
            if (existing.isEnabled() != def.enabled()) {
                existing.setEnabled(def.enabled());
                changed = true;
            }

            if (changed) {
                rpcNodes.save(existing);
            }
        }
    }

    private void syncChainRpcUrls(ChainConfig chain, List<NodeDef> defs) {
        List<String> urls = defs.stream()
                .map(NodeDef::url)
                .filter(url -> !url.isBlank())
                .toList();
        if (urls.isEmpty()) {
            return;
        }

        String primaryUrl = urls.getFirst();
        List<String> fallbackUrls = urls.size() > 1 ? urls.subList(1, urls.size()) : List.of();

        boolean changed = false;
        if (!Objects.equals(chain.getRpcUrl(), primaryUrl)) {
            chain.setRpcUrl(primaryUrl);
            changed = true;
        }
        if (!Objects.equals(chain.getFallbackRpcUrlList(), fallbackUrls)) {
            chain.setFallbackRpcUrlList(fallbackUrls);
            changed = true;
        }

        if (changed) {
            chainConfigs.save(chain);
        }
    }

    private void rpcNode(ChainConfig chain, String url, String label, boolean enabled) {
        RpcNode n = new RpcNode();
        n.setChainConfig(chain);
        n.setUrl(url);
        n.setLabel(label);
        n.setEnabled(enabled);
        rpcNodes.save(n);
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
        return userWithRoles(email, fullName, Set.of(role), entityId);
    }

    private AppUser userWithRoles(String email, String fullName, Set<AppUserRole> roles, java.util.UUID entityId) {
        return users.findByEmailIgnoreCase(email).orElseGet(() -> {
            AppUser u = new AppUser();
            u.setEmail(email);
            u.setFullName(fullName);
            u.setPasswordHash(encoder.encode("demo1234!"));
            u.setRoles(roles);
            u.setLegalEntityId(entityId);
            u.setAuthProvider(UserAuthProvider.LOCAL);
            u.setEnabled(true);
            return users.save(u);
        });
    }

    /** Seeds an operator (REGISTRY_ADMIN / COMPLIANCE_OFFICER / AUDIT) user, TOTP-enrolled with
     *  {@link #DEMO_TOTP_SECRET} so step-up + dual-control flows are exercisable in demo mode. */
    private AppUser operatorUser(String email, String fullName, AppUserRole role) {
        return users.findByEmailIgnoreCase(email).orElseGet(() -> {
            AppUser u = new AppUser();
            u.setEmail(email);
            u.setFullName(fullName);
            u.setPasswordHash(encoder.encode("demo1234!"));
            u.setRole(role);
            u.setAuthProvider(UserAuthProvider.LOCAL);
            u.setEnabled(true);
            u.setTotpSecret(DEMO_TOTP_SECRET);
            u.setTotpEnabled(true);
            u.setTotpEnrolledAt(Instant.now());
            return users.save(u);
        });
    }

    /** Representative (not exhaustive) KYC baseline: two documents matching the DE_EWPG
     *  checklist (JurisdictionRequirementConfig) plus one APPROVED jurisdiction approval —
     *  without this, every demo entity was "KYC APPROVED" with zero supporting evidence. */
    private void seedKycBasics(LegalEntity e, java.util.UUID approvedBy) {
        kycDocument(e.getId(), KycDocument.DocumentType.COMMERCIAL_REGISTER_EXTRACT,
                "handelsregisterauszug-" + e.getEntityNumber() + ".pdf", Jurisdiction.DE_EWPG);
        kycDocument(e.getId(), KycDocument.DocumentType.UBO_DECLARATION,
                "wirtschaftlich-berechtigte-" + e.getEntityNumber() + ".pdf", Jurisdiction.DE_EWPG);

        KycJurisdictionApproval approval = new KycJurisdictionApproval();
        approval.setEntityId(e.getId());
        approval.setJurisdiction(Jurisdiction.DE_EWPG);
        approval.setStatus(KycJurisdictionApproval.Status.APPROVED);
        approval.setApprovedBy(approvedBy);
        approval.setApprovedAt(daysAgo(200));
        approval.setExpiresAt(LocalDate.now().plusYears(2));
        kycJurisdictionApprovals.save(approval);
    }

    private void kycDocument(java.util.UUID entityId, KycDocument.DocumentType type, String fileName,
                              Jurisdiction jurisdiction) {
        byte[] content = ("Demo placeholder content for " + type + " (" + fileName + ") — "
                + "not a real regulatory document.").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        KycDocument doc = new KycDocument();
        doc.setLegalEntityId(entityId);
        doc.setDocumentType(type);
        doc.setMimeType("application/pdf");
        doc.setFileName(fileName);
        doc.setStorageRef("inline");
        doc.setContentHash(sha256Hex(content));
        doc.setSizeBytes((long) content.length);
        doc.setExpiresAt(LocalDate.now().plusYears(1));
        doc.setJurisdiction(jurisdiction);
        doc = kycDocuments.save(doc);

        KycDocumentContent stored = new KycDocumentContent();
        stored.setId(doc.getId());
        stored.setContent(content);
        kycDocumentContents.save(stored);
    }

    private void holderBlock(java.util.UUID entityId, java.util.UUID assetId, String walletAddress,
                              HolderBlock.BlockType type, String legalBasis, java.util.UUID createdBy) {
        HolderBlock block = new HolderBlock();
        block.setEntityId(entityId);
        block.setAssetId(assetId);
        block.setWalletAddress(walletAddress);
        block.setBlockType(type);
        block.setStatus(HolderBlock.Status.ACTIVE);
        block.setLegalBasis(legalBasis);
        block.setStartsAt(daysAgo(14));
        block.setCreatedBy(createdBy);
        block.setDualControlApproverId(createdBy);
        block.setDualControlApprovedAt(daysAgo(14));
        holderBlocks.save(block);
    }

    private static String sha256Hex(byte[] content) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(content);
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
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
