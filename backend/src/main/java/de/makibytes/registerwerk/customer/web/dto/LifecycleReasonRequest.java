package de.makibytes.registerwerk.customer.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Mandatory reason for suspending or reactivating an entity; persisted on the audit event. */
public record LifecycleReasonRequest(@NotBlank @Size(max = 2000) String reason) {}
