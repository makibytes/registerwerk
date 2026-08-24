---
title: REST API Übersicht
description: URL-Struktur, Authentifizierung, Fehlerantworten, Paginierung und API-Konventionen.
---

# REST API Übersicht { #rest-api-overview }

Alle Funktionen von Registerwerk werden über eine REST-API unter `http://backend:8080`
bereitgestellt. Das Operator-Frontend verbindet sich direkt; das Kunden-Frontend verbindet sich über
Kong (`http://kong:8000`). Die API ist mit OpenAPI 3 dokumentiert (Swagger-UI verfügbar unter
`/swagger-ui.html`).

---

## URL-Struktur { #url-structure }

| Muster | Authentifizierung erforderlich | Verfügbar für |
|---|---|---|
| `/api/v1/public/**` | Nein | Alle |
| `/api/v1/onboarding/token-info/**` | Nein | Kunden-Onboarding-Ablauf |
| `/api/v1/onboarding/complete` | Nein | Kunden-Onboarding-Ablauf |
| `/api/v1/**` | JWT erforderlich | Authentifizierte Benutzer (rollenabhängig) |

---

## Authentifizierung { #authentication }

Alle geschützten Endpunkte erfordern:

```
Authorization: Bearer <jwt>
```

**Das Backend validiert jedes Token selbst, bei jeder Anfrage.** Kong validiert keine JWTs und teilt
dem Backend nicht mit, wer der Aufrufer ist – sein `openid-connect`-Plugin ist eine Enterprise-Funktion
und in diesem OSS-Setup nicht aktiv. Kong *entfernt* zusätzlich vom Client mitgesendete
Identitäts-Header, sodass davor nichts eingeschmuggelt werden kann.

Operator-Token werden von `POST /api/v1/public/auth/login` ausgestellt (HS256,
`iss: registerwerk-local`). Kunden-Token werden vom OIDC-Provider ausgestellt, wenn
`ENTRA_ENABLED=true`, andernfalls vom selben lokalen Endpunkt. Ein delegierender Decoder leitet
anhand des JWS-`alg`-Headers weiter; beide Zweige sind auf den Issuer gepinnt, der OIDC-Zweig
zusätzlich auf die Audience. Siehe [Sicherheit & Authentifizierung](security.md).

---

## Fehlerantwortformat { #error-response-format }

Alle Fehler folgen dem `ErrorResponse`-Datensatz:

```json
{
  "status": 404,
  "message": "Asset with id 'abc...' not found",
  "timestamp": "2026-05-22T10:15:30Z",
  "path": "/api/v1/assets/abc..."
}
```

| HTTP-Status | Ausgelöst durch | Ursache |
|---|---|---|
| 400 | `IllegalArgumentException` | Ungültige Eingabe (Validierungsfehler, ungültiger Enum-Wert) |
| 401 | `InvalidCredentialsException` | Falsches Passwort, abgelaufenes JWT |
| 403 | `AccessDeniedException` | Unzureichende Rolle, Step-up erforderlich |
| 404 | `EntityNotFoundException` | Ressource existiert nicht |
| 409 | `InvalidStateTransitionException` | Vorgang im aktuellen Status nicht zulässig (z. B. Bereitstellung eines bereits bereitgestellten Assets) |
| 500 | Unerwartete Ausnahme | Interner Serverfehler (Details werden in der Produktion nicht offengelegt) |

!!! info "Fehlermeldungen in der Produktion"
    `error.include-message` ist im `prod`-Profil auf `never` gesetzt. In Entwicklung und Test ist es
    `always`. Das verhindert, dass Stacktraces in Produktionsantworten durchsickern.

---

## Paginierung { #pagination }

Listen-Endpunkte unterstützen cursorbasierte Paginierung mit den Parametern `page` und `size`:

```
GET /api/v1/assets?page=0&size=20&sort=createdAt,desc
```

Antworten enthalten einen `X-Total-Count`-Header mit der Gesamtzahl der Datensätze (vor der
Paginierung). Der Antwortkörper ist immer ein Array (nie ein Wrapper-Objekt).

---

## Wichtige API-Gruppen { #key-api-groups }

### Assets (`/api/v1/assets`) { #assets-apiv1assets }

| Methode | Pfad | Beschreibung |
|---|---|---|
| `GET` | `/api/v1/assets` | Alle Assets auflisten (paginiert) |
| `POST` | `/api/v1/assets` | Neues Asset anlegen |
| `GET` | `/api/v1/assets/{id}` | Asset per ID abrufen |
| `POST` | `/api/v1/assets/{id}/deploy` | Token auf der Blockchain bereitstellen |
| `POST` | `/api/v1/assets/{id}/mint` | Token minten |
| `POST` | `/api/v1/assets/{id}/burn` | Token vernichten (Step-up + Vier-Augen) |
| `POST` | `/api/v1/assets/{id}/force-transfer` | Zwangsübertragung (Step-up + Vier-Augen) |
| `POST` | `/api/v1/assets/{id}/freeze/{address}` | Adresse einfrieren (erfordert HolderBlock) |

### Kunden (`/api/v1/customers`) { #customers-apiv1customers }

| Methode | Pfad | Beschreibung |
|---|---|---|
| `GET` | `/api/v1/customers` | Juristische Personen auflisten |
| `POST` | `/api/v1/customers` | Juristische Person anlegen |
| `GET` | `/api/v1/customers/{id}` | Rechtsträger abrufen |
| `POST` | `/api/v1/customers/{id}/kyc/documents` | KYC-Dokument hochladen |
| `POST` | `/api/v1/customers/{id}/kyc/approve` | KYC genehmigen (COMPLIANCE_OFFICER + Step-up) |
| `GET` | `/api/v1/customers/{id}/beneficial-owners` | Wirtschaftlich Berechtigte auflisten |
| `POST` | `/api/v1/customers/{id}/beneficial-owners` | Wirtschaftlich Berechtigten hinzufügen |

### Compliance (`/api/v1/compliance`) { #compliance-apiv1compliance }

| Methode | Pfad | Beschreibung |
|---|---|---|
| `POST` | `/api/v1/compliance/screening/entities/{id}/screen` | Manuelle Prüfung auslösen |
| `GET` | `/api/v1/compliance/screening/entities/{id}/runs` | Screening-Verlauf abrufen |
| `POST` | `/api/v1/compliance/screening/hits/{hitId}/accept` | Treffer akzeptieren/verwerfen |
| `GET` | `/api/v1/holder-blocks` | Alle HolderBlocks auflisten |
| `POST` | `/api/v1/holder-blocks` | Sperrvermerk anlegen (Step-up + Vier-Augen) |
| `POST` | `/api/v1/holder-blocks/{id}/lift` | Sperrvermerk aufheben (Step-up + Vier-Augen) |

### Regulatory Reporting (`/api/v1/regulatory-reporting`) { #regulatory-reporting-apiv1regulatory-reporting }

| Methode | Pfad | Beschreibung |
|---|---|---|
| `POST` | `/api/v1/regulatory-reporting/mifir` | On-Demand-MiFIR-Export auslösen |
| `POST` | `/api/v1/regulatory-reporting/dac8` | On-Demand-DAC8-Export auslösen |
| `GET` | `/api/v1/regulatory-reporting/submissions` | Übermittlungsverlauf auflisten |

### DORA (`/api/v1/dora`) { #dora-apiv1dora }

| Methode | Pfad | Beschreibung |
|---|---|---|
| `GET` | `/api/v1/dora/incidents` | Offene IKT-Vorfälle auflisten |
| `POST` | `/api/v1/dora/incidents` | IKT-Vorfall melden (Art. 17) |
| `PATCH` | `/api/v1/dora/incidents/{id}/status` | Vorfallstatus/Ursache aktualisieren |
| `POST` | `/api/v1/dora/incidents/{id}/report-to-authority` | Ersten/abschließenden Behördenbericht erfassen (Art. 19) |
| `GET` | `/api/v1/dora/providers` | IKT-Drittanbieterregister auflisten (Art. 28) |
| `GET` | `/api/v1/dora/providers/expiring` | Anbieter mit bald auslaufenden Verträgen auflisten |
| `GET` | `/api/v1/dora/resilience-tests` | Resilienztest-Ergebnisse auflisten (Art. 24/25) |
| `GET` | `/api/v1/dora/resilience-tests/overdue` | Überfällige Resilienztests auflisten |
| `POST` | `/api/v1/dora/resilience-tests` | Resilienztest-Ergebnis erfassen |

---

## OpenAPI / Swagger UI { #openapi-swagger-ui }

Die OpenAPI-Spezifikation und die interaktive UI werden **vom Backend** auf Port 8080 bereitgestellt,
nicht von diesem Dokumentationsserver.

| URL | Beschreibung |
|---|---|
| [`{{ backend_url }}/swagger-ui.html`]({{ backend_url }}/swagger-ui.html) | Interaktive Swagger UI (Browser) |
| [`{{ backend_url }}/api-docs`]({{ backend_url }}/api-docs) | OpenAPI 3 JSON (maschinenlesbar) |
| [`{{ backend_url }}/actuator/health`]({{ backend_url }}/actuator/health) | Health-Check |
| [`{{ backend_url }}/actuator/info`]({{ backend_url }}/actuator/info) | Build-Info |

!!! info "Diese Dokumentationsseite vs. die API"
    Diese Site (Port 8003) ist eine statische MkDocs-Referenz – sie proxyt das Backend nicht. Öffnen
    Sie die obigen Links direkt im Browser, während der Stack läuft (`docker compose up -d`).

!!! warning "Swagger UI in der Produktion"
    Die Swagger UI ist im Spring-Profil `prod` deaktiviert. In Entwicklungs- und Staging-Umgebungen
    ist sie ohne Authentifizierung zugänglich. In der Produktion muss sie explizit aktiviert und
    hinter einer IP-Allowlist oder Basic-Auth geschützt werden.
