---
title: REST API Übersicht
description: URL-Struktur, Authentifizierung, Fehlerantworten, Paginierung und API-Konventionen.
---

# REST API Übersicht { #rest-api-overview }

Alle Funktionen von Registerwerk werden über eine REST-API unter `http://backend:8080`
bereitgestellt. Das Operator-Frontend verbindet sich direkt; das Kunden-Frontend verbindet sich über
Kong (`http://kong:8000`). Jede gemappte Route steht im generierten [API-Routenindex](api-routes.md) (nur Englisch). Ein OpenAPI-3-Dokument und eine Swagger UI existieren, sind aber **standardmäßig ausgeschaltet** (siehe [OpenAPI / Swagger UI](#openapi-swagger-ui)).

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

Listen-Endpunkte mit Paginierung akzeptieren `page` (nullbasiert) und `size`, zum Beispiel:

```
GET /api/v1/assets?page=0&size=20&sort=createdAt,desc
```

Die Antwortform ist **je Endpunkt** verschieden: Manche liefern ein einfaches JSON-Array (die Gesamtzahl steht dann im Response-Header `X-Total-Count`, den die CORS-Konfiguration für Browser freigibt), andere den `PageResponse`-Wrapper `{ content, totalElements, totalPages, page, size }`. Prüfen Sie das Schema des Endpunkts im OpenAPI-Dokument, bevor Sie sich auf eine der beiden Formen verlassen.

---

## Idempotency-Key und Beträge { #idempotency-key-and-amounts }

Geld- und zustandsverändernde Admin-/Emittenten-Endpunkte verlangen den Header `Idempotency-Key`: Mint, Burn, erzwungene Übertragungen/Freigaben, Force-Burn, Sperr- und Whitelist-Änderungen, Solana-Token-Administration, Slot- und Vault-Operationen, ERC-3643-Agentenaktionen, Lending-Markt-Operationen und -Abgleich, Änderungen an Zahlungswegen, Wallet-Import, Übergabe/Abschluss von Registerübertragungen und Tilgung eines Assets. Ein `POST`, `PUT`, `PATCH` oder `DELETE` ohne gültigen Schlüssel wird mit `400` und dem Code `IDEMPOTENCY_KEY_REQUIRED` (bzw. `IDEMPOTENCY_KEY_INVALID`) abgewiesen, bevor etwas ausgeführt wird. Andere Endpunkte bleiben optional.

- Senden Sie pro Benutzeraktion einen eindeutigen Wert (UUID; 8-255 Zeichen aus `A-Za-z0-9._:-`) und **verwenden Sie denselben Wert erneut, wenn Sie dieselbe Anfrage wiederholen** (nach Timeout oder `5xx`). Die Wiederholung liefert dann das ursprüngliche Ergebnis (`X-Idempotent-Replay: true`) bzw. dieselbe Transaktion statt einer doppelten Ausführung.
- Der Schlüssel gilt je Aufrufer: Rechtsträger bei Kundentokens, handelnder Benutzer bei Operator-Tokens. Derselbe Schlüssel mit anderer Methode, anderem Pfad oder Body wird mit `422` beantwortet; eine noch laufende Anfrage mit `409`.
- Der Schlüssel wird zusätzlich an der Outbox-Zeile der Chain-Transaktion gespeichert, sodass eine Wiederholung auch nach Ablauf der gecachten Antwort auf dieselbe signierte Transaktion abgebildet wird. Antworten `401`/`403` (auch Step-up-Challenges) und `5xx` werden nicht gecacht; die Wiederholung nach einem Step-up mit demselben Schlüssel ist daher sicher.

**Beträge sind Dezimalzeichenketten.** Senden Sie Token-Beträge (`amount`, `value`, `newCap`, `navPerShare`, ...) als JSON-Strings wie `"1000000000000000000000"`. Eine JavaScript-Zahl verliert oberhalb von 2^53 an Genauigkeit. Für ein Release wird eine JSON-Zahl noch akzeptiert, wenn sie exakt darstellbar ist (Ganzzahl unter 2^53 oder Dezimalzahl mit höchstens 15 signifikanten Stellen); es wird eine Deprecation-Warnung protokolliert. Alles andere wird mit `400` und `Invalid amount: ...` beantwortet.

## Routengruppen { #route-groups }

Der generierte [API-Routenindex](api-routes.md) ist die vollständige, aus dem Code abgeleitete Liste (Methode, Pfad, Rollenausdruck, Step-up). Die wichtigsten Basispfade:

| Bereich | Basispfad |
|---|---|
| Assets und Deployments (Emittenten-Mint/Burn unter `.../deployments/{depId}/issuer/`, erzwungene Betreibervorgänge unter `.../deployments/{depId}/admin/`) | `/api/v1/assets`, `/api/v1/deployments` |
| Rechtsträger und KYC (`/api/v1/entities/{entityId}/kyc/...`), KYC-Prüfwarteschlange | `/api/v1/entities`, `/api/v1/kyc` |
| Sanktionsprüfung (`/api/v1/compliance/screening/...`, Treffer unter `/hits/{hitId}/accept`) und weitere Compliance-Funktionen | `/api/v1/compliance` |
| Sperrvermerk (Holder-Blocks) | `/api/v1/holder-blocks` |
| Regulatorisches Reporting (MiFIR, DAC8) | `/api/v1/regulatory-reporting` |
| DORA-Vorfälle, Dienstleister, Resilienztests | `/api/v1/dora` |
| Handel, Repo Desk, Lending, Kapitalmaßnahmen | `/api/v1/trading`, `/api/v1/repo-desk`, `/api/v1/lending`, `/api/v1/corporate-actions` |
| Freigabe-Warteschlange (Vier-Augen-Prinzip) | `/api/v1/approvals` |
| Audit-Log, Kettenprüfung | `/api/v1/audit` |
| Betreiberverwaltung (Nutzer, Wallets, ...) | `/api/v1/admin` |
| Selbstbedienung der Kundenunternehmen | `/api/v1/company`, `/api/v1/me` |
| Öffentlich, ohne Authentifizierung (Chains, Plattformfähigkeiten, Travel Rule) | `/api/v1/public` |

Das Genehmigen von KYC ist zum Beispiel `POST /api/v1/entities/{entityId}/kyc/approve`: Auslösender ist ein `REGISTRY_ADMIN` oder `COMPLIANCE_OFFICER`, und der Aufruf braucht Step-up und einen zweiten Genehmiger (siehe [Step-up-Matrix](../compliance/step-up-matrix.md)).

---

## OpenAPI / Swagger UI { #openapi-swagger-ui }

Das OpenAPI-Dokument und die Swagger UI werden **vom Backend** bereitgestellt, nicht von diesem Dokumentationsserver, und sind **ausgeschaltet, solange `SWAGGER_ENABLED=true` nicht gesetzt ist** (Standard `false`, in jedem Profil).

| URL (wenn aktiviert) | Beschreibung |
|---|---|
| [`{{ backend_url }}/swagger-ui.html`]({{ backend_url }}/swagger-ui.html) | Interaktive Swagger UI (Browser) |
| [`{{ backend_url }}/api-docs`]({{ backend_url }}/api-docs) | OpenAPI 3 JSON (maschinenlesbar) |
| [`{{ backend_url }}/actuator/health`]({{ backend_url }}/actuator/health) | Health-Check |
| [`{{ backend_url }}/actuator/info`]({{ backend_url }}/actuator/info) | Build-Info |

!!! info "Diese Dokumentationsseite vs. die API"
    Diese Site (Port 48003) ist eine statische MkDocs-Referenz – sie proxyt das Backend nicht. Öffnen Sie die obigen Links direkt im Browser, während der Stack läuft (`docker compose up -d`).

!!! warning "SWAGGER_ENABLED=true macht die Spezifikation ohne Authentifizierung zugänglich"
    Ist es aktiviert, sind `/swagger-ui.html`, `/swagger-ui/**` und `/api-docs/**` öffentlich (`permitAll` in der Sicherheitskonfiguration): Jeder, der das Backend erreicht, kann den gesamten Routen- und Schemakatalog lesen. Nichts verweigert `SWAGGER_ENABLED=true` im Produktionsmodus. Lassen Sie es in der Produktion aus oder stellen Sie das Backend hinter eine Allowlist, die diese Pfade ausschließt.
