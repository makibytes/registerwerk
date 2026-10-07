---
title: API Gateway (Kong)
---

# API Gateway (Kong)

Kong 3.9 (OSS, DB-los) steht ausschließlich vor dem **API-Datenverkehr des Kunden-Frontends**. Es übernimmt
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

`gateway/kong.yml` ist die einzige maßgebliche Quelle. Die Datei wird schreibgeschützt unter `/etc/kong/kong.yml` eingebunden und beim Start über `KONG_DECLARATIVE_CONFIG` geladen. Kong läuft DB-los, es gibt also weder eine Datenbank noch `deck sync` (deck schreibt in eine datenbankgestützte Admin-API, und dieser Stack veröffentlicht keine). Um Routing oder Plugins zu ändern, bearbeiten Sie die Datei, validieren sie und erzeugen den Container neu:

```bash
docker compose run --rm kong kong config parse /etc/kong/kong.yml   # muss „parse successful" ausgeben
docker compose up -d --force-recreate kong
```

Unter Kubernetes bearbeiten Sie `deploy/helm/registerwerk/files/kong.yml` (wird in die ConfigMap `registerwerk-kong-config` gerendert) und führen `helm upgrade` aus; starten Sie das Kong-Deployment neu, falls die Pods die geänderte ConfigMap nicht übernehmen.

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

Kong läuft DB-los und bringt in diesem Stack **keine Admin-Oberfläche** mit (kein Konga, kein Kong Manager). Die Admin-API lauscht nur auf `127.0.0.1:8001` **innerhalb des Containers** (`KONG_ADMIN_LISTEN`) und wird nicht auf dem Host veröffentlicht; sie ist nicht authentifiziert und darf nie offengelegt werden. Das Kong-Image enthält kein `curl`, nutzen Sie daher die mitgelieferte CLI:

```bash
docker compose exec kong kong health
```

Um Routing oder Plugins zu ändern, bearbeiten Sie `gateway/kong.yml` und erzeugen den Dienst `kong` wie oben beschrieben neu — die Datei ist im DB-losen Modus die einzige maßgebliche Quelle.
