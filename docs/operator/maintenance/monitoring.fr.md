---
title: Surveillance
---

# Surveillance

Le registre est livré avec une pile de surveillance Prometheus + Grafana. Cette page décrit ce qu'il faut surveiller, quelles mesures sont importantes et comment configurer les alertes.

## Démarrage de la pile de surveillance

La pile principale (`docker compose up --build` à partir de la racine du dépôt) doit déjà être exécutée en premier —
la pile de surveillance rejoint son réseau `registerwerk_default` pour atteindre `backend`/`kong`.

```bash
docker compose -f monitoring/docker-compose.yml up -d
```

Cela commence :
- **Prometheus** sur `http://localhost:49090`
- **Grafana** sur `http://localhost:43000` (connexion : `admin` / `$GRAFANA_ADMIN_PASSWORD`, voir `.env.example`)
- **Alertmanager** sur `http://localhost:49093`
- **postgres-exporter** et **node-exporter** (alimentent les panneaux de santé de la base de données/métriques de l'hôte)

## Points de terminaison de santé

| Point de terminaison | Objectif |
|---|---|
| `GET /actuator/health` | État de santé global (UP/DOWN) |
| `GET /actuator/health/liveness` | Processus vivant — utilisé par la sonde d'activité du chart Helm |
| `GET /actuator/health/readiness` | Connexions DB + chaîne prêtes — utilisées par la sonde de préparation |
| `GET /actuator/prometheus` | Récupération des métriques Prometheus (non authentifiées — voir `SecurityConfig`) |

## Métriques clés à surveiller

Ce sont les métriques Micrometer réelles enregistrées dans le backend (chacune sous le module qui la possède) — chacune a une règle correspondante dans `monitoring/alerts/registerwerk.yml` et un panneau dans `monitoring/grafana/dashboards/registerwerk-overview.json`.

### Intégrité de la chaîne d'audit (module `audit`)

| Métrique | Descriptif | Seuil d'alerte |
|--------|-------------|----------------|
| `registerwerk_audit_chain_valid` | 1 si la dernière vérification de la chaîne de hachage a réussi, 0 si elle est cassée | 0 = CRITICAL (`AuditChainBroken`) |
| `registerwerk_audit_signing_key_age_seconds` | Secondes depuis la création/la rotation de la clé de signature Ed25519 de la chaîne d'audit active | > 90 jours = WARN (`AuditSigningKeyAgeWarning`) |

### Santé de l'indexeur (module `indexer`)

| Métrique | Descriptif | Seuil d'alerte |
|--------|-------------|----------------|
| `registerwerk_indexer_last_sync_timestamp_seconds{chain_config_id,indexer_type}` | Époque Unix de la dernière synchronisation réussie de chaque indexeur ; 0 si jamais synchronisé | `time() - metric` > 30 min = WARN, > 2h = CRITICAL |
| `registerwerk_chain_drift_open_total` | Nombre de lignes OPEN `chain_drift_event` actuellement (divergence entre le solde du registre et le solde on-chain, eWpG §16) | > 0 = CRITICAL (`ChainDriftDetected`) |

### Sanctions/contrôle (module `screening`)

| Métrique | Descriptif | Seuil d'alerte |
|--------|-------------|----------------|
| `registerwerk_sanctions_oldest_open_hit_seconds` | Âge en secondes de la plus ancienne alerte sanctions/PEP ouverte et non résolue ; 0 si aucune n'est ouverte | > 4h = CRITICAL (`SanctionsHitOpenTooLong`, GwG §10) |
| `registerwerk_screening_errors_recent_total` | Nombre de lignes `ScreeningRun` avec le statut = ERROR au cours des dernières 24 heures – échecs d'appel au fournisseur, distincts de la jauge d'âge des alertes ci-dessus | > 5 = CRITICAL (`ScreeningErrorsElevated`) — `ScreeningGateImpl` échoue en mode fermé (fail closed), bloquant silencieusement les approbations de nouvelles entités |
| `registerwerk_screening_periodic_refresh_last_failures` | Entités dont le réexamen a échoué lors de l'actualisation périodique quotidienne la plus récente | > 0 = WARN (`ScreeningPeriodicRefreshFailures`) |

### Rapprochement des jetons confidentiels (module `blockchain`)

Aucune table dédiée n'enregistre les incompatibilités ici (contrairement à `chain_drift_event`) — les deux jauges sont un instantané en mémoire
de l'exécution `ConfidentialBalanceReconciliationService.reconcile()` la plus récente,
pas une requête de base de données en direct.

| Métrique | Descriptif | Seuil d'alerte |
|--------|-------------|----------------|
| `registerwerk_confidential_reconciliation_mismatch_total` | Somme du nombre de non-concordances le plus récent sur tous les actifs confidentiels | > 0 = CRITICAL (`ConfidentialReconciliationMismatchDetected`) |
| `registerwerk_confidential_reconciliation_last_run_timestamp_seconds` | Époque Unix de l'exécution de rapprochement la plus récente (n'importe quel actif) | `time() - metric` > 1h = WARN (`ConfidentialReconciliationStale`) — détecte un relais Zama mal configuré interrompant silencieusement le balayage |

### État du nœud RPC (module `blockchain`)

| Métrique | Descriptif | Seuil d'alerte |
|--------|-------------|----------------|
| `registerwerk_rpc_nodes_unhealthy_total` | Nombre de lignes `RpcNode` actuellement marquées comme non saines | > 0 pendant 2 min = WARN (`RpcNodesUnhealthy`) |

### Réconciliation en chaîne d'identité d'organisation (module `orgidentity`)

Même mise en garde « pas de table dédiée » que la réconciliation confidentielle ci-dessus — ce sont des jauges
réinitialisées puis recomptées par balayage (chaque ligne active est réexaminée chaque cycle de 5 minutes, donc
cela reflète avec précision "la dérive actuellement ouverte" sans avoir besoin d'un nouvel état persistant).

| Métrique | Descriptif | Seuil d'alerte |
|--------|-------------|----------------|
| `registerwerk_org_chain_drift_open_total` | Inscriptions d'organisations/portefeuilles de membres en désaccord avec l'`OrgRegistry` on-chain lors du balayage le plus récent | > 0 = CRITICAL (`OrgChainDriftDetected`) |
| `registerwerk_permission_chain_drift_open_total` | Octrois d'autorisations en désaccord avec le `PermissionRegistry` on-chain, incl. retournements de restriction de rôle | > 0 = CRITICAL (`PermissionChainDriftDetected`) |

### Délais de reporting DORA (module `dora`)

| Métrique | Descriptif | Seuil d'alerte |
|--------|-------------|----------------|
| `registerwerk_dora_deadline_breaches{breach_type}` | Nombre de retards par type de violation (`classification`/`initial_report`/`final_report`/`resilience_test`), interrogé en direct via les mêmes appels de référentiel. `checkDeadlines()` s'exécute déjà quotidiennement | `sum(...)` > 0 = CRITICAL (`DoraDeadlineBreach`, Art. 19) |

### Obsolescence du rapport réglementaire (module `regreporting`)

| Métrique | Descriptif | Seuil d'alerte |
|--------|-------------|----------------|
| `registerwerk_regreport_stale_submissions_total` | Nombre de lignes brouillon `TRANSPORTED_UNVERIFIED` dépourvues de preuves d'autorité vérifiées au-delà du seuil configuré | > 0 = CRITICAL (`RegReportSubmissionsStale`) |

### Travel Rule (module `travelrule`)

| Métrique | Descriptif | Seuil d'alerte |
|--------|-------------|----------------|
| `registerwerk_travelrule_failed_messages_recent_total` | Nombre de lignes `travel_rule_message` avec le statut = FAILED au cours des dernières 24 heures | > 0 = CRITICAL (`TravelRuleMessageSendFailures`, TFR Art. 14) |

### Envoi de notifications (module `notification`)

Le seul compteur (pas une jauge) de cette liste — aucun état persistant ne soutient l'envoi d'e-mails du tout
(pas même un événement d'audit déclenché et oublié), donc `increase()` sur une fenêtre de temps est la seule requête
viable.

| Métrique | Descriptif | Seuil d'alerte |
|--------|-------------|----------------|
| `registerwerk_notification_email_send_failures_total{context}` | Échecs d'envoi d'e-mails depuis le démarrage, étiquetés `generic` (tirer et oublier) ou `statement_pdf` (livraison de relevé §19) | `increase(...)[1h]` > 5 = WARN (`EmailDeliveryFailuresElevated`) |

### Taux d'erreur et latence API

| Métrique | Descriptif | Seuil d'alerte |
|--------|-------------|----------------|
| `http_server_requests_seconds_count{status=~"5.."}` | Nombre d'erreurs 5xx | Taux 5xx élevé = WARN (voir le panneau « Taux d'erreur HTTP » du tableau de bord) |
| `histogram_quantile(0.95, http_server_requests_seconds_bucket)` | Latence du 95e percentile | > 1s = WARN |

### État de la base de données

| Métrique | Descriptif | Seuil d'alerte |
|--------|-------------|----------------|
| `hikaricp_connections_active` / `hikaricp_connections_max` | Utilisation du pool de connexions à la base de données | > 80 % = WARN (`DBConnectionsNearExhaustion`) |
| `pg_up` / `pg_stat_database_*` (via `postgres-exporter`) | Statistiques au niveau Postgres | Voir l'ensemble de métriques par défaut de `postgres-exporter` |

### Disponibilité

| Métrique | Descriptif | Seuil d'alerte |
|--------|-------------|----------------|
| `up{job="registerwerk-backend"}` | Indique si Prometheus parvient à scraper le backend | 0 pendant 1 min = CRITICAL (`BackendDown`) |

### Solde du portefeuille Blockchain

Le portefeuille du déployeur doit contenir suffisamment de jetons natifs (ETH, MATIC, etc.) pour payer le gas. Ceci n'est pas encore exposé comme métrique Prometheus — vérifiez via l'API d'administration en attendant :

```bash
curl http://localhost:48080/api/v1/admin/wallet/balances \
  -H "Authorization: Bearer $OPERATOR_JWT"
```

## Tableaux de bord Grafana

Le répertoire de surveillance comprend un tableau de bord prédéfini (`monitoring/grafana/dashboards/registerwerk-overview.json`)
couvrant l'état de la chaîne d'audit, la dérive de la chaîne, les sanctions ouvertes, le décalage de l'indexeur, la latence/erreurs API, la mémoire
JVM et une rangée dédiée de panneaux pour chaque métrique des tableaux « Métriques clés » de cette page
ci-dessus — provisionnés automatiquement via `monitoring/grafana/dashboards` et
`monitoring/grafana/datasources`, aucune importation manuelle nécessaire.

## Configuration du gestionnaire d'alertes

Modifiez `monitoring/alertmanager.yml` pour configurer les canaux de notification :

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

Alertmanager lui-même n'interpole pas les espaces réservés de style `${VAR}` dans sa configuration YAML — définissez les valeurs réelles
directement dans `monitoring/alertmanager.yml` (ou modélisez-les via vos propres outils secrets
avant de le monter) plutôt que de vous fier aux valeurs par défaut de style shell, qui ne sont jamais substituées.

## Journal d'audit

Tous les changements d'état sont enregistrés dans `audit_event` :

```sql
SELECT event_type, actor_id, occurred_at, payload
FROM audit_event
WHERE occurred_at > now() - interval '1 hour'
ORDER BY occurred_at DESC;
```

Accès API :

```
GET /api/v1/audit/events?eventType=ASSET_DEPLOYED&from=2026-01-01T00:00:00Z
```

## Moniteur d'indexeur

`IndexerMonitorService` s'exécute toutes les 5 minutes, actualise la jauge
`registerwerk_indexer_last_sync_timestamp_seconds` pour chaque indexeur et publie les événements d'audit
`INDEXER_STALE` lorsqu'un indexeur n'a pas été synchronisé pendant plus de 2 heures.
