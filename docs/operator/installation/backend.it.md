---
title: Configurazione del back-end
---

# Configurazione backend { #backend-setup }

## In esecuzione localmente { #running-locally }

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Il profilo `local` legge da `src/main/resources/application-local.yml` e si aspetta:
- PostgreSQL su `localhost:45432`
- Variabili di ambiente da `.env` (caricate tramite [direnv](https://direnv.net/) o `export` manualmente)

## Migrazioni Flyway { #flyway-migrations }

Le migrazioni vengono eseguite automaticamente all'avvio. Tutti i file di migrazione si trovano in:
```
backend/src/main/resources/db/migration/
```

Lo schema è un'unica baseline di installazione pulita, `V1__initial_schema.sql`, che copre ogni tabella, indice, trigger e funzione (incluso il log di audit con il relativo partizionamento e i privilegi esclusivamente DML del login di runtime `registerwerk_app`). Le modifiche successive vengono aggiunte come `V{n}__description.sql` e non vengono mai modificate dopo il rilascio.

!!! note
    I database creati da build di sviluppo precedenti alla nuova consolidazione della baseline hanno una cronologia Flyway incompatibile e non possono essere aggiornati sul posto; ricrearli.

## Salute e monitoraggio { #health-and-monitoring }

```bash
# Liveness
GET /actuator/health/liveness

# Readiness (checks DB + chain connections)
GET /actuator/health/readiness

# Metrics (Prometheus format)
GET /actuator/prometheus
```

## OpenAPI { #openapi }

L'interfaccia utente di Swagger è disponibile all'indirizzo:
```
http://localhost:48080/swagger-ui.html
```

JSON OpenAPI completo:
```
http://localhost:48080/v3/api-docs
```
