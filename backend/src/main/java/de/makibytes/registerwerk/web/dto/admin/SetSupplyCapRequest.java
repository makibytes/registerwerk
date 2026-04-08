package de.makibytes.registerwerk.web.dto.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigInteger;

/**
 * Request body for {@code POST .../admin/set-supply-cap}.
 *
 * <p>Legal basis: MiCAR Art. 46 — competent authority can impose a regulatory ceiling
 * on the number of crypto-assets in circulation.
 *
 * @param newCap New supply ceiling in smallest token unit; {@code 0} removes any existing cap.
 */
public record SetSupplyCapRequest(
        @NotNull
        @PositiveOrZero
        BigInteger newCap
) {}
