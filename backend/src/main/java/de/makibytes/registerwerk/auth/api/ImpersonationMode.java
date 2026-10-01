package de.makibytes.registerwerk.auth.api;

/** READ_ONLY: GET/HEAD/OPTIONS only (default support mode). ACT_ON_BEHALF: writes, minus a deny-list. */
public enum ImpersonationMode {
    READ_ONLY,
    ACT_ON_BEHALF
}
