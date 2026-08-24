---
title: Catalogue SLO / SLI
description: Objectifs et indicateurs de niveau de service pour le registre, la politique de budget d'erreur, et les requêtes Prometheus utilisées pour en rendre compte.
---

# Catalogue SLO / SLI

**Service :** Registerwerk eWpG Registry
**Base légale pour la disponibilité SLO :** eWpRV §6 (Registrierungsvoraussetzungen — Verfügbarkeit)
**Fréquence de révision :** Trimestrielle, avec le conseil d'administration et les autorités compétentes

---

## SLI et SLO

| SLI | Mesure | SLO | Budget d'erreur (30 jours) |
|---|---|---|---|
| **Disponibilité** | Taux HTTP 5xx sur `/api/v1/**` | ≥ 99,5 % | 3,6 h/mois |
| **Latence (lecture)** | p95 des points de terminaison GET | ≤200 ms | — |
| **Latence (écriture)** | p95 de POST/PUT/PATCH | ≤1 000 ms | — |
| **Latence (déploiement)** | p95 du flux de déploiement des actifs | ≤30 s | — |
| **Intégrité de la chaîne d'audit** | `AuditChainVerificationService.valid` | 100 % — jamais de rupture | 0 rupture |
| **Fraîcheur de l'indexeur** | Temps depuis la dernière synchronisation < 30 min | ≥ 99,9 % du temps | 43 min/mois |
| **Détection de dérive de chaîne** | Aucune dérive CRITICAL ouverte >15 min | 100 % | 0 non détectée |
| **Filtrage des sanctions** | Tous les résultats examinés sous 4 h | 100 % pendant les heures ouvrées | — |
| **Expiration KYC** | Transitions KYC dans les 24 h suivant l'expiration | 100 % | — |

---

## Politique de budget d'erreur

Lorsque le budget d'erreur pour la **disponibilité** est consommé à plus de 50 % sur un mois :
1. Gel de toutes les mises en production non critiques
2. Incident de priorité 1 déclaré ; astreinte de l'équipe d'ingénierie requise
3. Notification de l'autorité compétente si la tendance persiste au-delà de 14 jours

Lorsque **l'intégrité de la chaîne d'audit** est rompue :
1. Incident DORA CRITICAL immédiat
2. Suspension des opérations du registre jusqu'à revérification de la chaîne
3. Notification de la BaFin/CSSF/AMF/FMA sous 4 h

---

## Requêtes Prometheus (Grafana)

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

## Reporting SLO

Aucun rapport SLO mensuel automatique ni dépôt auprès d'une autorité n'est mis en œuvre via
`regreport_submission`. Les opérateurs doivent définir, générer, examiner, conserver et distribuer les
preuves SLO dans le cadre d'une procédure de résilience et de reporting approuvée en externe.
