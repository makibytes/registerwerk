-- Phase 5 veto fixup (N2): V28 re-armed every PENDING/FAILED delivery, including those of subscriptions that
-- are disabled (circuit breaker, URL policy, owner). The retry sweep now skips disabled subscriptions; also
-- disarm their rows so nothing replays stale backlog the moment a subscription is re-enabled.
UPDATE webhook_delivery d
   SET next_attempt_at = NULL
  FROM webhook_subscription s
 WHERE s.id = d.subscription_id
   AND s.enabled = FALSE
   AND d.status IN ('PENDING', 'FAILED')
   AND d.next_attempt_at IS NOT NULL;
