package de.makibytes.registerwerk.web.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record AddressResolveRequest(
        @NotEmpty List<String> addresses
) {}
