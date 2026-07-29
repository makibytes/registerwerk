package de.makibytes.registerwerk.kyc.api;

import de.makibytes.registerwerk.kyc.api.KycDocument.DocumentType;
import de.makibytes.registerwerk.customer.api.Jurisdiction;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hard-coded KYC document requirements per regulatory jurisdiction.
 * Based on EU-AMLD 4th/5th/6th baseline plus jurisdiction-specific additions:
 *   DE_EWPG : BaFin / eWpG / GwG §10-17
 *   LU_CSSF : CSSF Circular 19/732 / AML Law 2004
 *   FR_AMF  : Code monétaire et financier / Loi PACTE
 *   LI_TVTG : TVTG 2020 / FMA Liechtenstein / AML Law
 */
@Component
public class JurisdictionRequirementConfig {

    /** 90 days — standard max age for register extracts across all EU jurisdictions. */
    private static final Duration REGISTER_MAX_AGE = Duration.ofDays(90);

    public record DocumentRequirement(
        DocumentType documentType,
        boolean mandatory,
        Duration maxAge,       // null = no age limit
        String localName,      // jurisdiction-specific label
        String description
    ) {}

    /**
     * Per-jurisdiction compliance metadata for sanctions, regulatory reporting,
     * KYC cadence, beneficial-owner thresholds, and data retention.
     */
    public record ComplianceMetadata(
        List<String> sanctionsProviders,        // e.g. ["OPEN_SANCTIONS"]
        String supervisoryAuthority,            // "BaFin" | "CSSF" | "AMF" | "FMA"
        String incidentReportingEndpoint,       // DORA Art. 17 initial-report endpoint
        boolean mifirApplicable,               // whether RTS 22 transaction reporting applies
        boolean micarApplicable,               // whether MiCAR (where not MiFID II instrument) applies
        Duration kycRefreshCadence,            // how often ongoing monitoring re-screens
        double beneficialOwnerThresholdPct,    // 25.0 = GwG §3 standard
        Duration dataRetentionPeriod,          // regulatory retention (GwG §8: 5y; eWpG §15: 10y)
        DefiInteropModel defiInteropModel,     // permitted DeFi bridge model, see DefiInteropModel
        boolean permissionlessAmmAllowed       // explicit, not implicit — always false today
    ) {}

    public record JurisdictionProfile(
        Jurisdiction jurisdiction,
        List<DocumentRequirement> issuerRequirements,
        ComplianceMetadata compliance
    ) {}

    private final Map<Jurisdiction, JurisdictionProfile> profiles;

    public JurisdictionRequirementConfig() {
        profiles = new EnumMap<>(Jurisdiction.class);
        profiles.put(Jurisdiction.DE_EWPG, buildDeEwpg());
        profiles.put(Jurisdiction.LU_CSSF, buildLuCssf());
        profiles.put(Jurisdiction.FR_AMF,  buildFrAmf());
        profiles.put(Jurisdiction.LI_TVTG, buildLiTvtg());
    }

    public JurisdictionProfile getProfile(Jurisdiction jurisdiction) {
        JurisdictionProfile profile = profiles.get(jurisdiction);
        if (profile == null) {
            throw new IllegalArgumentException("No KYC profile configured for jurisdiction: " + jurisdiction);
        }
        return profile;
    }

    public List<JurisdictionProfile> getAllProfiles() {
        return List.copyOf(profiles.values());
    }

    // ── Register-document labeling (§19 eWpG + cross-jurisdiction analogues) ───

    /**
     * Standard nominee-custody disclaimer shown on every collectively-held
     * (Sammeleintragung) holding confirmation, regardless of jurisdiction: the
     * registered holder of record is the custodian, not the investor, so the
     * document is a beneficial-holding confirmation, never a register statement.
     */
    private static final String NOMINEE_DISCLAIMER =
        "Der eingetragene Registerinhaber ist die depotführende Verwahrstelle (nominee), nicht der Anleger. "
        + "Dieses Dokument bestätigt den wirtschaftlichen Bestand im Rahmen der Sammelverwahrung / Sammeleintragung "
        + "und ersetzt keinen individuellen Registerauszug bzw. keine individuelle Bestandsbescheinigung. / "
        + "The registered holder of record is the custodian (nominee), not the investor. This document confirms "
        + "the beneficial holding under collective custody and does not substitute for an individual register "
        + "statement or holding confirmation.";

    private static final String LOCAL_COUNSEL_REVIEW_NOTE_LU =
        "Dieses Dokument ist eine Bestätigung nach luxemburgischem Recht und keine mit dem deutschen §19-eWpG-"
        + "Registerauszug identische gesetzliche Form; eine Prüfung durch luxemburgische Rechtsberater wird "
        + "empfohlen. / This document is a Luxembourg-law confirmation, not a document identical to the German "
        + "statutory §19 eWpG Registerauszug; review by Luxembourg counsel is recommended.";

    private static final String LOCAL_COUNSEL_REVIEW_NOTE_LI =
        "Dieses Dokument ist eine Bestätigung nach liechtensteinischem Recht (TVTG) und keine deutsche eWpG-"
        + "Registerauszug-Entsprechung; eine Prüfung durch liechtensteinische Rechtsberater wird empfohlen. / "
        + "This document is a Liechtenstein-law (TVTG) confirmation, not an equivalent of the German eWpG "
        + "Registerauszug; review by Liechtenstein counsel is recommended.";

    /**
     * Resolves the register-document profile (title / legal basis / labeling) for a
     * holder's self-service register-document download, given the asset's jurisdiction
     * and whether this specific holder entry is individual (Einzeleintragung, §17(2)
     * eWpG) or collective (Sammeleintragung / nominee custody). A null jurisdiction
     * (legacy/incomplete asset data) defaults to DE_EWPG, the registry's home
     * jurisdiction.
     *
     * <p>Only DE_EWPG-individual and FR_AMF-individual are wired as {@code statutory}
     * — see {@link RegisterDocumentProfile}.
     */
    public RegisterDocumentProfile resolveRegisterDocumentProfile(Jurisdiction jurisdiction, boolean individualEntry) {
        Jurisdiction effective = jurisdiction != null ? jurisdiction : Jurisdiction.DE_EWPG;
        return switch (effective) {
            case DE_EWPG -> individualEntry
                    ? new RegisterDocumentProfile("REGISTERAUSZUG", "Registerauszug",
                        "Register statement pursuant to § 19 eWpG (Gesetz über elektronische Wertpapiere)",
                        null, true)
                    : new RegisterDocumentProfile("BESTANDSBESTAETIGUNG", "Bestandsbestätigung / Holding confirmation",
                        "Holding confirmation for a collectively held (Sammeleintragung) position — "
                        + "not a § 19 eWpG register statement",
                        NOMINEE_DISCLAIMER, false);
            case FR_AMF -> individualEntry
                    ? new RegisterDocumentProfile("ATTESTATION_INSCRIPTION_COMPTE", "Attestation d'inscription en compte",
                        "Attestation pursuant to Art. L211-3 et seq. Code monétaire et financier (régime DEEP, Loi PACTE)",
                        null, true)
                    : new RegisterDocumentProfile("CONFIRMATION_DETENTION", "Confirmation de détention / Holding confirmation",
                        "Holding confirmation for a collectively held (nominee) position — "
                        + "not an individual account-registration attestation",
                        NOMINEE_DISCLAIMER, false);
            case LU_CSSF -> new RegisterDocumentProfile(
                    individualEntry ? "ATTESTATION_DETENTION_LU" : "CONFIRMATION_DETENTION_COLLECTIVE_LU",
                    individualEntry ? "Attestation de détention de titres" : "Confirmation de détention (dépôt collectif)",
                    "Confirmation based on the loi du 6 avril 2013 relative à la dématérialisation and applicable "
                    + "Luxembourg DLT laws",
                    individualEntry ? LOCAL_COUNSEL_REVIEW_NOTE_LU : LOCAL_COUNSEL_REVIEW_NOTE_LU + " " + NOMINEE_DISCLAIMER,
                    false);
            case LI_TVTG -> new RegisterDocumentProfile(
                    individualEntry ? "TOKEN_BESTAETIGUNG_LI" : "TOKEN_BESTAETIGUNG_COLLECTIVE_LI",
                    individualEntry ? "Token-Bestätigung / Confirmation of token holding" : "Token-Bestätigung (Sammelverwahrung)",
                    "Confirmation under the Liechtenstein TVTG (Token- und VT-Dienstleister-Gesetz) token-container model",
                    individualEntry ? LOCAL_COUNSEL_REVIEW_NOTE_LI : LOCAL_COUNSEL_REVIEW_NOTE_LI + " " + NOMINEE_DISCLAIMER,
                    false);
        };
    }

    // ── Germany — eWpG / BaFin ────────────────────────────────────────────────

    private static final ComplianceMetadata DE_EWPG_COMPLIANCE = new ComplianceMetadata(
        List.of("OPEN_SANCTIONS", "REFINITIV_WORLD_CHECK"),
        "BaFin",
        "https://portal.mvp.bafin.de/",
        true,   // MiFIR RTS 22 applies for eWpG tokens classified as MiFID II financial instruments
        false,  // MiCAR does NOT apply to MiFID II financial instruments (eWpG securities)
        Duration.ofDays(365),
        25.0,   // GwG §3 Abs. 2 — 25% beneficial owner threshold
        Duration.ofDays(365 * 10), // eWpG §15(3) — 10-year retention for registry entries
        // eWpG's Sammelverwahrung (collective custody) concept already recognizes a custodian
        // holding electronic securities in one account on behalf of tracked beneficial owners —
        // the natural basis for a nominee/omnibus DeFi bridge. No MiFID II exemption exists for
        // an anonymous, unlicensed pool holding the security directly.
        DefiInteropModel.NOMINEE_POOL, false
    );

    private static final ComplianceMetadata LU_CSSF_COMPLIANCE = new ComplianceMetadata(
        List.of("OPEN_SANCTIONS"),
        "CSSF",
        "https://www.cssf.lu/en/complaints/",
        true,   // MiFIR via MiFID II transposition
        true,   // MiCAR applies for non-MiFID-II crypto-assets
        Duration.ofDays(365),
        25.0,
        Duration.ofDays(365 * 5), // AML Law 2004 Art. 4 — 5 years minimum
        // Luxembourg custodian/depositary structures (CSSF-supervised) can hold securities in
        // omnibus form on behalf of underlying clients; same nominee basis as DE_EWPG. No
        // permissionless-pool exemption under MiFID II.
        DefiInteropModel.NOMINEE_POOL, false
    );

    private static final ComplianceMetadata FR_AMF_COMPLIANCE = new ComplianceMetadata(
        List.of("OPEN_SANCTIONS"),
        "AMF",
        "https://www.amf-france.org/",
        true,
        true,
        Duration.ofDays(365),
        25.0,
        Duration.ofDays(365 * 5), // LCB-FT — 5 years
        // French custodian-account holder (teneur de compte-conservation) regime already
        // supports nominee/omnibus holding under the CMF; same basis as DE_EWPG/LU_CSSF.
        DefiInteropModel.NOMINEE_POOL, false
    );

    private static final ComplianceMetadata LI_TVTG_COMPLIANCE = new ComplianceMetadata(
        List.of("OPEN_SANCTIONS"),
        "FMA",
        "https://www.fma-li.li/",
        false,  // MiFIR applies only via EEA passporting
        true,   // TVTG (SPDG) token service providers subject to MiCAR-equivalent
        Duration.ofDays(365),
        25.0,   // SPG (Due Diligence Act) — 25% threshold
        Duration.ofDays(365 * 10), // TVTG §33 — 10-year retention
        // TVTG's token-container model separates the legal right from the token and explicitly
        // contemplates a licensed VT Service Provider acting as an intermediary/custodian —
        // the same nominee basis as the other three jurisdictions.
        DefiInteropModel.NOMINEE_POOL, false
    );

    private JurisdictionProfile buildDeEwpg() {
        return new JurisdictionProfile(Jurisdiction.DE_EWPG, List.of(
            req(DocumentType.CERTIFICATE_OF_INCORPORATION, true, null,
                "Gesellschaftsvertrag / Satzung",
                "Articles of association or partnership agreement of the issuing entity."),
            req(DocumentType.COMMERCIAL_REGISTER_EXTRACT, true, REGISTER_MAX_AGE,
                "Handelsregisterauszug (≤ 3 Monate)",
                "Current extract from the German commercial register (Handelsregister), not older than 90 days."),
            req(DocumentType.UBO_DECLARATION, true, null,
                "UBO-Erklärung / Transparenzregister",
                "Declaration of ultimate beneficial owners (≥ 25% ownership or control) per GwG §3."),
            req(DocumentType.BENEFICIAL_OWNER_REGISTER_EXTRACT, true, REGISTER_MAX_AGE,
                "Transparenzregisterauszug",
                "Extract from the German transparency register (Transparenzregister), not older than 90 days."),
            req(DocumentType.OWNERSHIP_STRUCTURE_CHART, true, null,
                "Beteiligungsstruktur",
                "Chart showing all ownership layers down to natural persons with percentages."),
            req(DocumentType.IDENTITY_DOCUMENT, true, null,
                "Ausweisdokumente (Geschäftsführer + UBOs)",
                "Valid passport or national ID card for all managing directors and UBOs."),
            req(DocumentType.BOARD_RESOLUTION, true, null,
                "Gesellschafterbeschluss zur Wertpapieremission",
                "Board or shareholders' resolution specifically authorizing the securities issuance."),
            req(DocumentType.AML_QUESTIONNAIRE, true, null,
                "Geldwäsche-Fragebogen (GwG)",
                "Completed AML/CFT risk questionnaire per GwG §10."),
            req(DocumentType.ANNUAL_REPORT, false, null,
                "Jahresabschluss (empfohlen)",
                "Latest audited annual financial statements (recommended, required for institutional issuers)."),
            req(DocumentType.LEI_CERTIFICATE, false, null,
                "LEI-Zertifikat (empfohlen)",
                "Legal Entity Identifier certificate (recommended for institutional issuers).")
        ), DE_EWPG_COMPLIANCE);
    }

    // ── Luxembourg — CSSF ─────────────────────────────────────────────────────

    private JurisdictionProfile buildLuCssf() {
        return new JurisdictionProfile(Jurisdiction.LU_CSSF, List.of(
            req(DocumentType.CERTIFICATE_OF_INCORPORATION, true, null,
                "Statuts / Certificate of Incorporation",
                "Articles of association or statuts of the Luxembourg or foreign entity."),
            req(DocumentType.COMMERCIAL_REGISTER_EXTRACT, true, REGISTER_MAX_AGE,
                "Extrait du RCS (≤ 3 mois)",
                "Current extract from the Luxembourg Register of Commerce and Companies (RCS), not older than 90 days."),
            req(DocumentType.UBO_DECLARATION, true, null,
                "Déclaration UBO / RBE",
                "Ultimate beneficial owner declaration. Entity must be registered in the Luxembourg Registre des Bénéficiaires Effectifs (RBE)."),
            req(DocumentType.BENEFICIAL_OWNER_REGISTER_EXTRACT, true, REGISTER_MAX_AGE,
                "Extrait du RBE",
                "Extract from the Luxembourg Register of Beneficial Owners (RBE), not older than 90 days."),
            req(DocumentType.OWNERSHIP_STRUCTURE_CHART, true, null,
                "Organigramme de propriété",
                "Chart showing all ownership and control layers down to natural persons."),
            req(DocumentType.SHAREHOLDER_REGISTER, true, null,
                "Registre des actionnaires",
                "Current shareholder register showing all shareholders and their voting rights."),
            req(DocumentType.IDENTITY_DOCUMENT, true, null,
                "Pièces d'identité (administrateurs + UBOs)",
                "Valid passport or EU national ID for all directors and beneficial owners."),
            req(DocumentType.BOARD_RESOLUTION, true, null,
                "Résolution du conseil d'administration",
                "Board resolution authorizing the securities issuance and appointing signatories."),
            req(DocumentType.ANNUAL_REPORT, true, null,
                "Rapports annuels (2 dernières années)",
                "Audited annual financial statements for the last 2 fiscal years."),
            req(DocumentType.SOURCE_OF_FUNDS, true, null,
                "Justificatifs de l'origine des fonds",
                "Documentation of the origin of the funds used for the securities issuance."),
            req(DocumentType.AML_QUESTIONNAIRE, true, null,
                "Questionnaire LBC/FT (CSSF Circ. 19/732)",
                "Completed AML/CFT questionnaire per CSSF Circular 19/732."),
            req(DocumentType.LEI_CERTIFICATE, false, null,
                "Certificat LEI (recommandé)",
                "Legal Entity Identifier certificate (recommended per ESMA guidelines).")
        ), LU_CSSF_COMPLIANCE);
    }

    // ── France — AMF ──────────────────────────────────────────────────────────

    private JurisdictionProfile buildFrAmf() {
        return new JurisdictionProfile(Jurisdiction.FR_AMF, List.of(
            req(DocumentType.CERTIFICATE_OF_INCORPORATION, true, null,
                "Statuts de la société",
                "Articles of association (statuts) of the issuing entity."),
            req(DocumentType.COMMERCIAL_REGISTER_EXTRACT, true, REGISTER_MAX_AGE,
                "Extrait Kbis (≤ 3 mois)",
                "Current Kbis extract from the French Companies Register (RCS), not older than 90 days."),
            req(DocumentType.UBO_DECLARATION, true, null,
                "Déclaration des bénéficiaires effectifs",
                "Declaration identifying all beneficial owners with ≥ 25% ownership or control per Loi PACTE."),
            req(DocumentType.OWNERSHIP_STRUCTURE_CHART, true, null,
                "Organigramme actionnarial",
                "Ownership structure chart showing all layers down to natural persons."),
            req(DocumentType.IDENTITY_DOCUMENT, true, null,
                "Pièces d'identité (dirigeants + bénéficiaires effectifs)",
                "Valid passport or French national ID card for all directors and beneficial owners."),
            req(DocumentType.BOARD_RESOLUTION, true, null,
                "Procès-verbal d'assemblée / décision de direction",
                "Board or shareholders' resolution authorizing the securities issuance."),
            req(DocumentType.ANNUAL_REPORT, true, null,
                "Derniers rapports annuels",
                "Audited annual financial statements for the most recent fiscal year."),
            req(DocumentType.AML_QUESTIONNAIRE, true, null,
                "Questionnaire LCB-FT",
                "Completed AML/CFT questionnaire per French Monetary and Financial Code (CMF)."),
            req(DocumentType.LEI_CERTIFICATE, false, null,
                "Certificat LEI (recommandé)",
                "Legal Entity Identifier certificate (recommended by AMF for securities issuers).")
        ), FR_AMF_COMPLIANCE);
    }

    // ── Liechtenstein — TVTG / FMA ────────────────────────────────────────────

    private JurisdictionProfile buildLiTvtg() {
        return new JurisdictionProfile(Jurisdiction.LI_TVTG, List.of(
            req(DocumentType.CERTIFICATE_OF_INCORPORATION, true, null,
                "Gesellschaftsstatuten / Articles",
                "Articles of association of the issuing entity registered in Liechtenstein or EEA."),
            req(DocumentType.COMMERCIAL_REGISTER_EXTRACT, true, REGISTER_MAX_AGE,
                "Handelsregisterauszug FL (≤ 3 Monate)",
                "Current extract from the Liechtenstein Office of Justice (Amt für Justiz) register, not older than 90 days."),
            req(DocumentType.UBO_DECLARATION, true, null,
                "WB-Erklärung (wirtschaftlicher Berechtigter)",
                "Ultimate beneficial owner declaration per Liechtenstein Due Diligence Act (SPG)."),
            req(DocumentType.OWNERSHIP_STRUCTURE_CHART, true, null,
                "Eigentümerstruktur",
                "Chart showing all ownership layers down to natural persons with percentages."),
            req(DocumentType.IDENTITY_DOCUMENT, true, null,
                "Ausweisdokumente (Geschäftsführer + WBs)",
                "Valid passport or EEA national ID for all managing directors and beneficial owners."),
            req(DocumentType.BOARD_RESOLUTION, true, null,
                "Geschäftsleitungsbeschluss zur Tokenisierung",
                "Management or board resolution specifically authorizing the tokenized securities issuance."),
            req(DocumentType.ANNUAL_REPORT, true, null,
                "Jahresbericht",
                "Audited annual financial statements for the most recent fiscal year."),
            req(DocumentType.AML_QUESTIONNAIRE, true, null,
                "Geldwäsche-Fragebogen (SPG/FATF)",
                "Completed AML/CFT questionnaire per Liechtenstein Due Diligence Act and FATF recommendations."),
            req(DocumentType.TOKEN_WHITEPAPER, true, null,
                "Token-Informationsdokument (TVTG §9)",
                "Mandatory information document (white paper) for the token per TVTG §9, describing the token container model and rights."),
            req(DocumentType.SMART_CONTRACT_AUDIT, true, null,
                "Smart-Contract-Sicherheitsaudit",
                "Independent security audit of the smart contract code by a qualified auditor (required by FMA for tokenized securities)."),
            req(DocumentType.REGULATORY_LICENSE, false, null,
                "TVTG-Dienstleisternachricht (wenn zutreffend)",
                "If acting as a TT Service Provider (TVTG §12), notification or registration with FMA Liechtenstein.")
        ), LI_TVTG_COMPLIANCE);
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    private static DocumentRequirement req(DocumentType type, boolean mandatory, Duration maxAge,
                                           String localName, String description) {
        return new DocumentRequirement(type, mandatory, maxAge, localName, description);
    }
}
