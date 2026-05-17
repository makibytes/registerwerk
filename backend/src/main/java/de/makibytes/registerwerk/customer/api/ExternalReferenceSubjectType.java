package de.makibytes.registerwerk.customer.api;

public enum ExternalReferenceSubjectType {
    LEGAL_ENTITY("LegalEntity"),
    ASSET("Asset"),
    ASSET_HOLDER("AssetHolder"),
    ERC3643_IDENTITY_REGISTRY_ENTRY("Erc3643IdentityRegistry");

    private final String auditSubjectType;

    ExternalReferenceSubjectType(String auditSubjectType) {
        this.auditSubjectType = auditSubjectType;
    }

    public String auditSubjectType() {
        return auditSubjectType;
    }
}
