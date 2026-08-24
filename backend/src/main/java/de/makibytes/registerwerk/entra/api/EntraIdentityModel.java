package de.makibytes.registerwerk.entra.api;

/**
 * How a user's identity is hosted, which decides whether Registerwerk can manage their
 * authentication methods at all.
 *
 * <p>Only {@link #WORKFORCE_MEMBER} and {@link #WORKFORCE_GUEST} live in the operator's own
 * tenant and are therefore manageable through Graph. {@link #FEDERATED} users authenticate
 * against their own organisation's Entra tenant — their MFA is their administrator's
 * responsibility and we must not attempt Graph calls against them.
 */
public enum EntraIdentityModel {

    /** Local HS256 account ({@code auth_provider = 'LOCAL'}) — Entra is not involved. */
    LOCAL(false),

    /** A member of the operator's workforce tenant. Full method set, TAP available. */
    WORKFORCE_MEMBER(true),

    /**
     * A B2B guest in the operator's workforce tenant. Manageable, but note that a
     * Temporary Access Pass cannot be issued to an <em>external</em> guest — only to an
     * internal guest whose methods are registered in this tenant.
     */
    WORKFORCE_GUEST(true),

    /** Identity lives in the customer's own tenant; we neither see nor manage their methods. */
    FEDERATED(false);

    private final boolean managedHere;

    EntraIdentityModel(boolean managedHere) {
        this.managedHere = managedHere;
    }

    /** True when authentication methods for this user can be read and mutated through our Graph app. */
    public boolean isManagedHere() {
        return managedHere;
    }
}
