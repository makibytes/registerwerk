---
id: monitoring
title: Monitoring
sidebar_label: Monitoring
---

# Monitoring

The registry ships with a Prometheus + Grafana monitoring stack. This page describes what to monitor, which metrics matter, and how to configure alerting.

## Starting the monitoring stack

```bash
docker compose -f monitoring/docker-compose.yml up -d
```

This starts:
- **Prometheus** at `http://localhost:9090`
- **Grafana** at `http://localhost:3000` (default login: `admin` / `changeme`)
- **Alertmanager** at `http://localhost:9093`

## Key metrics to monitor

### Indexer health

| Metric | Description | Alert threshold |
|--------|-------------|----------------|
| `indexer_lag_blocks{chain}` | Blocks behind chain head | >20 blocks = WARN, >100 = CRIT |
| `indexer_last_sync_timestamp{chain}` | Unix timestamp of last successful sync | Stale >10 min = WARN |
| `graph_node_up` | Whether graph-node is reachable | 0 = CRIT |

Check current indexer status:

```bash
curl -s http://localhost:8080/actuator/metrics/indexer.lag.blocks \
  -H "Authorization: Bearer $OPERATOR_JWT" | jq .
```

### Chain sync status

For each configured chain, monitor:

```bash
# Via graph-node status API
curl -s http://localhost:8030/graphql \
  -d '{"query":"{indexingStatuses{subgraph synced health chains{network latestBlock{number} chainHeadBlock{number}}}}"}' \
  | jq '.data.indexingStatuses[]'
```

### API error rates

| Metric | Description | Alert threshold |
|--------|-------------|----------------|
| `http_server_requests_seconds_count{status="5xx"}` | 5xx error count | >10/min = WARN |
| `http_server_requests_seconds{quantile="0.99"}` | 99th percentile latency | >2s = WARN |
| `http_server_requests_seconds{quantile="0.95"}` | 95th percentile latency | >1s = WARN |

### Database health

| Metric | Description | Alert threshold |
|--------|-------------|----------------|
| `hikaricp_connections_active` | Active DB connections | >8 of pool = WARN |
| `hikaricp_connections_timeout_total` | Connection timeout count | >0 = WARN |
| `pg_database_size_bytes` | PostgreSQL database size | >80% disk = WARN |

### Blockchain wallet balance

The deployer wallet must hold sufficient native tokens (ETH, MATIC, etc.) to pay for gas. Monitor wallet balances:

```bash
curl http://localhost:8080/api/v1/admin/wallet/balances \
  -H "Authorization: Bearer $OPERATOR_JWT"
```

Alert when any chain's deployer balance falls below the configured threshold (default: 0.1 ETH / 0.5 MATIC).

## Grafana dashboards

The monitoring directory includes pre-built dashboards:

| Dashboard | Description |
|-----------|-------------|
| `Registry Overview` | Summary of all chains, indexer status, API health |
| `Indexer Details` | Per-chain indexer lag, sync rate, error counts |
| `API Performance` | Request rates, latencies, error rates per endpoint |
| `Database Health` | Connection pool, query durations, table sizes |
| `Blockchain Wallets` | Deployer wallet balances per chain |

Import dashboards via **Grafana → Dashboards → Import → Upload JSON**.

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

## Health endpoint summary

The Spring Boot actuator provides a unified health endpoint:

```bash
curl http://localhost:8080/actuator/health | jq .
```

Component health checks included:
- `db` — PostgreSQL connectivity
- `indexer` — indexer lag check (all chains)
- `blockchain` — RPC connectivity check (all chains)
- `mail` — SMTP connectivity

A `DOWN` status on any component causes the overall health to return `DOWN` with HTTP 503, which should trigger load balancer failover and alerting.
sidebar_position: 1
---

# Monitoring

## Health endpoints

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Overall health (UP/DOWN) |
| `GET /actuator/health/liveness` | Process alive |
| `GET /actuator/health/readiness` | DB + chain connections ready |
| `GET /actuator/prometheus` | Prometheus metrics scrape |

## Key metrics

| Metric | Alert threshold |
|---|---|
| `indexer_state.last_synced_at` | > 2 hours → INDEXER_STALE audit event |
| `indexer_state.consecutive_errors` | > 5 → investigate |
| `token_transfer` insert rate | Sudden drop → indexer down |
| `audit_event` partition size | > 10M rows/month → consider archival |

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
GET /api/v1/audit?eventType=ASSET_DEPLOYED&from=2026-01-01
```

## Indexer monitor

`IndexerMonitorService` runs every 5 minutes and publishes `INDEXER_STALE` events when an indexer has not synced for 2+ hours. Configure an alert on this event type in your monitoring stack.

## Recommended dashboards

Set up Prometheus + Grafana with alerts on:
- Backend JVM heap usage
- DB connection pool saturation  
- Kong request error rate (5xx)
- graph-node sync lag
