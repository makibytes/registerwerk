package de.makibytes.registerwerk.kyc.web.dto;

import jakarta.validation.constraints.Future;

import java.time.LocalDate;

public record KycApprovalRequest(@Future LocalDate expiryDate) {}
