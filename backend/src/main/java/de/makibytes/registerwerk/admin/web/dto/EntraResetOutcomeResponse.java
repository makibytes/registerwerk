package de.makibytes.registerwerk.admin.web.dto;

import java.util.List;

/**
 * Result of removing every deletable authentication method.
 *
 * @param deleted  labels of the methods that were removed
 * @param failures per-method reasons for anything that could not be removed — reported rather
 *                 than thrown, so a partial reset is visible instead of silently half-applied
 */
public record EntraResetOutcomeResponse(boolean complete, List<String> deleted, List<String> failures) {
}
