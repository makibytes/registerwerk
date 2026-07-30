package de.makibytes.registerwerk.entra.api;

/**
 * The requested operation cannot apply to this user because of how their identity is hosted —
 * not a failure, a category error. Maps to 409.
 *
 * <p>Two cases produce it, and both are refused <em>before</em> any Graph call so the operator
 * gets an explanation instead of a confusing 404:
 * <ul>
 *   <li>{@link EntraIdentityModel#FEDERATED} — the user does not exist in our tenant; their own
 *       organisation's administrator manages their methods.</li>
 *   <li>An <em>external</em> B2B guest and a Temporary Access Pass — Entra does not permit
 *       issuing a TAP to an external guest. Reset their methods and have them re-register instead.</li>
 * </ul>
 */
public class EntraUnsupportedForIdentityModelException extends RuntimeException {

    private final EntraIdentityModel identityModel;

    public EntraUnsupportedForIdentityModelException(String message, EntraIdentityModel identityModel) {
        super(message);
        this.identityModel = identityModel;
    }

    public EntraIdentityModel getIdentityModel() {
        return identityModel;
    }
}
