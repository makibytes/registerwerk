---
title: API-Referenz
---

# API-Referenz

Registerwerk stellt eine REST-API für alle Registervorgänge bereit. Diese Seite ist der Einstiegspunkt für Betreiber; die Konventionen (Fehler, Paginierung, Idempotenz, Beträge) stehen in der [REST-API-Übersicht](../platform/api.md), und jede Route steht im generierten [API-Routenindex](../platform/api-routes.md) (nur Englisch).

## Interaktive Dokumentation

Das OpenAPI-Dokument und die Swagger UI sind **standardmäßig ausgeschaltet**. Setzen Sie `SWAGGER_ENABLED=true`, damit das Backend sie ausliefert:

```
http://localhost:48080/swagger-ui.html
http://localhost:48080/api-docs
```

!!! warning "Die Spezifikation ist ohne Authentifizierung zugänglich, wenn aktiviert"
    Mit `SWAGGER_ENABLED=true` sind diese Pfade `permitAll`. Nichts verweigert die Einstellung im Produktionsmodus. Lassen Sie sie bei aus dem Internet erreichbaren Installationen ausgeschaltet.

## Authentifizierung

Alle API-Endpunkte außer `/api/v1/public/**` (und den Onboarding-Token-Endpunkten) erfordern ein Bearer-JWT:

```bash
curl http://localhost:48080/api/v1/entities \
  -H "Authorization: Bearer <jwt>"
```

Betreiber-Token stammen von `POST /api/v1/public/auth/login` (das Betreiberportal nutzt ihn direkt). Bei `ENTRA_ENABLED=true` stammen Kunden-Token von Entra, sonst vom selben lokalen Endpunkt. Siehe [Sicherheit & Authentifizierung](../platform/security.md) und, für die Kundenseite, [Anmeldung](../customer/authentication.md). Das Backend validiert jedes Token selbst; Kong tut das nicht.

## Wo man einen Endpunkt findet

| Bedarf | Wo |
|---|---|
| Jede Route, ihr Rollenausdruck und ihre Step-up-Anforderung | [API-Routenindex](../platform/api-routes.md) |
| Welche Routen Step-up oder einen zweiten Genehmiger brauchen und welche Vorgänge den Request-Body binden | [Step-up-Matrix](../compliance/step-up-matrix.md) |
| Request- und Response-Schemas | OpenAPI-Dokument (`SWAGGER_ENABLED=true`) |
| Fehlerformat, Paginierung, `Idempotency-Key`, Dezimalbeträge | [REST-API-Übersicht](../platform/api.md) |

## Fehlerantworten

Fehler verwenden den Record `ErrorResponse` (`status`, `message`, `timestamp`, `path`); die Statuszuordnung (400, 401, 403, 404, 409, 500) steht in der [REST-API-Übersicht](../platform/api.md#error-response-format). Manche Endpunkte ergänzen einen maschinenlesbaren `code`, zum Beispiel `IDEMPOTENCY_KEY_REQUIRED`, `IMPERSONATION_READ_ONLY` oder `IMPERSONATION_ACTION_DENIED`.

## Ratenbegrenzung

Kunden-API-Aufrufe laufen über Kong, das jede Client-IP auf 300 Anfragen pro Minute und 10.000 pro Stunde begrenzt; gezählt wird in Redis, sodass das Limit über alle Kong-Replikate geteilt wird (siehe [API-Gateway](installation/api-gateway.md)). Aufrufe aus dem Betreiberportal laufen nicht über Kong und unterliegen diesem Limit nicht. Rate-Limit-Header (`X-RateLimit-Limit-Minute`, `X-RateLimit-Remaining-Minute`) sind in den Antworten enthalten; ein überschrittenes Limit wird mit `429` beantwortet.
