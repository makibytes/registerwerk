package de.makibytes.registerwerk.stepup.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The one-time result of claiming an approved request. Send {@code approvalToken} as {@code X-Dual-Control-Token}
 * on exactly the request named by {@code target} ({@code "METHOD /path[?query]"}), with your own step-up token as
 * the Bearer, before {@code expiresAt}. It is single use and useless to anyone but the requester.
 */
public record ApprovalClaimResponse(UUID requestId, String approvalToken, Instant expiresAt, String action, String target,
                                    String headerName) {}
