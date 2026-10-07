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
- PostgreSQL en `localhost:45432`
- Variables de entorno de `.env` (cargadas mediante [direnv](https://direnv.net/) o `export` manualmente)

## Migraciones de Flyway { #flyway-migrations }

Las migraciones se ejecutan automáticamente al inicio. Todos los archivos de migración están en:
```
backend/src/main/resources/db/migration/
```

El esquema es una única línea base de instalación limpia, `V1__initial_schema.sql`, que cubre todas las tablas, índices, disparadores y funciones (incluido el registro de auditoría con su particionado y los privilegios exclusivamente DML del login de ejecución `registerwerk_app`). Los cambios posteriores se añaden como `V{n}__description.sql` y nunca se editan tras su publicación.

!!! note
    Las bases de datos creadas a partir de compilaciones de desarrollo anteriores a la nueva consolidación de la línea base tienen un historial de Flyway incompatible y no pueden actualizarse in situ; vuelva a crearlas.

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
http://localhost:48080/swagger-ui.html
```

JSON completo de OpenAPI:
```
http://localhost:48080/v3/api-docs
```
