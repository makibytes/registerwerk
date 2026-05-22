package de.makibytes.registerwerk.chain.api;

import java.util.UUID;

/** Published when a chain configuration is saved/updated, so blockchain can refresh its client pool. */
public record ChainConfigUpdatedEvent(UUID chainConfigId) {}
