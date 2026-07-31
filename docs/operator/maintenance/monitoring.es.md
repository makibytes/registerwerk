---
title: Monitoreo
---

# Monitoreo { #monitoring }

El registro se envía con una pila de monitoreo de Prometheus + Grafana. Esta página describe qué monitorear
, qué métricas son importantes y cómo configurar las alertas.

## Inicio de la pila de monitoreo { #starting-the-monitoring-stack }

La pila principal (`docker compose up --build` de la raíz del repositorio) ya debe estar ejecutándose primero;
la pila de monitoreo se une a su red `registerwerk_default` para alcanzar `backend`/`kong`.

```bash
docker compose -f monitoring/docker-compose.yml up -d
```

Esto comienza:
- **Prometheus** en `http://localhost:9090`
- **Grafana** en `http://localhost:3000` (iniciar sesión: `admin` / `$GRAFANA_ADMIN_PASSWORD`, consulte `.env.example`)
- **Alertmanager** en `http://localhost:9093`
- **postgres-exporter** y **node-exporter** (alimentan los paneles de métricas del host/estado de la base de datos)

## Puntos finales de estado { #health-endpoints }

| Punto final | Propósito |
|---|---|
| `GET /actuator/health` | Salud general (UP/DOWN) |
| `GET /actuator/health/liveness` | Proceso vivo: utilizado por la sonda de vida (liveness) del Helm chart |
| `GET /actuator/health/readiness` | Conexiones de cadena DB + listas: utilizadas por la sonda de preparación |
| `GET /actuator/prometheus` | Scraping de métricas de Prometheus (no autenticado; consulte `SecurityConfig`) |

## Métricas clave para monitorear { #key-metrics-to-monitor }

Estas son las métricas reales de Micrometer registradas en el backend (cada una debajo del módulo que
posee): cada una tiene una regla correspondiente en `monitoring/alerts/registerwerk.yml` y panel
en `monitoring/grafana/dashboards/registerwerk-overview.json`.

### Integridad de la cadena de auditoría (módulo `audit`) { #audit-chain-integrity-audit-module }

| Métrica | Descripción | Umbral de alerta |
|--------|----------------------|----------------|
| `registerwerk_audit_chain_valid` | 1 si pasó la última verificación de la cadena hash, 0 si está rota | 0 = CRITICAL (`AuditChainBroken`) |
| `registerwerk_audit_signing_key_age_seconds` | Segundos desde que se creó/rotó la clave de firma Ed25519 de la cadena de auditoría activa | > 90 días = WARN (`AuditSigningKeyAgeWarning`) |

### Estado del indexador (módulo `indexer`) { #indexer-health-indexer-module }

| Métrica | Descripción | Umbral de alerta |
|--------|----------------------|----------------|
| `registerwerk_indexer_last_sync_timestamp_seconds{chain_config_id,indexer_type}` | Época Unix de la última sincronización exitosa de cada indexador; 0 si nunca se sincroniza | `time() - metric` > 30 min = WARN, > 2h = CRITICAL |
| `registerwerk_chain_drift_open_total` | Recuento de filas actuales OPEN `chain_drift_event` (registro frente a divergencia de equilibrio en cadena, eWpG §16) | > 0 = CRITICAL (`ChainDriftDetected`) |

### Sanciones/detección (módulo `screening`) { #sanctionsscreening-screening-module }

| Métrica | Descripción | Umbral de alerta |
|--------|----------------------|----------------|
| `registerwerk_sanctions_oldest_open_hit_seconds` | Antigüedad en segundos de la alerta de sanciones o PEP abierta sin resolver más antigua; 0 si no hay ninguna abierta | > 4h = CRITICAL (`SanctionsHitOpenTooLong`, GwG §10) |
| `registerwerk_screening_errors_recent_total` | Recuento de filas `ScreeningRun` con estado=ERROR en las últimas 24 horas: fallos en las llamadas al proveedor, distintos del indicador de antigüedad de alertas anterior | > 5 = CRITICAL (`ScreeningErrorsElevated`) — `ScreeningGateImpl` deniega por defecto (fail closed) en este caso, bloqueando silenciosamente las aprobaciones de nuevas entidades |
| `registerwerk_screening_periodic_refresh_last_failures` | Entidades que no pasaron la nueva evaluación en la actualización periódica diaria más reciente | > 0 = WARN (`ScreeningPeriodicRefreshFailures`) |

### Conciliación de token confidencial (módulo `blockchain`) { #confidential-token-reconciliation-blockchain-module }

Aquí no hay discrepancias en los registros de la tabla dedicada (a diferencia de `chain_drift_event`): ambos indicadores son una instantánea en memoria
de la ejecución más reciente de `ConfidentialBalanceReconciliationService.reconcile()`,
no es una consulta de base de datos en vivo.

| Métrica | Descripción | Umbral de alerta |
|--------|----------------------|----------------|
| `registerwerk_confidential_reconciliation_mismatch_total` | Suma del recuento de discrepancias más reciente en todos los activos confidenciales | > 0 = CRITICAL (`ConfidentialReconciliationMismatchDetected`) |
| `registerwerk_confidential_reconciliation_last_run_timestamp_seconds` | Época Unix de la ejecución de conciliación más reciente (cualquier activo) | `time() - metric` > 1h = WARN (`ConfidentialReconciliationStale`) — detecta un retransmisor (relayer) de Zama mal configurado que detiene silenciosamente el barrido |

### Estado del nodo RPC (módulo `blockchain`) { #rpc-node-health-blockchain-module }

| Métrica | Descripción | Umbral de alerta |
|--------|-------------|----------------|
| `registerwerk_rpc_nodes_unhealthy_total` | Recuento de filas `RpcNode` actualmente marcadas como no saludables | > 0 para 2m = WARN (`RpcNodesUnhealthy`) |

### Reconciliación en cadena de OrgIdentity (módulo `orgidentity`) { #orgidentity-onchain-reconciliation-orgidentity-module }

La misma advertencia de "sin tabla dedicada" que la conciliación confidencial anterior: estos son indicadores
que se reinician y luego cuentan por barrido (cada fila activa se vuelve a examinar cada ciclo de 5 minutos, por lo que
esto refleja con precisión la "deriva abierta actualmente" sin necesidad de un nuevo estado persistente).

| Métrica | Descripción | Umbral de alerta |
|--------|-------------|----------------|
| `registerwerk_org_chain_drift_open_total` | Registros de organizaciones/billeteras de miembros que no están de acuerdo con la cadena `OrgRegistry` en el barrido más reciente | > 0 = CRITICAL (`OrgChainDriftDetected`) |
| `registerwerk_permission_chain_drift_open_total` | Concesiones de permisos que no están de acuerdo con `PermissionRegistry` en cadena, incl. cambios de restricción de roles | > 0 = CRITICAL (`PermissionChainDriftDetected`) |

### Fechas límite de informes DORA (módulo `dora`) { #dora-reporting-deadlines-dora-module }

| Métrica | Descripción | Umbral de alerta |
|--------|-------------|----------------|
| `registerwerk_dora_deadline_breaches{breach_type}` | Recuento vencido por tipo de infracción (`classification`/`initial_report`/`final_report`/`resilience_test`), consulta en vivo a través de las mismas llamadas al repositorio `checkDeadlines()` ya se ejecuta diariamente | `sum(...)` > 0 = CRITICAL (`DoraDeadlineBreach`, Art. 19) |

### Caducidad del informe regulatorio (módulo `regreporting`) { #regulatory-report-staleness-regreporting-module }

| Métrica | Descripción | Umbral de alerta |
|--------|-------------|----------------|
| `registerwerk_regreport_stale_submissions_total` | Recuento de filas de borrador `TRANSPORTED_UNVERIFIED` que carecen de evidencia de autoridad verificada más allá del umbral configurado | > 0 = CRITICAL (`RegReportSubmissionsStale`) |

### Regla de viaje (módulo `travelrule`) { #travel-rule-travelrule-module }

| Métrica | Descripción | Umbral de alerta |
|--------|-------------|----------------|
| `registerwerk_travelrule_failed_messages_recent_total` | Recuento de filas `travel_rule_message` con estado=FAILED en las últimas 24 horas | > 0 = CRITICAL (`TravelRuleMessageSendFailures`, TFR Art. 14) |

### Entrega de notificación (módulo `notification`) { #notification-delivery-notification-module }

El único contador (no un indicador) de esta lista; ningún estado persistente respalda en absoluto la entrega de correo electrónico
(ni siquiera un evento de auditoría de disparar y olvidar), por lo que `increase()` en una ventana de tiempo es la única consulta viable.

| Métrica | Descripción | Umbral de alerta |
|--------|-------------|----------------|
| `registerwerk_notification_email_send_failures_total{context}` | Errores en el envío de correo electrónico desde el inicio, etiquetados como `generic` (disparar y olvidar) o `statement_pdf` (entrega de declaración §19) | `increase(...)[1h]` > 5 = WARN (`EmailDeliveryFailuresElevated`) |

### Tasas de error y latencia de API { #api-error-rates-latency }

| Métrica | Descripción | Umbral de alerta |
|--------|-------------|----------------|
| `http_server_requests_seconds_count{status=~"5.."}` | Recuento de errores 5xx | Tasa 5xx elevada = WARN (consulte el panel "Tasa de error HTTP" del tablero) |
| `histogram_quantile(0.95, http_server_requests_seconds_bucket)` | Latencia percentil 95 | > 1s = WARN |

### Estado de la base de datos { #database-health }

| Métrica | Descripción | Umbral de alerta |
|--------|-------------|----------------|
| `hikaricp_connections_active` / `hikaricp_connections_max` | Utilización del grupo de conexiones de base de datos | > 80% = WARN (`DBConnectionsNearExhaustion`) |
| `pg_up` / `pg_stat_database_*` (a través de `postgres-exporter`) | Estadísticas de nivel Postgres | Consulte el conjunto de métricas predeterminado propio de `postgres-exporter` |

### Disponibilidad { #availability }

| Métrica | Descripción | Umbral de alerta |
|--------|-------------|----------------|
| `up{job="registerwerk-backend"}` | Si Prometheus puede scrapear el backend | 0 por 1m = CRITICAL (`BackendDown`) |

### Saldo del monedero en blockchain { #blockchain-wallet-balance }

El monedero del implementador debe contener suficientes tokens nativos (ETH, MATIC, etc.) para pagar el gas. Esto
aún no está expuesto como métrica de Prometheus; mientras tanto, verifíquelo a través de la API de administración:

```bash
curl http://localhost:8080/api/v1/admin/wallet/balances \
  -H "Authorization: Bearer $OPERATOR_JWT"
```

## Paneles de Grafana { #grafana-dashboards }

El directorio de monitoreo incluye un panel prediseñado (`monitoring/grafana/dashboards/registerwerk-overview.json`)
que cubre el estado de la cadena de auditoría, la deriva de la cadena, las alertas de sanciones abiertas, el retraso del indexador, la latencia/errores de API, la memoria
JVM y una fila dedicada de paneles para cada métrica de las tablas de «Métricas clave» de esta página
mostradas más arriba — aprovisionado automáticamente a través de `monitoring/grafana/dashboards` y
`monitoring/grafana/datasources`, sin necesidad de importación manual.

## Configuración del administrador de alertas { #alertmanager-configuration }

Edite `monitoring/alertmanager.yml` para configurar los canales de notificación:

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

El propio Alertmanager no interpola marcadores de posición de estilo `${VAR}` en su configuración YAML — establezca los valores reales
directamente en `monitoring/alertmanager.yml` (o genérelo mediante sus propias herramientas de gestión de secretos
antes de montarlo) en lugar de depender de valores predeterminados de estilo shell, que nunca se
sustituyen.

## Registro de auditoría { #audit-log }

Todos los cambios de estado se registran en `audit_event`:

```sql
SELECT event_type, actor_id, occurred_at, payload
FROM audit_event
WHERE occurred_at > now() - interval '1 hour'
ORDER BY occurred_at DESC;
```

Acceso a la API:

```
GET /api/v1/audit/events?eventType=ASSET_DEPLOYED&from=2026-01-01T00:00:00Z
```

## El monitor del indexador { #indexer-monitor }

`IndexerMonitorService` se ejecuta cada 5 minutos, actualiza el indicador
`registerwerk_indexer_last_sync_timestamp_seconds` para cada indexador y publica eventos de auditoría
`INDEXER_STALE` cuando un indexador no se ha sincronizado durante más de 2 horas.
