package de.makibytes.registerwerk.repo.events;

import java.util.List;
import java.util.UUID;

/** Asks the notification module to tell the company administrators of the listed entities about a repo event. */
public record RepoPartyNoticeEvent(UUID tradeId, List<UUID> entityIds, String subject, String message) {}
