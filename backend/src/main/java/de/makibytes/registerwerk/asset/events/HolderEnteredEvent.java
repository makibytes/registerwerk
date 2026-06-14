package de.makibytes.registerwerk.asset.events;

import java.util.UUID;

/**
 * Published when a holder is entered in the register in their favour
 * (§19(2) no. 1 — triggers an INITIAL_ENTRY register statement).
 */
public record HolderEnteredEvent(UUID holderId) {}
