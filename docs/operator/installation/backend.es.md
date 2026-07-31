---
title: Configuración del backend
---

# Configuración del backend { #backend-setup }

## Ejecutando localmente { #running-locally }

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

El perfil `local` lee desde `src/main/resources/application-local.yml` y espera:
- PostgreSQL en `localhost:5432`
- Variables de entorno de `.env` (cargadas mediante [direnv](https://direnv.net/) o `export` manualmente)

## Migraciones de Flyway { #flyway-migrations }

Las migraciones se ejecutan automáticamente al inicio. Todos los archivos de migración están en:
```
backend/src/main/resources/db/migration/
```

Versiones de esquema actuales:
| Versión | Descripción |
|---|---|
| V1 | Esquema inicial consolidado que cubre entidades jurídicas, KYC, tokens de incorporación, activos, implementaciones, titulares, registro de auditoría, configuración de cadena, transferencias de tokens, cursores de estado del indexador, ONCHAINID y atestaciones, tablas de la suite ERC-3643 T-REX, cadenas Fhenix e Inco, y tablas relacionadas |

## Salud y monitorización { #health-and-monitoring }

```bash
# Liveness
GET /actuator/health/liveness

# Readiness (checks DB + chain connections)
GET /actuator/health/readiness

# Metrics (Prometheus format)
GET /actuator/prometheus
```

## OpenAPI { #openapi }

La interfaz de usuario de Swagger está disponible en:
```
http://localhost:8080/swagger-ui.html
```

JSON completo de OpenAPI:
```
http://localhost:8080/v3/api-docs
```
