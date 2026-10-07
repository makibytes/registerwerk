package de.makibytes.registerwerk.stepup.web.dto;

/** Open requests waiting for the caller's decision (own requests excluded). */
public record ApprovalPendingCount(long count) {}
