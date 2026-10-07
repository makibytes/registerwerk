---
title: API Gateway (Kong)
---

# API Gateway (Kong)

Kong 3.8 (OSS, DB-los) steht ausschließlich vor dem **API-Datenverkehr des Kunden-Frontends**. Es übernimmt
Ratenbegrenzung, Antwort-Caching und Sicherheitsheader. Es steht **nicht** vor der Benutzeroberfläche
eines der beiden Frontends – beide Apps werden vom Browser immer direkt an ihrem eigenen Port (`:44200`, `:44201`) geöffnet – und
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
| `rate-limiting` | 300 Anfragen/Minute, 10.000/Stunde pro Client-IP (Redis-gestützt, über Kong-Replikas geteilt) |
| `bot-detection` | Blockiert gängige Crawler-/Scanner-User-Agents |
| `ip-restriction` | Beschränkt `/api/v1/admin/**` auf Betreibernetzwerk-CIDRs, geprüft gegen die echte Client-IP |
| `cors` | Cross-Origin-Header für das Angular-Frontend des Kunden |
| `request-size-limiting` | 20 MB maximale Anfragegröße |
| `response-transformer` | Fügt Standard-Sicherheitsheader hinzu (HSTS, CSP, X-Frame-Options, …) |

`openid-connect` (JWT-Terminierung am Gateway) ist **nur mit Kong Enterprise/Konnect verfügbar** und in
diesem OSS-Setup nicht aktiv – für Bereitstellungen, die Kong Enterprise betreiben, liegt ein
fertig zusammenführbares Snippet unter `gateway/plugins/oidc-entra.yml`. Ohne dieses erfolgen JWT-Validierung
und Entitäts-/Rollenextraktion vollständig im Spring-Backend, das die Claims direkt aus dem Token liest – Kong
fügt hier niemals `X-Entity-Id`/`X-Entity-Roles`-Header ein.

## Client-IP-Behandlung

Rate Limiting, die Admin-`ip-restriction` und die Login-Drosselung des Backends hängen von der echten Client-Adresse ab; ein vom Client gesetztes `X-Forwarded-For` wird nie geglaubt:

- Die nginx-Instanzen **überschreiben** `X-Forwarded-For` mit der gesehenen TCP-Gegenstelle (nie anhängen). Hinter dem Helm-Ingress stellt nginx die echte Adresse vorher über `ingress.trustedCidrs` wieder her.
- Kong vertraut `X-Forwarded-For` nur aus dem nginx-/Ingress-Netz (`KONG_TRUSTED_IPS`, `KONG_REAL_IP_HEADER=X-Forwarded-For`, `KONG_REAL_IP_RECURSIVE=off`; im Chart `kong.env.trusted_ips`).
- Das Backend vertraut dem Header nur von `REGISTERWERK_AUTH_TRUSTED_PROXIES` (Compose setzt einen lokalen Demo-Standard; das Chart hat keinen und rendert erst, wenn nur die Kong- und Operator-nginx-Pods genannt sind, nie ein ganzer privater Bereich).
- Die Compose-Admin-Allowlist enthält `192.168.0.0/16` und `::1` für die lokale Demo. In Helm wird sie aus `kong.adminAllowCidrs` gerendert (Pflicht, kein Default).

`scripts/check-client-ip.sh` prüft gegen einen laufenden Stack, dass gefälschte `X-Forwarded-For`-Werte den Rate-Limit-Zähler nicht zurücksetzen. Der Helm-API-Ingress zeigt auf Kong, nie auf das Backend, und beantwortet `/actuator/*` außer Health mit 404.

## Kong-Admin-API

Kong läuft DB-los und liefert in diesem Stack **keine Admin-GUI** (kein Konga, kein Kong Manager – beide wurden
entfernt bzw. nie verkabelt). Der Zugriff auf die Admin-API ist bewusst auf Loopback beschränkt:

```bash
# Bound to 127.0.0.1:48001 on the host — never expose this publicly, it's unauthenticated
docker compose exec kong kong health
curl http://127.0.0.1:48001/status
```

Um Routing/Plugins zu ändern, bearbeiten Sie `gateway/kong.yml` und starten Sie den Dienst `kong` neu – das ist im DB-losen Modus die einzige verbindliche Quelle.
