package de.makibytes.registerwerk.application.exception;

public class LoginDisabledException extends RuntimeException {

    public LoginDisabledException() {
        super("Password login is disabled; use the configured identity provider");
    }
}
