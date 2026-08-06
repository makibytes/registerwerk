---
title: API Gateway (Kong)
---

# API Gateway (Kong)

Kong 3.8 (OSS, DB-los) steht ausschließlich vor dem **API-Datenverkehr des Kunden-Frontends**. Es übernimmt
Ratenbegrenzung, Antwort-Caching und Sicherheitsheader. Es steht **nicht** vor der Benutzeroberfläche
eines der beiden Frontends – beide Apps werden vom Browser immer direkt an ihrem eigenen Port (`:4200`, `:4201`) geöffnet – und
das **Operator-Frontend umgeht Kong vollständig**, selbst für seine eigenen API-Aufrufe (sein nginx leitet
`/api/` direkt an `backend:8080` weiter). JWT-Validierung und Entitäts-/Rollenextraktion erfolgen immer im
Spring-Backend selbst, aus den eigenen Claims des Tokens – nicht über einen von Kong
eingefügten Header, im OSS-Setup, das dieses Repository ausliefert.

## Kong starten

```bash
docker compose up -d kong
```

Kong läuft im DB-losen (deklarativen) Modus – es liest `gateway/kong.yml` direkt über
`KONG_DECLARATIVE_CONFIG` und benötigt keine eigene Datenbank.

## Deklarative Konfiguration

Kong wird über `gateway/kong.yml` im deck-Format konfiguriert. So wenden Sie Änderungen an:

```bash
deck sync --config gateway/kong.yml
```

## Wichtige Plugins

Standardmäßig sind nur gebündelte Kong-OSS-Plugins aktiv (siehe `gateway/kong.yml`):

| Plugin | Zweck |
|---|---|
| `proxy-cache` | Speichert GET-200-Antworten öffentlicher Routen 30–60 Sekunden lang zwischen |
| `request-transformer` | Entfernt vom Client mitgelieferte `X-Entity-Id`/`X-Entity-Roles` auf öffentlichen Routen, sodass nichts eingeschmuggelt werden kann, bevor das Backend die Anfrage überhaupt sieht |
| `rate-limiting` | 300 Anfragen/Minute, 10.000/Stunde pro Consumer |
| `bot-detection` | Blockiert gängige Crawler-/Scanner-User-Agents |
| `ip-restriction` | Beschränkt `/api/v1/admin/**` auf Betreibernetzwerk-CIDRs |
| `cors` | Cross-Origin-Header für das Angular-Frontend des Kunden |
| `request-size-limiting` | 20 MB maximale Anfragegröße |
| `response-transformer` | Fügt Standard-Sicherheitsheader hinzu (HSTS, CSP, X-Frame-Options, …) |

`openid-connect` (JWT-Terminierung am Gateway) ist **nur mit Kong Enterprise/Konnect verfügbar** und in
diesem OSS-Setup nicht aktiv – für Bereitstellungen, die Kong Enterprise betreiben, liegt ein
fertig zusammenführbares Snippet unter `gateway/plugins/oidc-entra.yml`. Ohne dieses erfolgen JWT-Validierung
und Entitäts-/Rollenextraktion vollständig im Spring-Backend, das die Claims direkt aus dem Token liest – Kong
fügt hier niemals `X-Entity-Id`/`X-Entity-Roles`-Header ein.

## Kong-Admin-API

Kong läuft DB-los und liefert in diesem Stack **keine Admin-GUI** (kein Konga, kein Kong Manager – beide wurden
entfernt bzw. nie verkabelt). Der Zugriff auf die Admin-API ist bewusst auf Loopback beschränkt:

```bash
# Bound to 127.0.0.1:8001 on the host — never expose this publicly, it's unauthenticated
docker compose exec kong kong health
curl http://127.0.0.1:8001/status
```

Um Routing/Plugins zu ändern, bearbeiten Sie `gateway/kong.yml` und starten Sie den Dienst `kong` neu – das ist im DB-losen Modus die einzige verbindliche Quelle.
