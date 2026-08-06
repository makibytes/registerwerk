---
title: SLO-/SLI-Katalog
description: Service-Level-Ziele und -Indikatoren für das Register, die Fehlerbudget-Richtlinie und die zu ihrer Berichterstattung verwendeten Prometheus-Abfragen.
---

# SLO-/SLI-Katalog

**Service:** Registerwerk eWpG Registry  
**Rechtsgrundlage für die Verfügbarkeits-SLO:** eWpRV §6 (Registrierungsvoraussetzungen — Verfügbarkeit)  
**Review-Rhythmus:** Vierteljährlich mit Vorstand + zuständigen Behörden

---

## SLIs und SLOs

| SLI | Messung | SLO | Fehlerbudget (30 Tage) |
|---|---|---|---|
| **Verfügbarkeit** | HTTP-5xx-Rate auf `/api/v1/**` | ≥99,5 % | 3,6 h/Monat |
| **Latenz (Lesen)** | p95 der GET-Endpunkte | ≤200 ms | — |
| **Latenz (Schreiben)** | p95 von POST/PUT/PATCH | ≤1 000 ms | — |
| **Latenz (Bereitstellung)** | p95 des Asset-Bereitstellungsablaufs | ≤30 s | — |
| **Integrität der Audit-Chain** | `AuditChainVerificationService.valid` | 100 % – keine Brüche | 0 Brüche |
| **Indexer-Frische** | Zeit seit der letzten Synchronisierung < 30 min | ≥99,9 % der Zeit | 43 min/Monat |
| **Chain-Drift-Erkennung** | Kein CRITICAL-Drift länger als 15 Min. offen | 100 % | 0 unentdeckt |
| **Sanktionsprüfung** | Alle Treffer innerhalb von 4 Stunden überprüft | 100 % während der Geschäftszeiten | — |
| **KYC Ablauf** | KYC-Übergänge innerhalb von 24 Stunden nach Ablauf | 100 % | — |

---

## Fehlerbudgetrichtlinie

Wenn das Fehlerbudget für **Verfügbarkeit** in einem Monat zu mehr als 50 % verbraucht wird:
1. Alle nicht kritischen Versionen einfrieren
2. Vorfall mit Priorität 1 gemeldet; Engineering-Standby erforderlich
3. Benachrichtigen Sie die zuständige Behörde, wenn der Trend über die letzten 14 Tage hinaus anhält.

Wenn die **Integrität der Audit-Chain** bricht:
1. Unmittelbarer DORA-CRITICAL-Vorfall
2. Registerbetrieb ausgesetzt, bis die Chain erneut verifiziert ist
3. BaFin/CSSF/AMF/FMA innerhalb von 4h benachrichtigt

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

## SLO-Berichterstellung

Über `regreport_submission` ist weder ein automatischer monatlicher SLO-Bericht noch eine behördliche
Einreichung implementiert. Betreiber müssen SLO-Nachweise im Rahmen eines extern genehmigten
Resilienz- und Berichtsverfahrens definieren, erzeugen, prüfen, aufbewahren und verteilen.
