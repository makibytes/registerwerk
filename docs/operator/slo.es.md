---
title: Catálogo de SLO/SLI
description: Objetivos e indicadores de nivel de servicio para el registro, la política de presupuesto de errores y las consultas de Prometheus usadas para reportarlos.
---

# Catálogo de SLO/SLI { #slo-sli-catalogue }

**Servicio:** Registro eWpG Registerwerk  
**Base legal del SLO de disponibilidad:** eWpRV §6 (Registrierungsvoraussetzungen — Verfügbarkeit)  
**Periodicidad de revisión:** Trimestral, con el consejo y las autoridades competentes

---

## SLI y SLO { #slis-and-slos }

| SLI | Medición | SLO | Presupuesto de errores (30 días) |
|---|---|---|---|
| **Disponibilidad** | Tasa de errores HTTP 5xx en `/api/v1/**` | ≥99,5 % | 3,6 h/mes |
| **Latencia (lectura)** | p95 de los endpoints GET | ≤200 ms | — |
| **Latencia (escritura)** | p95 de POST/PUT/PATCH | ≤1000 ms | — |
| **Latencia (implementación)** | p95 del flujo de implementación de activos | ≤30 s | — |
| **Integridad de la cadena de auditoría** | `AuditChainVerificationService.valid` | 100 % — nunca se rompe | 0 roturas |
| **Actualidad del indexador** | Tiempo desde la última sincronización < 30 min | ≥99,9 % del tiempo | 43 min/mes |
| **Detección de deriva de cadena** | Ninguna deriva CRITICAL abierta durante >15 min | 100 % | 0 sin detectar |
| **Filtrado de sanciones** | Todas las alertas revisadas en un plazo de 4 h | 100 % en horario laboral | — |
| **Vencimiento de KYC** | Transiciones de KYC en un plazo de 24 h desde el vencimiento | 100 % | — |

---

## Política de presupuesto de errores { #error-budget-policy }

Cuando el presupuesto de errores de **disponibilidad** está consumido en más del 50 % en un mes:
1. Se congelan todas las versiones no críticas
2. Se declara un incidente de prioridad 1; se requiere guardia de ingeniería
3. Se notifica a la autoridad competente si la tendencia persiste más de 14 días

Cuando se rompe la **integridad de la cadena de auditoría**:
1. Incidente DORA CRITICAL inmediato
2. Las operaciones del registro se suspenden hasta que se vuelva a verificar la cadena
3. Se notifica a BaFin/CSSF/AMF/FMA en un plazo de 4 h

---

## Consultas de Prometheus (Grafana) { #prometheus-queries-grafana }

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

## Informes de SLO { #slo-reporting }

No hay implementado ningún informe mensual automático de SLO ni presentación ante ninguna autoridad a través de
`regreport_submission`. Los operadores deben definir, generar, revisar, conservar y distribuir la evidencia de SLO
conforme a un procedimiento de informes y resiliencia aprobado externamente.
