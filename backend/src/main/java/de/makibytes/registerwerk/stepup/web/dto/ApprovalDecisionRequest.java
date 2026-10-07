package de.makibytes.registerwerk.stepup.web.dto;

import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/approvals/{id}/approve} (code required: the approver's fresh 6-digit TOTP code) and
 * {@code .../reject} (code ignored, may be omitted).
 */
public record ApprovalDecisionRequest(@Size(max = 6) String code, @Size(max = 1000) String note) {}
