package de.makibytes.registerwerk.customer.web.dto;

import de.makibytes.registerwerk.customer.api.ClientCategory;
import jakarta.validation.constraints.NotNull;

public record ClassifyClientRequest(@NotNull ClientCategory clientCategory) {}
