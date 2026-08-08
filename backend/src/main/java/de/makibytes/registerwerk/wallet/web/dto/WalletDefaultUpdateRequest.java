package de.makibytes.registerwerk.wallet.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record WalletDefaultUpdateRequest(@NotNull UUID walletId) {}
