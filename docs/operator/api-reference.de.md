---
title: API Referenz
---

# API Referenz

Das eWpG-Register stellt eine REST-API für alle Registrierungsvorgänge bereit. Diese Seite bietet einen Überblick über die API-Struktur, die Authentifizierung und Links zur interaktiven Live-Dokumentation.

## Interaktive Dokumentation

Die Swagger-Benutzeroberfläche ist verfügbar unter:

```
http://localhost:48080/swagger-ui.html
```

Für die Produktion:

```
https://api.registerwerk.example.com/swagger-ui.html
```

Die vollständige OpenAPI-3-Spezifikation (JSON) ist verfügbar unter:

```
http://localhost:48080/v3/api-docs
```

## Authentifizierung

Alle API-Endpunkte (außer `/api/v1/public/**`) erfordern ein Bearer-JWT-Token:

```bash
curl https://api.registerwerk.example.com/api/v1/issuances \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIs..."
```

Siehe [Authentifizierung](../customer/authentication.md) zum Erhalt eines Tokens.

## API-Gruppen

### Öffentliche Endpunkte (`/api/v1/public/`)

Keine Authentifizierung erforderlich.

| Methode | Pfad | Beschreibung |
|--------|------|-------------|
| `GET` | `/api/v1/public/chains` | Alle aktivierten Chains auflisten |
| `GET` | `/api/v1/public/health` | Einfache Gesundheitsprüfung |

### Kundenendpunkte (`/api/v1/`)

Erfordern Authentifizierung. Antworten sind auf die authentifizierte Entität beschränkt.

| Methode | Pfad | Beschreibung |
|--------|------|-------------|
| `GET` | `/api/v1/issuances` | Emissionen Ihrer Entität auflisten |
| `POST` | `/api/v1/issuances` | Neue Emission erstellen |
| `GET` | `/api/v1/issuances/{id}` | Emissionsdetails abrufen |
| `PUT` | `/api/v1/issuances/{id}` | Emission aktualisieren (nur DRAFT) |
| `POST` | `/api/v1/issuances/{id}/submit` | Zur Genehmigung einreichen |
| `POST` | `/api/v1/issuances/{id}/deploy` | In der Blockchain bereitstellen |
| `POST` | `/api/v1/issuances/{id}/suspend` | Token aussetzen |
| `POST` | `/api/v1/issuances/{id}/redeem` | Als eingelöst markieren |
| `GET` | `/api/v1/issuances/{id}/investors` | Anleger auflisten |
| `POST` | `/api/v1/issuances/{id}/investors` | Anleger hinzufügen |
| `DELETE` | `/api/v1/issuances/{id}/investors/{investorId}` | Anleger entfernen |
| `POST` | `/api/v1/issuances/{id}/investors/{investorId}/whitelist` | Wallet on-chain whitelisten |
| `GET` | `/api/v1/investments` | Token-Bestände auflisten (Investor) |
| `GET` | `/api/v1/transfers` | Übertragungen Ihrer Entität auflisten |
| `GET` | `/api/v1/audit-log` | Audit-Log (auf Ihre Entität beschränkt) |
| `GET` | `/api/v1/profile` | Ihr Entitätsprofil |
| `POST` | `/api/v1/wallets` | Wallet registrieren |
| `DELETE` | `/api/v1/wallets/{address}` | Wallet entfernen |

### Admin-Endpunkte (`/api/v1/admin/`)

Erfordern die Rolle `REGISTRY_ADMIN`.

| Methode | Pfad | Beschreibung |
|--------|------|-------------|
| `GET` | `/api/v1/admin/entities` | Alle Entitäten auflisten |
| `POST` | `/api/v1/admin/entities` | Entität anlegen + Einladung senden |
| `PATCH` | `/api/v1/admin/entities/{id}/status` | Entitätsstatus aktualisieren |
| `GET` | `/api/v1/admin/kyc` | Ausstehende KYC-Prüfungen auflisten |
| `POST` | `/api/v1/admin/kyc/{id}/approve` | KYC genehmigen |
| `POST` | `/api/v1/admin/kyc/{id}/reject` | KYC ablehnen |
| `POST` | `/api/v1/admin/issuances/{id}/approve` | Emission genehmigen |
| `POST` | `/api/v1/admin/issuances/{id}/reject` | Emission ablehnen |
| `GET` | `/api/v1/admin/chains` | Alle Chains auflisten |
| `POST` | `/api/v1/admin/chains` | Chain hinzufügen |
| `PATCH` | `/api/v1/admin/chains/{chainId}` | Chain-Konfiguration aktualisieren |
| `POST` | `/api/v1/admin/chains/refresh` | Chain-Clients neu laden |
| `GET` | `/api/v1/admin/audit-log` | Vollständiges Audit-Log (alle Entitäten) |

## Fehlerantworten

Alle Fehler folgen einem Standardformat:

```json
{
  "timestamp": "2025-04-06T12:00:00Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "code": "ISSUANCE_INVALID_STATE",
  "message": "Cannot submit issuance in state ISSUED",
  "path": "/api/v1/issuances/abc123/submit"
}
```

Häufige Fehlercodes:

| Code | HTTP | Beschreibung |
|------|------|-------------|
| `UNAUTHORIZED` | 401 | Fehlendes oder ungültiges JWT |
| `FORBIDDEN` | 403 | Unzureichende Rolle für diesen Vorgang |
| `NOT_FOUND` | 404 | Ressource existiert nicht |
| `ISSUANCE_INVALID_STATE` | 422 | Zustandsübergang nicht zulässig |
| `BLOCKCHAIN_ERROR` | 502 | RPC-Aufruf an die Chain fehlgeschlagen |
| `INDEXER_UNAVAILABLE` | 503 | Graph Node nicht erreichbar |

## Ratenbegrenzung

API-Aufrufe sind am Kong-Gateway ratenbegrenzt:

- 300 Anfragen/Minute pro authentifiziertem Consumer
- 10 Anfragen/Minute für authentifizierungsbezogene Endpunkte

Rate-Limit-Header sind in den Antworten enthalten:

```
X-RateLimit-Limit-Minute: 300
X-RateLimit-Remaining-Minute: 287
```

# API Referenz

Die vollständige OpenAPI-Spezifikation ist verfügbar unter:

```
http://localhost:48080/v3/api-docs
http://localhost:48080/swagger-ui.html
```

## Wichtigste Endpunkte

### Entitäten
| Methode | Pfad | Beschreibung |
|---|---|---|
| `GET` | `/api/v1/entities` | Alle Entitäten auflisten |
| `POST` | `/api/v1/entities` | Entität erstellen |
| `GET` | `/api/v1/entities/{id}` | Entität abrufen |
| `PUT` | `/api/v1/entities/{id}` | Entität aktualisieren |
| `GET` | `/api/v1/entities/{id}/kyc/documents` | KYC-Dokumente auflisten |
| `POST` | `/api/v1/entities/{id}/kyc/documents` | KYC-Dokument hochladen |

### Assets
| Methode | Pfad | Beschreibung |
|---|---|---|
| `GET` | `/api/v1/assets` | Alle Assets auflisten |
| `POST` | `/api/v1/assets` | Asset erstellen |
| `GET` | `/api/v1/assets/{id}` | Asset abrufen |
| `POST` | `/api/v1/assets/{id}/deployments` | In der Chain bereitstellen |
| `GET` | `/api/v1/assets/{id}/deployments/{depId}/history` | Übertragungsverlauf |
| `GET` | `/api/v1/assets/{id}/holders` | Inhaber auflisten |

### ERC-3643
| Methode | Pfad | Beschreibung |
|---|---|---|
| `GET` | `/api/v1/assets/{id}/deployments/{depId}/erc3643` | T-REX-Suite abrufen |
| `POST` | `/api/v1/assets/{id}/deployments/{depId}/erc3643/compliance-modules` | Modul hinzufügen |
| `POST` | `/api/v1/assets/{id}/deployments/{depId}/erc3643/trusted-issuers` | Aussteller hinzufügen |

### ONCHAINID
| Methode | Pfad | Beschreibung |
|---|---|---|
| `GET` | `/api/v1/identities` | Identitäten auflisten |
| `POST` | `/api/v1/identities` | ONCHAINID erstellen |
| `POST` | `/api/v1/identities/{id}/claims` | KYC-Claim ausstellen |
| `DELETE` | `/api/v1/identities/{id}/claims/{claimId}` | Claim widerrufen |

### Admin
| Methode | Pfad | Beschreibung |
|---|---|---|
| `GET` | `/api/v1/admin/chains` | Chain-Konfigurationen auflisten |
| `POST` | `/api/v1/admin/chains` | Chain hinzufügen |
| `PUT` | `/api/v1/admin/chains/{id}` | Chain aktualisieren |
| `POST` | `/api/v1/admin/chains/refresh` | Web3j-Clients neu laden |
| `GET` | `/api/v1/audit` | Audit-Log abfragen |

### Öffentlich (keine Authentifizierung)
| Methode | Pfad | Beschreibung |
|---|---|---|
| `GET` | `/api/v1/public/assets/by-address/{address}` | Token nachschlagen |
| `GET` | `/api/v1/public/chains` | Aktive Chains auflisten |
| `GET` | `/api/v1/onboarding/token-info/{token}` | Onboarding-Token validieren |
| `POST` | `/api/v1/onboarding/complete` | Onboarding mit Token abschließen |
