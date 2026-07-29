package de.makibytes.registerwerk.screening.internal;

/**
 * What kind of watchlist match a {@link ScreeningHit} is. A PEP match is not itself
 * a reason to block — GwG §15 / AMLD requires enhanced due diligence, not automatic
 * rejection — whereas a SANCTIONS hit implicates a hard legal prohibition. Reviewers
 * must be able to tell the two apart instead of applying the same escalation to both.
 */
public enum HitCategory {
    SANCTIONS,
    PEP,
    ADVERSE_MEDIA
}
