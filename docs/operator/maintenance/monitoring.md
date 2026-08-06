---
title: Monitoring
---

# Monitoring

The registry ships with a Prometheus + Grafana monitoring stack. This page describes what to
monitor, which metrics matter, and how to configure alerting.

## Starting the monitoring stack

The main stack (`docker compose up --build` from the repo root) must already be running first —
the monitoring stack joins its `registerwerk_default` network to reach `backend`/`kong`.

```bash
docker compose -f monitoring/docker-compose.yml up -d
```

This starts:
- **Prometheus** at `http://localhost:9090`
- **Grafana** at `http://localhost:3000` (login: `admin` / `$GRAFANA_ADMIN_PASSWORD`, see `.env.example`)
- **Alertmanager** at `http://localhost:9093`
- **postgres-exporter** and **node-exporter** (feed the Database Health / host metrics panels)

## Health endpoints

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Overall health (UP/DOWN) |
| `GET /actuator/health/liveness` | Process alive — used by the Helm chart's liveness probe |
| `GET /actuator/health/readiness` | DB + chain connections ready — used by the readiness probe |
| `GET /actuator/prometheus` | Prometheus metrics scrape (unauthenticated — see `SecurityConfig`) |

## Key metrics to monitor

These are the actual Micrometer metrics registered in the backend (each below the module that
owns it) — every one has a corresponding rule in `monitoring/alerts/registerwerk.yml` and panel
in `monitoring/grafana/dashboards/registerwerk-overview.json`.

### Audit chain integrity (`audit` module)

| Metric | Description | Alert threshold |
|--------|-------------|----------------|
| `registerwerk_audit_chain_valid` | 1 if the last hash-chain verification passed, 0 if broken | 0 = CRITICAL (`AuditChainBroken`) |
| `registerwerk_audit_signing_key_age_seconds` | Seconds since the active audit-chain Ed25519 signing key was created/rotated | > 90 days = WARN (`AuditSigningKeyAgeWarning`) |

### Indexer health (`indexer` module)

| Metric | Description | Alert threshold |
|--------|-------------|----------------|
| `registerwerk_indexer_last_sync_timestamp_seconds{chain_config_id,indexer_type}` | Unix epoch of each indexer's last successful sync; 0 if never synced | `time() - metric` > 30 min = WARN, > 2h = CRITICAL |
| `registerwerk_chain_drift_open_total` | Count of currently OPEN `chain_drift_event` rows (registry vs. on-chain balance divergence, eWpG §16) | > 0 = CRITICAL (`ChainDriftDetected`) |

### Sanctions/screening (`screening` module)

| Metric | Description | Alert threshold |
|--------|-------------|----------------|
| `registerwerk_sanctions_oldest_open_hit_seconds` | Age in seconds of the longest-unresolved open sanctions/PEP hit; 0 if none open | > 4h = CRITICAL (`SanctionsHitOpenTooLong`, GwG §10) |
| `registerwerk_screening_errors_recent_total` | Count of `ScreeningRun` rows with status=ERROR in the last 24h — provider-call failures, distinct from the hit-age gauge above | > 5 = CRITICAL (`ScreeningErrorsElevated`) — `ScreeningGateImpl` fails closed on this, silently blocking new-entity approvals |
| `registerwerk_screening_periodic_refresh_last_failures` | Entities that failed re-screening in the most recent daily periodic refresh | > 0 = WARN (`ScreeningPeriodicRefreshFailures`) |

### Confidential-token reconciliation (`blockchain` module)

No dedicated table records mismatches here (unlike `chain_drift_event`) — both gauges are an
in-memory snapshot of the most recent `ConfidentialBalanceReconciliationService.reconcile()` run,
not a live DB query.

| Metric | Description | Alert threshold |
|--------|-------------|----------------|
| `registerwerk_confidential_reconciliation_mismatch_total` | Sum of the most recent mismatch count across all confidential assets | > 0 = CRITICAL (`ConfidentialReconciliationMismatchDetected`) |
| `registerwerk_confidential_reconciliation_last_run_timestamp_seconds` | Unix epoch of the most recent reconciliation run (any asset) | `time() - metric` > 1h = WARN (`ConfidentialReconciliationStale`) — catches a misconfigured Zama relayer silently halting the sweep |

### RPC node health (`blockchain` module)

| Metric | Description | Alert threshold |
|--------|-------------|----------------|
| `registerwerk_rpc_nodes_unhealthy_total` | Count of `RpcNode` rows currently marked unhealthy | > 0 for 2m = WARN (`RpcNodesUnhealthy`) |

### OrgIdentity onchain reconciliation (`orgidentity` module)

Same "no dedicated table" caveat as confidential reconciliation above — these are
reset-then-recount-per-sweep gauges (every active row is re-examined each 5-minute cycle, so
this accurately reflects "currently open drift" without needing new persisted state).

| Metric | Description | Alert threshold |
|--------|-------------|----------------|
| `registerwerk_org_chain_drift_open_total` | Org registrations/member wallets disagreeing with onchain `OrgRegistry` in the most recent sweep | > 0 = CRITICAL (`OrgChainDriftDetected`) |
| `registerwerk_permission_chain_drift_open_total` | Permission grants disagreeing with onchain `PermissionRegistry`, incl. role-restriction flips | > 0 = CRITICAL (`PermissionChainDriftDetected`) |

### DORA reporting deadlines (`dora` module)

| Metric | Description | Alert threshold |
|--------|-------------|----------------|
| `registerwerk_dora_deadline_breaches{breach_type}` | Overdue count per breach type (`classification`/`initial_report`/`final_report`/`resilience_test`), live-queried via the same repository calls `checkDeadlines()` already runs daily | `sum(...)` > 0 = CRITICAL (`DoraDeadlineBreach`, Art. 19) |

### Regulatory-report staleness (`regreporting` module)

| Metric | Description | Alert threshold |
|--------|-------------|----------------|
| `registerwerk_regreport_stale_submissions_total` | Count of `TRANSPORTED_UNVERIFIED` draft rows lacking verified authority evidence beyond the configured threshold | > 0 = CRITICAL (`RegReportSubmissionsStale`) |

### Travel Rule (`travelrule` module)

| Metric | Description | Alert threshold |
|--------|-------------|----------------|
| `registerwerk_travelrule_failed_messages_recent_total` | Count of `travel_rule_message` rows with status=FAILED in the last 24h | > 0 = CRITICAL (`TravelRuleMessageSendFailures`, TFR Art. 14) |

### Notification delivery (`notification` module)

The only Counter (not a gauge) in this list — no persisted state backs email delivery at all
(not even a fire-and-forget audit event), so `increase()` over a time window is the only
viable query.

| Metric | Description | Alert threshold |
|--------|-------------|----------------|
| `registerwerk_notification_email_send_failures_total{context}` | Email send failures since startup, tagged `generic` (fire-and-forget) or `statement_pdf` (§19 statement delivery) | `increase(...)[1h]` > 5 = WARN (`EmailDeliveryFailuresElevated`) |

### API error rates & latency

| Metric | Description | Alert threshold |
|--------|-------------|----------------|
| `http_server_requests_seconds_count{status=~"5.."}` | 5xx error count | Elevated 5xx rate = WARN (see dashboard's "HTTP Error Rate" panel) |
| `histogram_quantile(0.95, http_server_requests_seconds_bucket)` | 95th percentile latency | > 1s = WARN |

### Database health

| Metric | Description | Alert threshold |
|--------|-------------|----------------|
| `hikaricp_connections_active` / `hikaricp_connections_max` | DB connection pool utilization | > 80% = WARN (`DBConnectionsNearExhaustion`) |
| `pg_up` / `pg_stat_database_*` (via `postgres-exporter`) | Postgres-level stats | See `postgres-exporter`'s own default metric set |

### Availability

| Metric | Description | Alert threshold |
|--------|-------------|----------------|
| `up{job="registerwerk-backend"}` | Whether Prometheus can scrape the backend at all | 0 for 1m = CRITICAL (`BackendDown`) |

### Blockchain wallet balance

The deployer wallet must hold sufficient native tokens (ETH, MATIC, etc.) to pay for gas. This is
not yet exposed as a Prometheus metric — check via the admin API in the meantime:

```bash
curl http://localhost:8080/api/v1/admin/wallet/balances \
  -H "Authorization: Bearer $OPERATOR_JWT"
```

## Grafana dashboards

The monitoring directory includes a pre-built dashboard (`monitoring/grafana/dashboards/registerwerk-overview.json`)
covering audit chain status, chain drift, open sanctions hits, indexer lag, API latency/errors,
JVM memory, and a dedicated row of panels for every metric in this page's "Key metrics" tables
above — provisioned automatically via `monitoring/grafana/dashboards` and
`monitoring/grafana/datasources`, no manual import needed.

## Alertmanager configuration

Edit `monitoring/alertmanager.yml` to configure notification channels:

```yaml
receivers:
  - name: 'ops-team'
    email_configs:
      - to: 'ops@yourregistry.example.com'
        from: 'alerts@yourregistry.example.com'
        smarthost: 'smtp.example.com:587'
    pagerduty_configs:
      - routing_key: 'YOUR_PAGERDUTY_KEY'
        severity: '{{ .CommonLabels.severity }}'

route:
  receiver: ops-team
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
```

Alertmanager itself does not interpolate `${VAR}`-style placeholders in its YAML config — set
real values directly in `monitoring/alertmanager.yml` (or template it via your own secrets
tooling before mounting it) rather than relying on shell-style defaults, which are never
substituted.

## Audit log

All state changes are recorded in `audit_event`:

```sql
SELECT event_type, actor_id, occurred_at, payload
FROM audit_event
WHERE occurred_at > now() - interval '1 hour'
ORDER BY occurred_at DESC;
```

API access:

```
GET /api/v1/audit/events?eventType=ASSET_DEPLOYED&from=2026-01-01T00:00:00Z
```

## Indexer monitor

`IndexerMonitorService` runs every 5 minutes, refreshes the
`registerwerk_indexer_last_sync_timestamp_seconds` gauge for every indexer, and publishes
`INDEXER_STALE` audit events when an indexer has not synced for 2+ hours.
