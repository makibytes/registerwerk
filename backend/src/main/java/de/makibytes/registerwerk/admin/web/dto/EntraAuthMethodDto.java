package de.makibytes.registerwerk.admin.web.dto;

import java.time.Instant;

/**
 * One registered authentication method, as shown in the operator support console.
 *
 * @param deletable whether this method can be removed individually — a password is not a
 *                  removable factor, and a Temporary Access Pass is replaced rather than deleted
 */
public record EntraAuthMethodDto(
        String id,
        String type,
        String label,
        boolean isDefault,
        boolean deletable,
        Instant createdAt) {
}
