---
title: Catalogo SLO / SLI
description: Obiettivi e indicatori di livello di servizio per il registro, la policy di error budget e le query Prometheus usate per rendicontarli.
---

# Catalogo SLO / SLI { #slo-sli-catalogue }

**Servizio:** Registro eWpG Registerwerk  
**Base giuridica per l'SLO di disponibilità:** eWpRV §6 (Registrierungsvoraussetzungen — Verfügbarkeit)  
**Cadenza di revisione:** Trimestrale, con il consiglio di amministrazione e le autorità competenti

---

## SLI e SLO { #slis-and-slos }

| SLI | Misurazione | SLO | Error budget (30 giorni) |
|---|---|---|---|
| **Disponibilità** | Tasso di HTTP 5xx su `/api/v1/**` | ≥99,5% | 3,6 h/mese |
| **Latenza (lettura)** | p95 degli endpoint GET | ≤200 ms | — |
| **Latenza (scrittura)** | p95 di POST/PUT/PATCH | ≤1 000 ms | — |
| **Latenza (deployment)** | p95 del flusso di deployment degli asset | ≤30 s | — |
| **Integrità della catena di controllo** | `AuditChainVerificationService.valid` | 100% — nessuna interruzione mai | 0 interruzioni |
| **Aggiornamento dell'indicizzatore** | Tempo dall'ultima sincronizzazione < 30 min | ≥99,9% del tempo | 43 min/mese |
| **Rilevamento della deriva della catena** | Nessuna deriva CRITICAL aperta >15 min | 100% | 0 non rilevate |
| **Screening sanzioni** | Tutti i riscontri esaminati entro 4 h | 100% durante l'orario lavorativo | — |
| **Scadenza KYC** | Transizioni KYC entro 24 h dalla scadenza | 100% | — |

---

## Criterio di error budget { #error-budget-policy }

Quando l'error budget per la **disponibilità** è consumato per oltre il 50% in un mese:
1. Congelare tutte le release non critiche
2. Viene dichiarato un incidente di priorità 1; è richiesto lo standby del team tecnico
3. Informare l'autorità competente se la tendenza persiste oltre i 14 giorni

Quando l'**integrità della catena di controllo** si interrompe:
1. Incidente DORA CRITICAL immediato
2. Le operazioni del registro vengono sospese finché la catena non è riverificata
3. BaFin/CSSF/AMF/FMA vengono notificate entro 4h

---

## Query Prometheus (Grafana) { #prometheus-queries-grafana }

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

## Rendicontazione SLO { #slo-reporting }

Nessun report mensile SLO automatico né alcuna comunicazione formale all'autorità è implementato tramite
`regreport_submission`. Gli operatori devono definire, generare, rivedere, conservare e distribuire la
documentazione SLO secondo una procedura di resilienza e rendicontazione approvata esternamente.
