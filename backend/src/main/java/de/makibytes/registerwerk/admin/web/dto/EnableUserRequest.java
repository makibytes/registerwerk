package de.makibytes.registerwerk.admin.web.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional body of {@code POST /admin/users/{id}/enable}. {@code reinstatementReason} (at least 10
 * characters) is mandatory when the account's latest access-review decision was REVOKED.
 */
public record EnableUserRequest(@Size(max = 1000) String reinstatementReason) {}
