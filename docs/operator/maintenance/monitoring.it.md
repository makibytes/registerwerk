---
title: Monitoraggio
---

# Monitoraggio { #monitoring }

Il registro viene fornito con uno stack di monitoraggio Prometheus + Grafana. Questa pagina descrive cosa
monitorare, quali parametri contano e come configurare gli avvisi.

## Avvio dello stack di monitoraggio { #starting-the-monitoring-stack }

Lo stack principale (`docker compose up --build` dalla root del repository) deve essere già in esecuzione per primo —
lo stack di monitoraggio si unisce alla sua rete `registerwerk_default` per raggiungere `backend`/`kong`.

```bash
docker compose -f monitoring/docker-compose.yml up -d
```

Questo inizia:
- **Prometheus** presso `http://localhost:9090`
- **Grafana** presso `http://localhost:3000` (login: `admin` / `$GRAFANA_ADMIN_PASSWORD`, vedere `.env.example`)
- **Alertmanager** presso `http://localhost:9093`
- **postgres-exporter** e **node-exporter** (alimenta i pannelli di integrità del database/metriche host)

## Endpoint di integrità { #health-endpoints }

| Endpoint | Scopo |
|---|---|
| `GET /actuator/health` | Stato generale (UP/DOWN) |
| `GET /actuator/health/liveness` | Processo attivo: utilizzato dalla sonda di attività (liveness probe) del chart Helm |
| `GET /actuator/health/readiness` | Collegamenti DB + catena pronti — utilizzati dalla sonda di disponibilità |
| `GET /actuator/prometheus` | Scraping delle metriche Prometheus (non autenticato — vedere `SecurityConfig`) |

## Metriche chiave da monitorare { #key-metrics-to-monitor }

Queste sono le metriche Micrometer effettive registrate nel backend (ciascuna sotto il modulo che
la possiede) — ognuna ha una regola corrispondente in `monitoring/alerts/registerwerk.yml` e un pannello
in `monitoring/grafana/dashboards/registerwerk-overview.json`.

### Integrità della catena di controllo (modulo `audit`) { #audit-chain-integrity-audit-module }

| Metrica | Descrizione | Soglia di avviso |
|--------|-------------|----------------|
| `registerwerk_audit_chain_valid` | 1 se l'ultima verifica della catena hash è stata superata, 0 se interrotta | 0 = CRITICAL (`AuditChainBroken`) |
| `registerwerk_audit_signing_key_age_seconds` | Secondi trascorsi dalla creazione/rotazione della chiave di firma Ed25519 della catena di controllo attiva | > 90 giorni = WARN (`AuditSigningKeyAgeWarning`) |

### Stato dell'indicizzatore (modulo `indexer`) { #indexer-health-indexer-module }

| Metrica | Descrizione | Soglia di avviso |
|--------|-------------|----------------|
| `registerwerk_indexer_last_sync_timestamp_seconds{chain_config_id,indexer_type}` | Epoca Unix dell'ultima sincronizzazione riuscita di ciascun indicizzatore; 0 se mai sincronizzato | `time() - metric` > 30 min = WARN, > 2 ore = CRITICAL |
| `registerwerk_chain_drift_open_total` | Conteggio delle righe attualmente OPEN `chain_drift_event` (registro rispetto a divergenza del saldo sulla catena, eWpG §16) | > 0 = CRITICAL (`ChainDriftDetected`) |

### Sanzioni/screening (modulo `screening`) { #sanctionsscreening-screening-module }

| Metrica | Descrizione | Soglia di avviso |
|--------|-------------|----------------|
| `registerwerk_sanctions_oldest_open_hit_seconds` | Età in secondi delle sanzioni aperte/PEP irrisolte da più tempo; 0 se nessuno aperto | > 4h = CRITICAL (`SanctionsHitOpenTooLong`, GwG §10) |
| `registerwerk_screening_errors_recent_total` | Conteggio di righe `ScreeningRun` con stato=ERROR nelle ultime 24 ore: errori di chiamata del provider, distinti dall'indicatore dell'età del riscontro riportato sopra | > 5 = CRITICAL (`ScreeningErrorsElevated`) — su questo `ScreeningGateImpl` applica il rifiuto in caso di errore (fail closed), bloccando silenziosamente le approvazioni di nuove entità |
| `registerwerk_screening_periodic_refresh_last_failures` | Entità che non hanno superato il nuovo screening nell'aggiornamento periodico giornaliero più recente | > 0 = WARN (`ScreeningPeriodicRefreshFailures`) |

### Riconciliazione token confidenziale (modulo `blockchain`) { #confidential-token-reconciliation-blockchain-module }

Nessuna tabella dedicata registra le discrepanze qui (a differenza di `chain_drift_event`) — entrambi gli indicatori sono
un'istantanea in memoria dell'esecuzione più recente di `ConfidentialBalanceReconciliationService.reconcile()`,
non una query DB live.

| Metrica | Descrizione | Soglia di avviso |
|--------|-------------|----------------|
| `registerwerk_confidential_reconciliation_mismatch_total` | Somma del conteggio di discrepanze più recenti tra tutte le risorse riservate | > 0 = CRITICAL (`ConfidentialReconciliationMismatchDetected`) |
| `registerwerk_confidential_reconciliation_last_run_timestamp_seconds` | Epoca Unix dell'esecuzione di riconciliazione più recente (qualsiasi risorsa) | `time() - metric` > 1h = WARN (`ConfidentialReconciliationStale`) — rileva un relayer Zama configurato in modo errato che interrompe silenziosamente la scansione |

### Stato del nodo RPC (modulo `blockchain`) { #rpc-node-health-blockchain-module }

| Metrica | Descrizione | Soglia di avviso |
|--------|-------------|----------------|
| `registerwerk_rpc_nodes_unhealthy_total` | Conteggio delle righe `RpcNode` attualmente contrassegnate come non integre | > 0 per 2m = WARN (`RpcNodesUnhealthy`) |

### Riconciliazione OrgIdentity onchain (modulo `orgidentity`) { #orgidentity-onchain-reconciliation-orgidentity-module }

Lo stesso avvertimento "nessuna tabella dedicata" della riconciliazione riservata di cui sopra: questi sono
misuratori reimpostati e poi riconteggiati per scansione (ogni riga attiva viene riesaminata ogni ciclo di 5 minuti, quindi
questo riflette accuratamente la "deriva attualmente aperta" senza bisogno di un nuovo stato persistente).

| Metrica | Descrizione | Soglia di avviso |
|--------|-------------|----------------|
| `registerwerk_org_chain_drift_open_total` | Registrazioni di organizzazioni/portafogli membri in disaccordo con onchain `OrgRegistry` nell'analisi più recente | > 0 = CRITICAL (`OrgChainDriftDetected`) |
| `registerwerk_permission_chain_drift_open_total` | Concessioni di autorizzazione in disaccordo con onchain `PermissionRegistry`, incl. ribaltamenti di limitazione di ruolo | > 0 = CRITICAL (`PermissionChainDriftDetected`) |

### DORA scadenze di reporting (modulo `dora`) { #dora-reporting-deadlines-dora-module }

| Metrica | Descrizione | Soglia di avviso |
|--------|-------------|----------------|
| `registerwerk_dora_deadline_breaches{breach_type}` | Conteggio scaduto per tipo di violazione (`classification`/`initial_report`/`final_report`/`resilience_test`), query in tempo reale tramite le stesse chiamate del repository `checkDeadlines()` vengono già eseguite quotidianamente | `sum(...)` > 0 = CRITICAL (`DoraDeadlineBreach`, Art. 19) |

### Stabilità della segnalazione normativa (modulo `regreporting`) { #regulatory-report-staleness-regreporting-module }

| Metrica | Descrizione | Soglia di avviso |
|--------|-------------|----------------|
| `registerwerk_regreport_stale_submissions_total` | Conteggio delle righe bozza `TRANSPORTED_UNVERIFIED` prive di prove di autorità verificate oltre la soglia configurata | > 0 = CRITICAL (`RegReportSubmissionsStale`) |

### Travel Rule (modulo `travelrule`) { #travel-rule-travelrule-module }

| Metrica | Descrizione | Soglia di avviso |
|--------|-------------|----------------|
| `registerwerk_travelrule_failed_messages_recent_total` | Conteggio di righe `travel_rule_message` con stato=FAILED nelle ultime 24 ore | > 0 = CRITICAL (`TravelRuleMessageSendFailures`, TFR Art. 14) |

### Invio notifiche (modulo `notification`) { #notification-delivery-notification-module }

L'unico contatore (non un misuratore) in questo elenco: nessuno stato persistente supporta in alcun modo la consegna delle e-mail
(nemmeno un evento di controllo "fire-and-forget"), quindi `increase()` su una finestra temporale è l'unica
query praticabile.

| Metrica | Descrizione | Soglia di avviso |
|--------|-------------|----------------|
| `registerwerk_notification_email_send_failures_total{context}` | Errori di invio di e-mail dall'avvio, contrassegnati con `generic` (fire-and-forget) o `statement_pdf` (consegna istruzioni §19) | `increase(...)[1h]` > 5 = WARN (`EmailDeliveryFailuresElevated`) |

### API tassi di errore e latenza { #api-error-rates-latency }

| Metrica | Descrizione | Soglia di avviso |
|--------|-------------|----------------|
| `http_server_requests_seconds_count{status=~"5.."}` | Conteggio errori 5xx | Tasso 5xx elevato = WARN (vedere il pannello "Tasso di errore HTTP" del dashboard) |
| `histogram_quantile(0.95, http_server_requests_seconds_bucket)` | Latenza del 95° percentile | > 1s = WARN |

### Stato del database { #database-health }

| Metrica | Descrizione | Soglia di avviso |
|--------|-------------|----------------|
| `hikaricp_connections_active` / `hikaricp_connections_max` | Utilizzo del pool di connessioni DB | > 80% = WARN (`DBConnectionsNearExhaustion`) |
| `pg_up` / `pg_stat_database_*` (tramite `postgres-exporter`) | Statistiche a livello di Postgres | Visualizza il set di parametri predefinito di `postgres-exporter` |

### Disponibilità { #availability }

| Metrica | Descrizione | Soglia di avviso |
|--------|-------------|----------------|
| `up{job="registerwerk-backend"}` | Se Prometheus riesce a fare scraping del backend | 0 per 1m = CRITICAL (`BackendDown`) |

### Saldo del portafoglio blockchain { #blockchain-wallet-balance }

Il portafoglio del distributore deve contenere token nativi sufficienti (ETH, MATIC, ecc.) per pagare il gas. Questo
non è ancora esposto come metrica Prometheus: nel frattempo, verifica tramite l'API di amministrazione:

```bash
curl http://localhost:8080/api/v1/admin/wallet/balances \
  -H "Authorization: Bearer $OPERATOR_JWT"
```

## Dashboard Grafana { #grafana-dashboards }

La directory di monitoraggio include un dashboard precostruito (`monitoring/grafana/dashboards/registerwerk-overview.json`)
che copre lo stato della catena di controllo, la deriva della catena, i riscontri di sanzioni aperti, il ritardo dell'indicizzatore, la latenza/errori API, la memoria
JVM e una fila dedicata di pannelli per ogni metrica nelle tabelle "Metriche chiave" di questa pagina
sopra: fornita automaticamente tramite `monitoring/grafana/dashboards` e
`monitoring/grafana/datasources`, non è necessaria alcuna importazione manuale.

## Configurazione Alertmanager { #alertmanager-configuration }

Modifica `monitoring/alertmanager.yml` per configurare i canali di notifica:

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

Alertmanager stesso non interpola i segnaposto in stile `${VAR}` nella sua configurazione YAML: imposta i valori reali
direttamente in `monitoring/alertmanager.yml` (o modellalo tramite i tuoi strumenti di gestione dei secret
prima di montarlo) piuttosto che fare affidamento sulle impostazioni predefinite in stile shell, che non vengono
mai sostituite.

## Pista di controllo { #audit-log }

Tutti i cambiamenti di stato vengono registrati in `audit_event`:

```sql
SELECT event_type, actor_id, occurred_at, payload
FROM audit_event
WHERE occurred_at > now() - interval '1 hour'
ORDER BY occurred_at DESC;
```

Accesso API:

```
GET /api/v1/audit/events?eventType=ASSET_DEPLOYED&from=2026-01-01T00:00:00Z
```

## Monitor dell'indicizzatore { #indexer-monitor }

`IndexerMonitorService` viene eseguito ogni 5 minuti, aggiorna l'indicatore
`registerwerk_indexer_last_sync_timestamp_seconds` per ogni indicizzatore e pubblica gli eventi di controllo
`INDEXER_STALE` quando un indicizzatore non viene sincronizzato per più di 2 ore.
