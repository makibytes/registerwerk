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

Versioni attuali dello schema:
| Versione | Descrizione |
|---|---|
| V1 | Schema iniziale consolidato che copre soggetti giuridici, KYC, token di onboarding, risorse, distribuzioni, titolari, pista di controllo, configurazione della catena, trasferimenti di token, cursori di stato dell'indicizzatore, ONCHAINID e attestazioni, tabelle della suite ERC-3643 T-REX, catene Fhenix e Inco e tabelle correlate |

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
