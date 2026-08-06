---
title: Überwachung
---

# Überwachung

Das Register wird mit einem Prometheus-+-Grafana-Monitoring-Stack ausgeliefert. Diese Seite beschreibt, was zu überwachen ist, welche Metriken zählen und wie Alerting konfiguriert wird.

## Den Monitoring-Stack starten

Der Haupt-Stack (`docker compose up --build` im Repository-Root) muss bereits laufen —
der Monitoring-Stack tritt dessen Netzwerk `registerwerk_default` bei, um `backend`/`kong` zu erreichen.

```bash
docker compose -f monitoring/docker-compose.yml up -d
```

Das startet:
- **Prometheus** unter `http://localhost:9090`
- **Grafana** unter `http://localhost:3000` (Login: `admin` / `$GRAFANA_ADMIN_PASSWORD`, siehe `.env.example`)
- **Alertmanager** unter `http://localhost:9093`
- **postgres-exporter** und **node-exporter** (speisen die Panels für Datenbankzustand/Host-Metriken)

## Health-Endpunkte

| Endpunkt | Zweck |
|---|---|
| `GET /actuator/health` | Gesamtzustand (UP/DOWN) |
| `GET /actuator/health/liveness` | Prozess lebt – wird von der Liveness-Probe des Helm-Charts verwendet |
| `GET /actuator/health/readiness` | DB- und Chain-Verbindungen bereit – wird von der Readiness-Probe verwendet |
| `GET /actuator/prometheus` | Prometheus-Metrik-Scrape (nicht authentifiziert – siehe `SecurityConfig`) |

## Wichtige zu überwachende Metriken

Dies sind die tatsächlichen Micrometer-Metriken, die im Backend registriert sind (jeweils unter dem Modul, dem
sie gehören) – jede hat eine entsprechende Regel in `monitoring/alerts/registerwerk.yml` und ein Panel
in `monitoring/grafana/dashboards/registerwerk-overview.json`.

### Integrität der Audit-Chain (Modul `audit`)

| Metrik | Beschreibung | Alarmschwelle |
|--------|-------------|----------------|
| `registerwerk_audit_chain_valid` | 1, wenn die letzte Hash-Chain-Verifizierung erfolgreich war, 0 bei Bruch | 0 = CRITICAL (`AuditChainBroken`) |
| `registerwerk_audit_signing_key_age_seconds` | Sekunden seit Erstellung/Rotation des aktiven Ed25519-Signaturschlüssels der Audit-Chain | > 90 Tage = WARN (`AuditSigningKeyAgeWarning`) |

### Indexer-Zustand (Modul `indexer`)

| Metrik | Beschreibung | Alarmschwelle |
|--------|-------------|----------------|
| `registerwerk_indexer_last_sync_timestamp_seconds{chain_config_id,indexer_type}` | Unix-Epoch der letzten erfolgreichen Synchronisierung jedes Indexers; 0, wenn nie synchronisiert | `time() - metric` > 30 Min. = WARN, > 2 Std. = CRITICAL |
| `registerwerk_chain_drift_open_total` | Anzahl aktuell OPEN `chain_drift_event`-Zeilen (Divergenz Register vs. On-Chain-Saldo, eWpG §16) | > 0 = CRITICAL (`ChainDriftDetected`) |

### Sanktionen/Screening (Modul `screening`)

| Metrik | Beschreibung | Alarmschwelle |
|--------|-------------|----------------|
| `registerwerk_sanctions_oldest_open_hit_seconds` | Alter in Sekunden des am längsten ungelösten offenen Sanktions-/PEP-Treffers; 0, wenn keiner offen ist | > 4 Std. = CRITICAL (`SanctionsHitOpenTooLong`, GwG §10) |
| `registerwerk_screening_errors_recent_total` | Anzahl der `ScreeningRun`-Zeilen mit Status=ERROR in den letzten 24 Std. — Anbieteraufruf-Fehler, unterschieden vom Treffer-Alters-Gauge oben | > 5 = CRITICAL (`ScreeningErrorsElevated`) — `ScreeningGateImpl` schlägt hierbei fail-closed und blockiert stillschweigend Genehmigungen neuer Entitäten |
| `registerwerk_screening_periodic_refresh_last_failures` | Entitäten, deren erneutes Screening im letzten täglichen periodischen Refresh fehlgeschlagen ist | > 0 = WARN (`ScreeningPeriodicRefreshFailures`) |

### Vertraulicher-Token-Abgleich (Modul `blockchain`)

Anders als bei `chain_drift_event` zeichnet hier keine dedizierte Tabelle Abweichungen auf — beide Gauges sind ein
In-Memory-Snapshot des letzten Laufs von `ConfidentialBalanceReconciliationService.reconcile()`,
keine Live-DB-Abfrage.

| Metrik | Beschreibung | Alarmschwelle |
|--------|-------------|----------------|
| `registerwerk_confidential_reconciliation_mismatch_total` | Summe der jüngsten Abweichungsanzahl über alle vertraulichen Assets | > 0 = CRITICAL (`ConfidentialReconciliationMismatchDetected`) |
| `registerwerk_confidential_reconciliation_last_run_timestamp_seconds` | Unix-Epoch des jüngsten Abgleichslaufs (beliebiges Asset) | `time() - metric` > 1 Std. = WARN (`ConfidentialReconciliationStale`) — erfasst einen falsch konfigurierten Zama-Relayer, der den Sweep stillschweigend stoppt |

### RPC-Node-Zustand (Modul `blockchain`)

| Metrik | Beschreibung | Alarmschwelle |
|--------|-------------|----------------|
| `registerwerk_rpc_nodes_unhealthy_total` | Anzahl der `RpcNode`-Zeilen, die aktuell als unhealthy markiert sind | > 0 für 2 Min. = WARN (`RpcNodesUnhealthy`) |

### OrgIdentity-On-Chain-Abgleich (Modul `orgidentity`)

Gleicher Vorbehalt „keine dedizierte Tabelle“ wie beim vertraulichen Abgleich oben — das sind
Reset-dann-Neuzählen-je-Sweep-Gauges (jede aktive Zeile wird in jedem 5-Minuten-Zyklus neu geprüft, sodass
dies die „aktuell offene Drift“ genau widerspiegelt, ohne dass neuer persistenter Zustand nötig wäre).

| Metrik | Beschreibung | Alarmschwelle |
|--------|-------------|----------------|
| `registerwerk_org_chain_drift_open_total` | Org-Registrierungen/Mitglieder-Wallets, die im jüngsten Sweep vom On-Chain-`OrgRegistry` abweichen | > 0 = CRITICAL (`OrgChainDriftDetected`) |
| `registerwerk_permission_chain_drift_open_total` | Berechtigungsvergaben, die vom On-Chain-`PermissionRegistry` abweichen, einschl. Umkehrungen der Rollenbeschränkung | > 0 = CRITICAL (`PermissionChainDriftDetected`) |

### DORA-Meldefristen (Modul `dora`)

| Metrik | Beschreibung | Alarmschwelle |
|--------|-------------|----------------|
| `registerwerk_dora_deadline_breaches{breach_type}` | Überfällige Anzahl je Verstoßtyp (`classification`/`initial_report`/`final_report`/`resilience_test`), live abgefragt über dieselben Repository-Aufrufe, die `checkDeadlines()` bereits täglich ausführt | `sum(...)` > 0 = CRITICAL (`DoraDeadlineBreach`, Art. 19) |

### Veraltung regulatorischer Meldungen (Modul `regreporting`)

| Metrik | Beschreibung | Alarmschwelle |
|--------|-------------|----------------|
| `registerwerk_regreport_stale_submissions_total` | Anzahl der `TRANSPORTED_UNVERIFIED`-Entwurfszeilen ohne verifizierten Behördennachweis über der konfigurierten Schwelle hinaus | > 0 = CRITICAL (`RegReportSubmissionsStale`) |

### Travel Rule (Modul `travelrule`)

| Metrik | Beschreibung | Alarmschwelle |
|--------|-------------|----------------|
| `registerwerk_travelrule_failed_messages_recent_total` | Anzahl der `travel_rule_message`-Zeilen mit Status=FAILED in den letzten 24 Std. | > 0 = CRITICAL (`TravelRuleMessageSendFailures`, TFR Art. 14) |

### Zustellung von Benachrichtigungen (Modul `notification`)

Der einzige Counter (kein Gauge) in dieser Liste — kein persistenter Zustand belegt die E-Mail-Zustellung überhaupt
(nicht einmal ein Fire-and-Forget-Audit-Ereignis), daher ist `increase()` über ein Zeitfenster die einzig
mögliche Abfrage.

| Metrik | Beschreibung | Alarmschwelle |
|--------|-------------|----------------|
| `registerwerk_notification_email_send_failures_total{context}` | E-Mail-Versandfehler seit Start, getaggt mit `generic` (Fire-and-Forget) oder `statement_pdf` (§19-Zustellung von Registerauszügen) | `increase(...)[1h]` > 5 = WARN (`EmailDeliveryFailuresElevated`) |

### API-Fehlerraten und Latenz

| Metrik | Beschreibung | Alarmschwelle |
|--------|-------------|----------------|
| `http_server_requests_seconds_count{status=~"5.."}` | Anzahl 5xx-Fehler | Erhöhte 5xx-Rate = WARN (siehe Dashboard-Panel „HTTP Error Rate“) |
| `histogram_quantile(0.95, http_server_requests_seconds_bucket)` | 95.-Perzentil-Latenz | > 1s = WARN |

### Datenbankzustand

| Metrik | Beschreibung | Alarmschwelle |
|--------|-------------|----------------|
| `hikaricp_connections_active` / `hikaricp_connections_max` | Auslastung des DB-Connection-Pools | > 80 % = WARN (`DBConnectionsNearExhaustion`) |
| `pg_up` / `pg_stat_database_*` (über `postgres-exporter`) | Statistiken auf Postgres-Ebene | Siehe den eigenen Standard-Metrikensatz von `postgres-exporter` |

### Verfügbarkeit

| Metrik | Beschreibung | Alarmschwelle |
|--------|-------------|----------------|
| `up{job="registerwerk-backend"}` | Ob Prometheus das Backend überhaupt scrapen kann | 0 für 1 Min. = CRITICAL (`BackendDown`) |

### Blockchain-Wallet-Guthaben

Die Deployer-Wallet muss über ausreichend native Token (ETH, MATIC usw.) verfügen, um Gas zu bezahlen. Das ist
noch nicht als Prometheus-Metrik verfügbar — prüfen Sie es in der Zwischenzeit über die Admin-API:

```bash
curl http://localhost:8080/api/v1/admin/wallet/balances \
  -H "Authorization: Bearer $OPERATOR_JWT"
```

## Grafana-Dashboards

Das Monitoring-Verzeichnis enthält ein vorgefertigtes Dashboard (`monitoring/grafana/dashboards/registerwerk-overview.json`),
das Audit-Chain-Status, Chain-Drift, offene Sanktionstreffer, Indexer-Lag, API-Latenz/-Fehler,
JVM-Speicher sowie eine eigene Panel-Reihe für jede Metrik in den „Wichtige Metriken“-Tabellen dieser Seite
abdeckt — automatisch bereitgestellt über `monitoring/grafana/dashboards` und
`monitoring/grafana/datasources`, kein manueller Import nötig.

## Alertmanager-Konfiguration

Bearbeiten Sie `monitoring/alertmanager.yml`, um Benachrichtigungskanäle zu konfigurieren:

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

Alertmanager selbst interpoliert `${VAR}`-artige Platzhalter in seiner YAML-Konfiguration nicht — setzen Sie
echte Werte direkt in `monitoring/alertmanager.yml` (oder erzeugen Sie die Datei per Template über Ihr eigenes
Secrets-Tooling, bevor Sie sie mounten), statt sich auf shell-artige Defaults zu verlassen, die niemals
substituiert werden.

## Audit-Log

Alle Zustandsänderungen werden in `audit_event` protokolliert:

```sql
SELECT event_type, actor_id, occurred_at, payload
FROM audit_event
WHERE occurred_at > now() - interval '1 hour'
ORDER BY occurred_at DESC;
```

API-Zugriff:

```
GET /api/v1/audit/events?eventType=ASSET_DEPLOYED&from=2026-01-01T00:00:00Z
```

## Indexer-Monitor

`IndexerMonitorService` läuft alle 5 Minuten, aktualisiert das Gauge
`registerwerk_indexer_last_sync_timestamp_seconds` für jeden Indexer und veröffentlicht
`INDEXER_STALE`-Audit-Ereignisse, wenn ein Indexer seit 2 oder mehr Stunden nicht synchronisiert hat.
