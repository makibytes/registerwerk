package de.makibytes.registerwerk.entra.api;

/**
 * A Microsoft Graph call failed. Carries the Graph error code (e.g. {@code "Request_ResourceNotFound"},
 * {@code "Authentication_RequestFromUnsupportedUserRole"}) so callers and the operator UI can
 * distinguish "this user has no such method" from "our service principal lacks the directory role".
 */
public class EntraDirectoryException extends RuntimeException {

    private final int httpStatus;
    private final String graphErrorCode;

    public EntraDirectoryException(String message, int httpStatus, String graphErrorCode, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.graphErrorCode = graphErrorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getGraphErrorCode() {
        return graphErrorCode;
    }
}
