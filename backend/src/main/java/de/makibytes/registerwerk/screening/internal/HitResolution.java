package de.makibytes.registerwerk.screening.internal;

/**
 * How a hit was resolved. A false positive is the only resolution that clears the gate by itself
 * ({@code accepted=true}); a confirmed PEP stays unresolved for the gate until an enhanced-due-
 * diligence approval is recorded (6-17), because confirming a PEP is the opposite of "this is not them".
 */
public enum HitResolution {
    FALSE_POSITIVE,
    CONFIRMED_PEP
}
