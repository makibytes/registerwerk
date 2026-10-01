package de.makibytes.registerwerk.kyc.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** EDD approval of a confirmed-PEP beneficial owner; {@code reviewDueDate} defaults to and is capped at six months. */
public record EddApprovalRequest(
        @NotBlank @Size(max = 4000) String note,
        LocalDate reviewDueDate
) {}
