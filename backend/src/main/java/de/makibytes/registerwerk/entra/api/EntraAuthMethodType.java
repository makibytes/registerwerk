package de.makibytes.registerwerk.entra.api;

/**
 * The authentication-method kinds Microsoft Graph exposes under
 * {@code /users/{id}/authentication/*}, mapped from the {@code @odata.type} discriminator
 * returned by {@code GET /users/{id}/authentication/methods}.
 *
 * <p>{@link #collection()} is the Graph collection segment used for a targeted
 * {@code DELETE}. Types with a {@code null} collection cannot be deleted individually:
 * {@link #PASSWORD} is not a removable factor, and {@link #TEMPORARY_ACCESS_PASS} is
 * removed by issuing a new one (Entra permits only one TAP per user).
 */
public enum EntraAuthMethodType {

    MICROSOFT_AUTHENTICATOR("#microsoft.graph.microsoftAuthenticatorAuthenticationMethod",
            "microsoftAuthenticatorMethods"),
    SOFTWARE_OATH("#microsoft.graph.softwareOathAuthenticationMethod",
            "softwareOathMethods"),
    PHONE("#microsoft.graph.phoneAuthenticationMethod",
            "phoneMethods"),
    FIDO2("#microsoft.graph.fido2AuthenticationMethod",
            "fido2Methods"),
    WINDOWS_HELLO("#microsoft.graph.windowsHelloForBusinessAuthenticationMethod",
            "windowsHelloForBusinessMethods"),
    EMAIL("#microsoft.graph.emailAuthenticationMethod",
            "emailMethods"),
    PASSWORDLESS_PHONE_SIGN_IN("#microsoft.graph.passwordlessMicrosoftAuthenticatorAuthenticationMethod",
            "passwordlessMicrosoftAuthenticatorMethods"),
    TEMPORARY_ACCESS_PASS("#microsoft.graph.temporaryAccessPassAuthenticationMethod",
            null),
    PASSWORD("#microsoft.graph.passwordAuthenticationMethod",
            null),
    UNKNOWN(null, null);

    private final String odataType;
    private final String collection;

    EntraAuthMethodType(String odataType, String collection) {
        this.odataType = odataType;
        this.collection = collection;
    }

    public String odataType() {
        return odataType;
    }

    /** Graph collection segment for a targeted DELETE, or {@code null} when not individually removable. */
    public String collection() {
        return collection;
    }

    public boolean isDeletable() {
        return collection != null;
    }

    /**
     * True when this method counts as a second factor for the purposes of the customer-facing
     * "2FA is set up" indicator. A password is not a second factor, and a TAP is a temporary
     * recovery credential issued by support — treating either as "registered" would tell a user
     * they are protected when they are not.
     */
    public boolean isSecondFactor() {
        return this != PASSWORD && this != TEMPORARY_ACCESS_PASS && this != UNKNOWN;
    }

    public static EntraAuthMethodType fromOdataType(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        for (EntraAuthMethodType type : values()) {
            if (value.equalsIgnoreCase(type.odataType)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
