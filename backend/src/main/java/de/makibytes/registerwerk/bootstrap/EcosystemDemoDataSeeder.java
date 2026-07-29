package de.makibytes.registerwerk.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetTokenAdminGrant;
import de.makibytes.registerwerk.asset.api.AssetTokenAdminGrantRepository;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.marketplace.api.*;
import de.makibytes.registerwerk.orgidentity.api.*;
import de.makibytes.registerwerk.payment.api.PaymentRail;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddress;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddressRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Seeds demo data for the ecosystem (org identity, permission framework) and the dApp
 * marketplace: two ACTIVE organizations, member wallets with distinct org-scoped roles,
 * a trusted claim issuer, permission definitions/grants (including a role-restricted
 * grant + role delegation, showcasing org-admin delegation), and the four reference
 * marketplace dApps ({@code boardroom}, {@code bond-desk}, {@code repo-facility},
 * {@code repo-markets}) as already-{@code PUBLISHED} listings with real,
 * independently-verifiable signatures.
 *
 * <p>Runs after {@link DemoDataSeeder} (see {@link #getOrder()}), which creates the
 * companies and users this seeder attaches org identity to. Everything is written
 * directly in its terminal ACTIVE/PUBLISHED state — no onchain transactions are
 * submitted — mirroring how {@link DemoDataSeeder} writes "confirmed" deployments
 * directly rather than simulating the async chain-confirmation flow.
 *
 * <p>{@code repo-markets} is seeded as a fourth listing alongside the original three: it
 * intentionally reuses {@code repo-facility}'s already-registered {@code repo-facility.borrow}/
 * {@code repo-facility.configure} permission codes at the contract level (same
 * {@link de.makibytes.registerwerk.orgidentity.api.PermissionDefinition} rows, no re-grant
 * needed), so its own manifest's {@code requiredPermissions} only lists the codes genuinely new
 * to it — {@code seedDapp}'s unconditional insert would otherwise violate
 * {@code permission_definition.code}'s uniqueness constraint on the second listing. See
 * {@code repo-markets.manifest.json}'s description for the full rationale.
 */
@Component
@ConditionalOnProperty(name = "registerwerk.seed-demo-data", havingValue = "true")
public class EcosystemDemoDataSeeder implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(EcosystemDemoDataSeeder.class);

    /** The chain identifier the demo ecosystem/marketplace data is anchored on. */
    private static final String DEMO_CHAIN_IDENTIFIER = "ETHEREUM_SEPOLIA";

    /** Org-scoped role used for the role-restriction / delegation showcase. */
    private static final String BOARD_SECRETARY_ROLE = "BOARD_SECRETARY";

    private final LegalEntityRepository entities;
    private final AppUserRepository users;
    private final ChainConfigRepository chainConfigs;
    private final OrgRegistrationRepository orgRegistrations;
    private final OrgMemberWalletRepository memberWallets;
    private final EcosystemTrustedIssuerRepository trustedIssuers;
    private final PermissionDefinitionRepository permissionDefinitions;
    private final PermissionGrantRepository permissionGrants;
    private final DappListingRepository dappListings;
    private final DappVersionRepository dappVersions;
    private final DappRequiredPermissionRepository dappRequiredPermissions;
    private final DappPaymentMethodRepository dappPaymentMethods;
    private final DappReviewEventRepository reviewEvents;
    private final PaymentRailRepository paymentRails;
    private final PaymentRailChainAddressRepository paymentRailChainAddresses;
    private final PasswordEncoder encoder;
    private final AssetRepository assets;
    private final AssetTokenAdminGrantRepository assetTokenAdminGrants;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EcosystemDemoDataSeeder(
            LegalEntityRepository entities,
            AppUserRepository users,
            ChainConfigRepository chainConfigs,
            OrgRegistrationRepository orgRegistrations,
            OrgMemberWalletRepository memberWallets,
            EcosystemTrustedIssuerRepository trustedIssuers,
            PermissionDefinitionRepository permissionDefinitions,
            PermissionGrantRepository permissionGrants,
            DappListingRepository dappListings,
            DappVersionRepository dappVersions,
            DappRequiredPermissionRepository dappRequiredPermissions,
            DappPaymentMethodRepository dappPaymentMethods,
            DappReviewEventRepository reviewEvents,
            PaymentRailRepository paymentRails,
            PaymentRailChainAddressRepository paymentRailChainAddresses,
            PasswordEncoder encoder,
            AssetRepository assets,
            AssetTokenAdminGrantRepository assetTokenAdminGrants) {
        this.entities = entities;
        this.users = users;
        this.chainConfigs = chainConfigs;
        this.orgRegistrations = orgRegistrations;
        this.memberWallets = memberWallets;
        this.trustedIssuers = trustedIssuers;
        this.permissionDefinitions = permissionDefinitions;
        this.permissionGrants = permissionGrants;
        this.dappListings = dappListings;
        this.dappVersions = dappVersions;
        this.dappRequiredPermissions = dappRequiredPermissions;
        this.dappPaymentMethods = dappPaymentMethods;
        this.reviewEvents = reviewEvents;
        this.paymentRails = paymentRails;
        this.paymentRailChainAddresses = paymentRailChainAddresses;
        this.encoder = encoder;
        this.assets = assets;
        this.assetTokenAdminGrants = assetTokenAdminGrants;
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (dappListings.existsBySlug("boardroom")) {
            log.info("Ecosystem demo data already present — skipped");
            return;
        }

        Optional<LegalEntity> meridianOpt = entities.findByEntityNumber("DEMO-MC-001");
        Optional<LegalEntity> auroraOpt = entities.findByEntityNumber("DEMO-AF-001");
        Optional<ChainConfig> chainOpt = chainConfigs.findByIdentifier(DEMO_CHAIN_IDENTIFIER);
        if (meridianOpt.isEmpty() || auroraOpt.isEmpty() || chainOpt.isEmpty()) {
            log.warn("Ecosystem demo data skipped — base demo data ({}) or chain config ({}) not present yet",
                    "DEMO-MC-001/DEMO-AF-001", DEMO_CHAIN_IDENTIFIER);
            return;
        }
        LegalEntity meridian = meridianOpt.get();
        LegalEntity aurora = auroraOpt.get();
        ChainConfig chain = chainOpt.get();

        // The 5 investor-type companies need org identity too, for an issuer/entity-wide
        // ASSET_TOKEN_ADMIN grant (the investor-scoped path doesn't need org identity, only
        // AssetHolder.whitelisted — see AssetTokenAdminGrantService.validateWalletEligibility).
        LegalEntity nordbank = requireEntity("DEMO-NI-001");
        LegalEntity rheinische = requireEntity("DEMO-RK-001");
        LegalEntity elbe = requireEntity("DEMO-EA-001");
        LegalEntity frankfurtDigital = requireEntity("DEMO-FD-001");
        LegalEntity wuerttemberg = requireEntity("DEMO-WI-001");

        AppUser heinz = users.findByEmailIgnoreCase("heinz.weber@meridian-capital.de")
                .orElseThrow(() -> new IllegalStateException(
                        "Expected demo user heinz.weber@meridian-capital.de from DemoDataSeeder"));
        AppUser thomas = users.findByEmailIgnoreCase("thomas.bauer@aurora-finance.de")
                .orElseThrow(() -> new IllegalStateException(
                        "Expected demo user thomas.bauer@aurora-finance.de from DemoDataSeeder"));

        log.info("Seeding ecosystem demo data (org identity, permissions, marketplace dApps)…");

        AppUser publisher = user("dapps@meridian-capital.de", "Franziska Vogt",
                AppUserRole.DAPP_PUBLISHER, meridian.getId());

        OrgRegistration meridianOrg = orgRegistration(meridian, chain, "meridian-org");
        OrgRegistration auroraOrg = orgRegistration(aurora, chain, "aurora-org");

        ECKeyPair boardSecretaryKey = deriveDemoKeyPair("meridian-board-secretary");
        ECKeyPair treasuryKey = deriveDemoKeyPair("meridian-treasury-publisher");
        ECKeyPair auroraTreasuryKey = deriveDemoKeyPair("aurora-treasury");

        memberWallet(meridianOrg, chain, heinz.getId(), boardSecretaryKey,
                List.of(BOARD_SECRETARY_ROLE), "Heinz Weber — Board Secretary");
        memberWallet(meridianOrg, chain, publisher.getId(), treasuryKey,
                List.of("TREASURY"), "Franziska Vogt — Treasury / dApp Publisher");
        memberWallet(auroraOrg, chain, thomas.getId(), auroraTreasuryKey,
                List.of("TREASURY"), "Thomas Bauer — Treasury");

        // Org identity for the 5 investor companies, each bound to the SAME wallet address
        // already used as that entity's AssetHolder wallet in DemoDataSeeder (literal strings
        // copied intentionally — this seeder must not reach into DemoDataSeeder's local
        // variables, and a real operator would bind the investor's existing custody wallet the
        // same way). This makes each investor eligible for a future ENTITY_WALLET_BINDING
        // (entity-wide) ASSET_TOKEN_ADMIN grant, not just the asset-scoped INVESTOR_WHITELIST
        // path already demoed below for Nordbank.
        memberWallet(orgRegistration(nordbank, chain, "nordbank-org"), chain,
                requireUser("maria.braun@nordbank-invest.de").getId(),
                "0x1A2B3C4D5E6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B", // = gbNordbank's wallet (Green Bond holder)
                List.of("TREASURY"), "Maria Braun — Treasury");
        memberWallet(orgRegistration(rheinische, chain, "rheinische-org"), chain,
                requireUser("sabine.mueller@rheinische-kapital.de").getId(),
                "0x2B3C4D5E6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B1C", // = gbRheinische's wallet
                List.of("TREASURY"), "Sabine Müller — Treasury");
        memberWallet(orgRegistration(elbe, chain, "elbe-org"), chain,
                requireUser("petra.zimmermann@elbe-asset-partners.de").getId(),
                "0xB2C3D4E5F6A7B8C9D0A1B2C3D4E5F6A7B8C9D0A1", // = Elbe's Confidential Equity wallet
                List.of("TREASURY"), "Petra Zimmermann — Treasury");
        memberWallet(orgRegistration(frankfurtDigital, chain, "frankfurt-digital-org"), chain,
                requireUser("sandra.richter@fd-fonds.de").getId(),
                "0x9C0D1E2F3A4B5C6D7E8F9A0B1C2D3E4F5A6B7C8D", // = inFrankfurt's wallet (Infra Note holder)
                List.of("TREASURY"), "Sandra Richter — Treasury");
        memberWallet(orgRegistration(wuerttemberg, chain, "wuerttemberg-org"), chain,
                requireUser("ute.koenig@wi-invest.de").getId(),
                "0x8B9C0D1E2F3A4B5C6D7E8F9A0B1C2D3E4F5A6B7C", // = inWuerttemberg's wallet
                List.of("TREASURY"), "Ute König — Treasury");

        trustedIssuer(chain);
        seedPaymentRails(chain);
        seedAssetTokenAdminGrants(meridian, nordbank, chain, boardSecretaryKey);

        // A platform-wide permission (namespace "registerwerk.") granted to both orgs —
        // distinct from the two dApp-namespaced permission sets below.
        PermissionDefinition platformPermission = platformPermission();
        orgGrant(platformPermission, meridianOrg, false);
        orgGrant(platformPermission, auroraOrg, false);

        // Both reference dApps are published by Meridian and operated with the same
        // treasury wallet that signed their manifests.
        PublisherContext meridianPublisher =
                new PublisherContext(meridian, chain, meridianOrg, treasuryKey, publisher.getId());
        SeedResult boardroom = seedDapp("boardroom", meridianPublisher);
        SeedResult bondDesk = seedDapp("bond-desk", meridianPublisher);
        SeedResult repoFacility = seedDapp("repo-facility", meridianPublisher);
        SeedResult repoMarkets = seedDapp("repo-markets", meridianPublisher);

        log.info("Ecosystem demo data seeded: 7 orgs, 8 member wallets, 1 trusted issuer, "
                + "{} permission definitions, {} grants, 4 published dApps, 2 ASSET_TOKEN_ADMIN grants",
                1 + boardroom.definitionsCreated() + bondDesk.definitionsCreated() + repoFacility.definitionsCreated()
                        + repoMarkets.definitionsCreated(),
                2 + boardroom.grantsCreated() + bondDesk.grantsCreated() + repoFacility.grantsCreated()
                        + repoMarkets.grantsCreated());
    }

    private LegalEntity requireEntity(String entityNumber) {
        return entities.findByEntityNumber(entityNumber)
                .orElseThrow(() -> new IllegalStateException(
                        "Expected demo entity " + entityNumber + " from DemoDataSeeder"));
    }

    private AppUser requireUser(String email) {
        return users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalStateException("Expected demo user " + email + " from DemoDataSeeder"));
    }

    /**
     * Seeds the two ASSET_TOKEN_ADMIN grants demonstrating {@code docs/compliance/token-admin-grants.md}:
     * one issuer-scoped (Meridian, via its own board-secretary org wallet — ISSUER_WALLET_BINDING)
     * and one investor-scoped (Nordbank, via its already-whitelisted Green Bond holder wallet —
     * INVESTOR_WHITELIST). Written directly in ACTIVE state via the repository — like the rest of
     * this seeder — rather than through {@code AssetTokenAdminGrantService}, since that service
     * lives in {@code asset.internal} and is intentionally not reachable from this module
     * (Modulith boundary: only {@code asset.api} is a cross-module surface). The eligibility bases
     * set here are exactly what that service's live validation would compute for these inputs.
     */
    private void seedAssetTokenAdminGrants(LegalEntity meridian, LegalEntity nordbank, ChainConfig chain,
                                            ECKeyPair meridianBoardSecretaryKey) {
        Asset greenBond = assets.findByAssetNumber("DEMO-BOND-MC-001")
                .orElseThrow(() -> new IllegalStateException("Expected demo asset DEMO-BOND-MC-001 from DemoDataSeeder"));
        UUID dualControlAdminId = requireUser("dual-control.admin@registerwerk-demo.internal").getId();

        assetTokenAdminGrant(meridian.getId(), greenBond.getId(), walletAddress(meridianBoardSecretaryKey), chain.getId(),
                AssetTokenAdminGrant.EligibilityBasis.ISSUER_WALLET_BINDING,
                "eWpG §24 Berichtigung — Meridian Capital AG, as issuer, delegated limited "
                        + "self-service register-correction rights on its own Green Bond 2024 issuance "
                        + "via its board-secretary wallet (demo).",
                dualControlAdminId);

        assetTokenAdminGrant(nordbank.getId(), greenBond.getId(),
                "0x1A2B3C4D5E6F7A8B9C0D1E2F3A4B5C6D7E8F9A0B", null, // gbNordbank's wallet — investor path needs no chainConfigId
                AssetTokenAdminGrant.EligibilityBasis.INVESTOR_WHITELIST,
                "eWpG §24 Berichtigung — Nordbank Invest AG, a whitelisted Green Bond holder, granted "
                        + "limited self-service correction rights on its own position following a "
                        + "documented settlement-fails incident (demo).",
                dualControlAdminId);
    }

    private void assetTokenAdminGrant(UUID entityId, UUID assetId, String walletAddress, UUID chainConfigId,
                                       AssetTokenAdminGrant.EligibilityBasis basis, String legalBasis, UUID createdBy) {
        AssetTokenAdminGrant grant = new AssetTokenAdminGrant();
        grant.setEntityId(entityId);
        grant.setAssetId(assetId);
        grant.setWalletAddress(walletAddress);
        grant.setChainConfigId(chainConfigId);
        grant.setEligibilityBasis(basis);
        grant.setLegalBasis(legalBasis);
        grant.setCreatedBy(createdBy);
        grant.setDualControlApproverId(createdBy);
        grant.setDualControlApprovedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        grant.setStatus(AssetTokenAdminGrant.Status.ACTIVE);
        assetTokenAdminGrants.save(grant);
    }

    // ── dApp seeding ─────────────────────────────────────────────────────────

    /** The publisher identity shared by every dApp seeded in this run. */
    private record PublisherContext(
            LegalEntity entity, ChainConfig chain, OrgRegistration org, ECKeyPair signerKey, UUID appUserId) {}

    private record SeedResult(int definitionsCreated, int grantsCreated) {}

    /**
     * Seeds one marketplace dApp end to end from its classpath manifest: a
     * {@code PUBLISHED} listing + version with a real keccak256 hash and EIP-191
     * signature (verifiable exactly like {@code ManifestSigningService.verify}), plus
     * the required-permission rows and their auto-registered/granted permission
     * definitions — mirroring {@code ListingLifecycleService.putManifest}/{@code approve}
     * without going through the review workflow or an onchain transaction.
     */
    private SeedResult seedDapp(String slug, PublisherContext publisher) {
        String manifestRaw = readManifest(slug);
        JsonNode root = parseManifest(slug, manifestRaw);

        List<Long> claimTopics = new ArrayList<>();
        root.path("requiredClaimTopics").forEach(topic -> claimTopics.add(topic.asLong()));

        DappListing listing = new DappListing();
        listing.setPublisherEntityId(publisher.entity().getId());
        listing.setChainConfigId(publisher.chain().getId());
        listing.setSlug(slug);
        listing.setDappIdHash(keccak256Hex(slug));
        listing.setName(root.path("name").asText());
        listing.setCategory(root.path("category").asText("general"));
        listing.setStatus(DappListingStatus.PUBLISHED);
        listing.setContactEmail(root.path("contact").asText(null));
        listing.setDocsUrl(root.path("docsUrl").asText(null));
        listing.setPricingNote(root.path("pricingNote").asText(null));
        listing = dappListings.save(listing);

        String manifestHash = keccak256Hex(manifestRaw);
        String signature = signManifestHash(manifestHash, publisher.signerKey());
        String signerWallet = walletAddress(publisher.signerKey());

        DappVersion version = new DappVersion();
        version.setListingId(listing.getId());
        version.setVersion(root.path("version").asText());
        version.setManifestRaw(manifestRaw);
        version.setManifestJson(manifestRaw);
        version.setManifestHash(manifestHash);
        version.setManifestSignature(signature);
        version.setSignerWallet(signerWallet);
        version.setStatus(DappVersionStatus.PUBLISHED);
        version.setSubmittedAt(Instant.now().minus(2, ChronoUnit.DAYS));
        version.setReviewedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        version.setOnchainTx(keccak256Hex(slug + "-register-tx"));
        version = dappVersions.save(version);

        listing.setCurrentVersionId(version.getId());
        dappListings.save(listing);

        reviewEvent(version.getId(), "SUBMITTED", publisher.appUserId(), null);
        reviewEvent(version.getId(), "APPROVED", null, "Demo seed — pre-approved for the marketplace showcase");

        int definitionsCreated = 0;
        int grantsCreated = 0;
        for (JsonNode permissionNode : root.path("requiredPermissions")) {
            String code = permissionNode.path("code").asText();
            String rationale = permissionNode.path("rationale").asText(null);

            DappRequiredPermission required = new DappRequiredPermission();
            required.setVersionId(version.getId());
            required.setPermissionCode(code);
            required.setPermissionHash(keccak256Hex(code));
            required.setClaimTopics(claimTopics);
            required.setRationale(rationale);
            dappRequiredPermissions.save(required);

            PermissionDefinition definition = new PermissionDefinition();
            definition.setCode(code);
            definition.setPermissionHash(keccak256Hex(code));
            definition.setName(code);
            definition.setDescription(rationale);
            definition.setDappListingId(listing.getId());
            definition.setStatus(PermissionDefinitionStatus.ACTIVE);
            definition.setDefinedTx(keccak256Hex(code + "-define-tx"));
            definition = permissionDefinitions.save(definition);
            definitionsCreated++;

            // boardroom.tally is the role-restriction/delegation showcase: the org grant
            // is marked restricted, and only the BOARD_SECRETARY role is delegated it —
            // see BoardroomGovernance's NatSpec and test_tally_respectsRoleRestriction.
            boolean roleRestricted = code.equals("boardroom.tally");
            orgGrant(definition, publisher.org(), roleRestricted);
            grantsCreated++;
            if (roleRestricted) {
                roleGrant(definition, publisher.org(), BOARD_SECRETARY_ROLE);
                grantsCreated++;
            }
        }

        for (JsonNode methodNode : root.path("paymentMethods")) {
            DappPaymentMethod method = new DappPaymentMethod();
            method.setVersionId(version.getId());
            if (methodNode.hasNonNull("rail")) {
                method.setMethodType(DappPaymentMethod.MethodType.RAIL);
                method.setRailCode(methodNode.path("rail").asText());
            } else {
                JsonNode custom = methodNode.path("custom");
                method.setMethodType(DappPaymentMethod.MethodType.CUSTOM);
                method.setCustomName(custom.path("name").asText());
                method.setCustomDescription(custom.path("description").asText());
                method.setCurrency(custom.path("currency").asText(null));
            }
            method.setNote(methodNode.path("note").asText(null));
            dappPaymentMethods.save(method);
        }

        log.debug("Seeded dApp listing '{}' as PUBLISHED (manifestHash={}, signer={})",
                slug, manifestHash, signerWallet);
        return new SeedResult(definitionsCreated, grantsCreated);
    }

    private String readManifest(String slug) {
        try (InputStream in = new ClassPathResource("demo/dapps/" + slug + ".manifest.json").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load demo manifest for '" + slug + "'", e);
        }
    }

    private JsonNode parseManifest(String slug, String manifestRaw) {
        try {
            return objectMapper.readTree(manifestRaw);
        } catch (IOException e) {
            throw new IllegalStateException("Demo manifest for '" + slug + "' is not valid JSON", e);
        }
    }

    // ── Org identity seeding ───────────────────────────────────────────────────

    private OrgRegistration orgRegistration(LegalEntity entity, ChainConfig chain, String label) {
        OrgRegistration org = new OrgRegistration();
        org.setLegalEntityId(entity.getId());
        org.setChainConfigId(chain.getId());
        org.setOrgAddress(walletAddress(deriveDemoKeyPair(label + "-onchainid")));
        org.setStatus(OrgRegistrationStatus.ACTIVE);
        org.setRegisteredTx(keccak256Hex(label + "-register-tx"));
        return orgRegistrations.save(org);
    }

    private void memberWallet(OrgRegistration org, ChainConfig chain, UUID appUserId,
                              ECKeyPair keyPair, List<String> roles, String label) {
        memberWallet(org, chain, appUserId, walletAddress(keyPair), roles, label);
    }

    /** Overload for binding a wallet whose address is a fixed literal (e.g. one already used as
     *  an {@code AssetHolder} wallet elsewhere in the demo data) rather than derived from a
     *  freshly-generated demo key pair — {@code PermissionGate.isWalletBoundToEntity} only
     *  checks the DB row, never a signature, so no backing key is needed for seed data. */
    private void memberWallet(OrgRegistration org, ChainConfig chain, UUID appUserId,
                              String walletAddress, List<String> roles, String label) {
        OrgMemberWallet wallet = new OrgMemberWallet();
        wallet.setOrgRegistrationId(org.getId());
        wallet.setChainConfigId(chain.getId());
        wallet.setWalletAddress(walletAddress);
        wallet.setAppUserId(appUserId);
        wallet.setLabel(label);
        wallet.setRoles(roles);
        wallet.setStatus(MemberWalletStatus.ACTIVE);
        wallet.setBoundTx(keccak256Hex(label + "-bind-tx"));
        memberWallets.save(wallet);
    }

    /**
     * Seeds the operator's payment-rail catalog: two MiCAR e-money-token stablecoins
     * (AllUnity Euro, USDC), the Pontes instant-payment API, and the ERC-7573-style DvP
     * settlement rail — the same four rails referenced by the bond-desk demo manifest.
     */
    private void seedPaymentRails(ChainConfig chain) {
        paymentRail("aueur", "AllUnity Euro (AUEUR)", PaymentRailType.STABLECOIN, "EUR", 6,
                "MiCAR e-money token (EMT) issued by AllUnity GmbH under a BaFin e-money "
                + "institution licence (MiCAR Title IV).",
                "AllUnity GmbH", "9845007N0T4HZ3PKR567", "BaFin EMI licence — MiCAR Title IV EMT",
                true, "https://www.allunity.com/aueur-whitepaper.pdf", true,
                walletAddress(deriveDemoKeyPair("aueur-token")), chain);

        paymentRail("usdc", "USD Coin (USDC)", PaymentRailType.STABLECOIN, "USD", 6,
                "MiCAR e-money token issued in the EEA by Circle Internet Financial Europe SAS.",
                "Circle Internet Financial Europe SAS", "254900OPPU84GM83MG36",
                "France ACPR e-money institution licence — MiCAR Title IV EMT",
                true, "https://www.circle.com/legal/usdc-whitepaper", true,
                walletAddress(deriveDemoKeyPair("usdc-token")), chain);

        paymentRail("pontes", "Pontes Instant Payment API", PaymentRailType.PONTES_API, "EUR", null,
                "Off-chain instant SEPA payment triggered through the Pontes payment API; "
                + "no onchain token movement.",
                null, null, null, false, null, false, null, chain);

        paymentRail("erc7573-dvp", "ERC-7573 DvP Settlement", PaymentRailType.ERC7573_DVP, "EUR", null,
                "Atomic delivery-versus-payment: lock the asset or payment leg in the operator's "
                + "DvpSettlement contract, settle both legs in a single transaction.",
                null, null, null, false, null, false,
                walletAddress(deriveDemoKeyPair("dvp-settlement")), chain);
    }

    private void paymentRail(String code, String displayName, PaymentRailType type, String currency,
                             Integer decimals, String description, String issuerName, String issuerLei,
                             String micarAuthorization, boolean emtFlag, String whitePaperUrl,
                             boolean redemptionAtPar, String chainAddress, ChainConfig chain) {
        PaymentRail rail = new PaymentRail();
        rail.setCode(code);
        rail.setDisplayName(displayName);
        rail.setRailType(type);
        rail.setCurrency(currency);
        rail.setDecimals(decimals);
        rail.setDescription(description);
        rail.setIssuerName(issuerName);
        rail.setIssuerLei(issuerLei);
        rail.setMicarAuthorization(micarAuthorization);
        rail.setEmtFlag(emtFlag);
        rail.setWhitePaperUrl(whitePaperUrl);
        rail.setRedemptionAtPar(redemptionAtPar);
        rail.setEnabled(true);
        rail = paymentRails.save(rail);

        if (chainAddress != null) {
            PaymentRailChainAddress address = new PaymentRailChainAddress();
            address.setPaymentRailId(rail.getId());
            address.setChainConfigId(chain.getId());
            address.setTokenAddress(chainAddress);
            paymentRailChainAddresses.save(address);
        }
    }

    private void trustedIssuer(ChainConfig chain) {
        EcosystemTrustedIssuer issuer = new EcosystemTrustedIssuer();
        issuer.setChainConfigId(chain.getId());
        issuer.setIssuerAddress(walletAddress(deriveDemoKeyPair("demo-kyc-issuer")));
        issuer.setClaimTopics(List.of(1L, 2L, 3L)); // KYC, AML, Accreditation
        issuer.setStatus(MemberWalletStatus.ACTIVE);
        issuer.setAddedTx(keccak256Hex("demo-kyc-issuer-add-tx"));
        trustedIssuers.save(issuer);
    }

    private PermissionDefinition platformPermission() {
        String code = "registerwerk.dapp-publish";
        PermissionDefinition definition = new PermissionDefinition();
        definition.setCode(code);
        definition.setPermissionHash(keccak256Hex(code));
        definition.setName("Publish dApp marketplace listings");
        definition.setDescription(
                "Platform-wide permission allowing an org to publish and manage marketplace dApp listings");
        definition.setStatus(PermissionDefinitionStatus.ACTIVE);
        definition.setDefinedTx(keccak256Hex(code + "-define-tx"));
        return permissionDefinitions.save(definition);
    }

    private PermissionGrant orgGrant(PermissionDefinition definition, OrgRegistration org, boolean roleRestricted) {
        PermissionGrant grant = new PermissionGrant();
        grant.setPermissionDefinitionId(definition.getId());
        grant.setOrgRegistrationId(org.getId());
        grant.setGrantType(PermissionGrantType.ORG);
        grant.setRoleRestricted(roleRestricted);
        grant.setStatus(PermissionGrantStatus.ACTIVE);
        grant.setGrantedTx(keccak256Hex(definition.getCode() + "-" + org.getId() + "-org-grant-tx"));
        return permissionGrants.save(grant);
    }

    private void roleGrant(PermissionDefinition definition, OrgRegistration org, String roleCode) {
        PermissionGrant grant = new PermissionGrant();
        grant.setPermissionDefinitionId(definition.getId());
        grant.setOrgRegistrationId(org.getId());
        grant.setGrantType(PermissionGrantType.ROLE);
        grant.setRoleCode(roleCode);
        grant.setStatus(PermissionGrantStatus.ACTIVE);
        grant.setGrantedTx(keccak256Hex(definition.getCode() + "-" + roleCode + "-role-grant-tx"));
        permissionGrants.save(grant);
    }

    private void reviewEvent(UUID versionId, String action, UUID actorId, String notes) {
        DappReviewEvent event = new DappReviewEvent();
        event.setVersionId(versionId);
        event.setAction(action);
        event.setActorId(actorId);
        event.setNotes(notes);
        reviewEvents.save(event);
    }

    private AppUser user(String email, String fullName, AppUserRole role, UUID entityId) {
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

    // ── Crypto helpers ─────────────────────────────────────────────────────────

    /**
     * Deterministically derives a demo-only EC key pair from a label — never used to
     * hold real value. Matches production key material only in format, not provenance.
     */
    private static ECKeyPair deriveDemoKeyPair(String label) {
        byte[] seed = Hash.sha3(("registerwerk-demo:" + label).getBytes(StandardCharsets.UTF_8));
        BigInteger privateKey = new BigInteger(1, seed);
        return ECKeyPair.create(privateKey);
    }

    private static String walletAddress(ECKeyPair keyPair) {
        return ("0x" + Keys.getAddress(keyPair)).toLowerCase();
    }

    /** keccak256 of the UTF-8 bytes of {@code value}, as a 0x-prefixed hex string. */
    private static String keccak256Hex(String value) {
        return Numeric.toHexString(Hash.sha3(value.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Signs the manifest hash exactly as the publish wizard does: EIP-191
     * personal_sign over the UTF-8 bytes of the hex hash *string* (not the raw 32
     * hash bytes) — see {@code ManifestSigningService.manifestHash}/{@code recoverSigner}.
     */
    private static String signManifestHash(String manifestHashHex, ECKeyPair signer) {
        Sign.SignatureData signature =
                Sign.signPrefixedMessage(manifestHashHex.getBytes(StandardCharsets.UTF_8), signer);
        byte[] combined = new byte[65];
        System.arraycopy(signature.getR(), 0, combined, 0, 32);
        System.arraycopy(signature.getS(), 0, combined, 32, 32);
        combined[64] = signature.getV()[0];
        return Numeric.toHexString(combined);
    }
}
