package de.makibytes.registerwerk.shared;

public class LoginDisabledException extends RuntimeException {

    public LoginDisabledException() {
        super("Password login is disabled; use the configured identity provider");
    }
}
