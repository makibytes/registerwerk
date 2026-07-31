---
title: SLO / SLI catalogue
description: Service-level objectives and indicators for the registry, the error-budget policy, and the Prometheus queries used to report them.
---

# SLO / SLI Catalogue

**Service:** Registerwerk eWpG Registry  
**Legal basis for availability SLO:** eWpRV §6 (Registrierungsvoraussetzungen — Verfügbarkeit)  
**Review cadence:** Quarterly with board + competent authorities

---

## SLIs and SLOs

| SLI | Measurement | SLO | Error Budget (30-day) |
|---|---|---|---|
| **Availability** | HTTP 5xx rate on `/api/v1/**` | ≥99.5% | 3.6 h/month |
| **Latency (read)** | p95 of GET endpoints | ≤200 ms | — |
| **Latency (write)** | p95 of POST/PUT/PATCH | ≤1 000 ms | — |
| **Latency (deploy)** | p95 of asset deployment flow | ≤30 s | — |
| **Audit chain integrity** | `AuditChainVerificationService.valid` | 100% — no breaks ever | 0 breaks |
| **Indexer freshness** | Time since last sync < 30 min | ≥99.9% of the time | 43 min/month |
| **Chain drift detection** | No CRITICAL drift open >15 min | 100% | 0 undetected |
| **Sanctions screening** | All hits reviewed within 4 h | 100% during business hours | — |
| **KYC expiry** | KYC transitions within 24 h of expiry | 100% | — |

---

## Error Budget Policy

When error budget for **availability** is >50% consumed in a month:
1. Freeze all non-critical releases
2. Priority-1 incident declared; engineering standby required
3. Notify competent authority if trend persists past 14 days

When **audit chain integrity** breaks:
1. Immediate DORA CRITICAL incident
2. Registry operations suspended until chain re-verified
3. BaFin/CSSF/AMF/FMA notified within 4h

---

## Prometheus Queries (Grafana)

```promql
# 30-day availability
1 - (
  sum(rate(http_server_requests_seconds_count{status=~"5.."}[30d]))
  /
  sum(rate(http_server_requests_seconds_count[30d]))
)

# p95 read latency
histogram_quantile(0.95,
  rate(http_server_requests_seconds_bucket{method="GET"}[5m])
)

# Indexer lag
time() - registerwerk_indexer_last_sync_timestamp_seconds

# Open drift events
registerwerk_chain_drift_open_total
```

---

## SLO Reporting

No automatic monthly SLO report or authority filing is implemented through
`regreport_submission`. Operators must define, generate, review, retain, and distribute SLO
evidence under an externally approved resilience and reporting procedure.
