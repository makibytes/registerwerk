package de.makibytes.registerwerk.asset.events;

import java.util.UUID;

/**
 * Published when register content concerning a holder changes
 * (§19(2) no. 2 — triggers a CHANGE register statement).
 */
public record HolderRegisterChangedEvent(UUID holderId) {}
