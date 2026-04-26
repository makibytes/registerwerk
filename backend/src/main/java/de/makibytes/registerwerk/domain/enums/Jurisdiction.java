package de.makibytes.registerwerk.domain.enums;

/**
 * Supported regulatory jurisdictions for securities issuance.
 * Each asset carries a jurisdiction that determines which KYC documents
 * the issuer must provide before the asset can be approved.
 */
public enum Jurisdiction {

    DE_EWPG(
        "Germany — eWpG / BaFin",
        "BaFin",
        "eWpG, GwG §10-17, KWG, eWpRV"
    ),
    LU_CSSF(
        "Luxembourg — CSSF",
        "CSSF",
        "AML Law 2004, AMLD 5th/6th, CSSF Circular 19/732"
    ),
    FR_AMF(
        "France — AMF",
        "AMF",
        "Code monétaire et financier (CMF), Loi PACTE, AMLD transposition"
    ),
    LI_TVTG(
        "Liechtenstein — TVTG / FMA",
        "FMA Liechtenstein",
        "TVTG 2020, AML Law, EU Prospectus Regulation"
    );

    public final String displayName;
    public final String regulator;
    public final String applicableLaw;

    Jurisdiction(String displayName, String regulator, String applicableLaw) {
        this.displayName = displayName;
        this.regulator = regulator;
        this.applicableLaw = applicableLaw;
    }
}
