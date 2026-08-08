package de.makibytes.registerwerk.erc3643.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class Erc3643AgentRequests {

    private static final String EVM_ADDRESS = "^0x[0-9a-fA-F]{40}$";

    private Erc3643AgentRequests() {}

    public record ComplianceModule(
            @NotBlank @Pattern(regexp = EVM_ADDRESS) String moduleAddress,
            @NotBlank @Size(max = 100) String moduleType,
            @NotNull Map<String, Object> parameters
    ) {}

    public record ForcedTransfer(
            @NotBlank @Pattern(regexp = EVM_ADDRESS) String from,
            @NotBlank @Pattern(regexp = EVM_ADDRESS) String to,
            @NotNull @Positive BigDecimal amount,
            @NotBlank @Size(max = 2000) String reason
    ) {}

    public record ForcedApprove(
            @NotBlank @Pattern(regexp = EVM_ADDRESS) String owner,
            @NotBlank @Pattern(regexp = EVM_ADDRESS) String spender,
            @NotNull @Positive BigDecimal amount,
            @NotBlank @Size(max = 2000) String reason
    ) {}

    public record Address(
            @NotBlank @Pattern(regexp = EVM_ADDRESS) String address
    ) {}

    public record ForceBurn(
            @NotBlank @Pattern(regexp = EVM_ADDRESS) String from,
            @NotNull @Positive BigDecimal amount,
            @NotBlank @Size(max = 2000) String legalBasis
    ) {}

    public record BatchTransfer(
            @NotEmpty @Size(max = 200) List<@NotBlank @Pattern(regexp = EVM_ADDRESS) String> froms,
            @NotEmpty @Size(max = 200) List<@NotBlank @Pattern(regexp = EVM_ADDRESS) String> tos,
            @NotEmpty @Size(max = 200) List<@NotNull @Positive BigDecimal> amounts
    ) {
        @AssertTrue(message = "froms, tos and amounts must have the same size")
        public boolean isAligned() {
            return froms != null && tos != null && amounts != null
                    && froms.size() == tos.size() && froms.size() == amounts.size();
        }
    }

    public record BatchAmounts(
            @NotEmpty @Size(max = 200) List<@NotBlank @Pattern(regexp = EVM_ADDRESS) String> addresses,
            @NotEmpty @Size(max = 200) List<@NotNull @Positive BigDecimal> amounts
    ) {
        @AssertTrue(message = "addresses and amounts must have the same size")
        public boolean isAligned() {
            return addresses != null && amounts != null && addresses.size() == amounts.size();
        }
    }
}
