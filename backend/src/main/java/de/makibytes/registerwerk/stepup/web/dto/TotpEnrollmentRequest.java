package de.makibytes.registerwerk.stepup.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Starting an enrolment re-proves the account password (no trust-on-first-use). */
public record TotpEnrollmentRequest(@NotBlank @Size(max = 200) String currentPassword) {}
