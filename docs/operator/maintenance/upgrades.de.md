---
title: Das Register aktualisieren
---

# Das Register aktualisieren

Diese Seite behandelt das Aktualisieren des Backends, der Frontends und der Smart Contracts. Befolgen Sie die Verfahren der Reihe nach – aktualisieren Sie Verträge niemals, bevor Sie das Backend aktualisiert haben.

## Backend-Upgrade

### 1. Die neuesten Änderungen holen

```bash
git fetch origin
git pull origin main
git submodule update --recursive
```

### 2. Das Änderungsprotokoll prüfen

Prüfen Sie vorab die Commits und Konfigurationsänderungen zwischen dem aktuell eingesetzten und dem Ziel-Tag.

### 3. Das neue Backend-Image bauen

```bash
cd backend
docker build -t registerwerk-backend:latest .
```

Oder aus der Container-Registry ziehen:

```bash
docker pull ghcr.io/ewpg/registerwerk-backend:latest
```

### 4. Das Upgrade anwenden

```bash
# Stop the backend gracefully (drains in-flight requests)
docker compose stop backend

# Start the new version — Flyway runs migrations automatically on startup
docker compose up -d backend

# Verify health
docker compose logs -f backend | grep -E "Started|ERROR"
curl http://localhost:48080/actuator/health
```

!!! warning
    Datenbankmigrationen laufen beim Start automatisch. Schlägt eine Migration fehl, startet das Backend nicht. Prüfen Sie die Logs auf den konkreten Migrationsfehler. Ändern Sie die Flyway-Verlaufstabelle niemals manuell.

### 5. Prüfen

Nach dem Start:
- Prüfen Sie die API unter `http://localhost:48080/swagger-ui.html`
- Führen Sie einen Test-API-Aufruf gegen einen kritischen Endpunkt aus
- Beobachten Sie das Audit-Log in den ersten 15 Minuten auf unerwartete Fehler

## Frontend-Upgrade

```bash
# Operator frontend
cd frontend-operator
npm install
ng build --configuration production
docker compose up -d --build frontend-operator

# Customer frontend
cd ../frontend-customer
npm install
ng build --configuration production
docker compose up -d --build frontend-customer
```

Frontends sind zustandslos – Upgrades erfolgen ohne Ausfallzeit.

## Smart-Contract-Upgrades

!!! warning
    Smart-Contract-Upgrades sind die sensibelsten Operationen. Alle Verträge durchlaufen vor jedem Mainnet-Upgrade eine Testnet-Bereitstellung und eine Prüfung. Aktualisieren Sie Mainnet-Verträge niemals, ohne zuvor die Testnet-Validierung abgeschlossen zu haben.

### Aktualisierbare vs. nicht aktualisierbare Verträge

| Vertrag | Aktualisierbar | Upgrade-Pfad |
|----------|------------|-------------|
| `AssetTokenFactory` | Nein (CREATE2-Factory) | Neue Factory bereitstellen, Backend-Konfiguration aktualisieren |
| `EwpgTREXFactory` | Nein | Neue Factory bereitstellen |
| `IdentityRegistryStorage` | Ja (UUPS-Proxy) | Proxy-Implementierung aktualisieren |
| `ModularCompliance` | Ja (UUPS-Proxy) | Proxy-Implementierung aktualisieren |
| Token-Verträge (je Emission) | Nein | Nach der Bereitstellung nicht mehr aktualisierbar |

### Einen UUPS-Proxy-Vertrag aktualisieren

```bash
cd contracts
forge script script/UpgradeCompliance.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast \
  --verify \
  --slow
```

Das Upgrade-Skript:
1. Stellt den neuen Implementierungsvertrag bereit
2. Ruft `upgradeToAndCall` auf dem UUPS-Proxy auf
3. Prüft, dass die neue Implementierung aktiv ist

### Compliance-Module aktualisieren

Compliance-Module können hinzugefügt, entfernt oder ersetzt werden, ohne den Token-Vertrag selbst zu aktualisieren. Das ist der bevorzugte Upgrade-Pfad für Änderungen der Compliance-Logik.

```bash
# Add a new compliance module to a token
curl -X POST http://localhost:48080/api/v1/admin/tokens/{tokenAddress}/compliance/modules \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  -H "Content-Type: application/json" \
  -d '{"moduleAddress": "0xNewModuleAddress", "chain": "mainnet"}'
```

## Rollback-Verfahren

Verursacht ein Upgrade Probleme, führen Sie ein Rollback durch, indem Sie zum vorherigen Docker-Image-Tag zurückkehren:

```bash
# Backend rollback
docker compose stop backend
docker tag ghcr.io/ewpg/registerwerk-backend:previous \
  registerwerk-backend:latest
docker compose up -d backend
```

Die Flyway-Migrationen in diesem Repository haben keine automatischen Down-Skripte. Bei einer Schemaänderung stellen Sie die Datenbanksicherung vor dem Upgrade zusammen mit dem vorherigen Anwendungsimage wieder her oder spielen eine geprüfte Vorwärtskorrektur ein.

## Kong-Upgrade

1. Aktualisieren Sie das `kong`-Image-Tag in `docker-compose.yml` (und in `gateway/docker-compose.kong.yml`, falls Sie den eigenständigen Gateway-Stack nutzen).
2. Ziehen Sie das Image und validieren Sie die deklarative Konfiguration vor dem Umschalten gegen die neue Version:

```bash
docker compose pull kong
docker compose run --rm kong kong config parse /etc/kong/kong.yml
docker compose up -d --force-recreate kong
```

Kong liest `gateway/kong.yml` beim Start neu ein: Im DB-losen Modus sind keine Migrationen auszuführen und keine Konfiguration erneut anzuwenden. Unter Kubernetes ist die Version des Kong-Subcharts in `deploy/helm/registerwerk/Chart.yaml` festgelegt; aktualisieren Sie sie dort und führen Sie `helm upgrade` aus.

## Subgraph-Upgrades

Ändert sich das Subgraph-Schema, bewahren Sie die vorherige Konfiguration und stellen Sie eine neue Version bereit.
Prüfen Sie vor der Bereitstellung, dass jede Singleton-Adresse ihren eigenen tatsächlichen `*_START_BLOCK_<SUFFIX>` hat und
jeder BondDesk-, AMM- und RepoVault-Eintrag `address@deploymentBlock` verwendet. Ein Factory-Block ist kein gültiger
Ersatz für die Bereitstellungsblöcke der anderen Quellen.

Rendern und kompilieren Sie alle konfigurierten Ziele, ohne zunächst zu veröffentlichen:

```bash
SUBGRAPH_VALIDATE_ONLY=true ./indexer/evm/deploy-subgraph.sh all
```

Stellen Sie dann mit einer Versionsbezeichnung bereit, die für die betroffenen Graph-Namen noch nie verwendet wurde:

```bash
SUBGRAPH_VERSION_LABEL=schema-20260729-01 ./indexer/evm/deploy-subgraph.sh all
```

graph-node reindiziert jede gerenderte Quelle ab dem für diese Quelle konfigurierten Block. Halten Sie die vorherigen
Versionen und deren Konfiguration für ein zerstörungsfreies Rollback verfügbar, bis jeder Ersatz
den Chain-Head erreicht hat und seine Ereignisbereiche unabhängig abgeglichen wurden. Entfernen Sie nicht
den vorherigen Subgraph vor der Validierung; Rollback bedeutet, das zuvor genehmigte Manifest
und die Quellkonfiguration unter einer weiteren neuen Versionsbezeichnung erneut bereitzustellen.

## Abhängigkeits-Updates

- **Java / Spring Boot**: `pom.xml` aktualisieren, `mvn verify` ausführen
- **Angular**: `ng update @angular/core @angular/cli`
- **Verträge**: `forge update` (aktualisiert Git-Submodule in `contracts/lib/`)
