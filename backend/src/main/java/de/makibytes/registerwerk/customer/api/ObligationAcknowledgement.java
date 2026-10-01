package de.makibytes.registerwerk.customer.api;

/** An operator's explicit decision to terminate despite one open obligation ({@code KIND:ref}), with the reason. */
public record ObligationAcknowledgement(String obligationId, String reason) {}
