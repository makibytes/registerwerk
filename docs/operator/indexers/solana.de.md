---
title: Solana (Yellowstone gRPC)
---

# Solana-Indexer – Yellowstone gRPC

Der Solana-Indexer verwendet **Yellowstone**, ein Geyser-Plugin, das Kontoaktualisierungen und Transaktionen in Echtzeit über gRPC streamt. Ein Polling-Fallback deckt Zeiträume ab, in denen der gRPC-Stream nicht verfügbar ist.

## Architektur

```
Solana Validator (with Yellowstone Geyser plugin)
        |
        | (gRPC stream)
        v
  indexer/solana (Node.js service)
        |
        | (REST / WebSocket)
        v
  Backend Spring Boot
```

## Voraussetzungen

- Ein Solana-RPC-Endpunkt mit **Yellowstone-gRPC**-Unterstützung
  - Gehostete Optionen: [Helius](https://helius.dev), [Triton](https://triton.one)
  - Selbstgehostet: erfordert einen Solana-Validator mit installiertem Yellowstone-Plugin

## Konfiguration

`indexer/solana/yellowstone.yaml`:

```yaml
endpoint: "${SOLANA_YELLOWSTONE_ENDPOINT}"
x_token: "${SOLANA_YELLOWSTONE_TOKEN}"

subscriptions:
  - type: transaction
    accountInclude:
      - "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"  # SPL Token program
    commitment: confirmed

polling:
  enabled: true
  fallback_rpc: "${SOLANA_MAINNET_RPC_URL}"
  poll_interval_seconds: 2
  max_blocks_per_poll: 10
```

Gesetzt in `.env`:

```bash
SOLANA_YELLOWSTONE_ENDPOINT=https://your-yellowstone-endpoint.helius.xyz:2083
SOLANA_YELLOWSTONE_TOKEN=your_api_token
SOLANA_MAINNET_RPC_URL=https://mainnet.helius-rpc.com/?api-key=YOUR_KEY
SOLANA_DEVNET_RPC_URL=https://devnet.helius-rpc.com/?api-key=YOUR_KEY
```

## Den Solana-Indexer starten

```bash
docker compose -f indexer/solana/docker-compose.yml up -d
```

Oder für die lokale Entwicklung:

```bash
cd indexer/solana && npm install
npm start
```

## SPL-Token-Tracking

Der Solana-Indexer verfolgt SPL-Token-Programmereignisse:
- `Transfer` – Token-Übertragungen zwischen Konten
- `MintTo` – Token-Minting
- `Burn` – Token-Vernichtung (Burning)

Er filtert Ereignisse auf solche, die im Backend registrierte Token-Mint-Adressen betreffen. Nicht damit zusammenhängende SPL-Token-Aktivität wird ignoriert.

## Polling-Fallback

Bricht der Yellowstone-gRPC-Stream ab oder wird er nicht mehr verfügbar, wechselt der Indexer automatisch in den Polling-Modus:

1. Fragt `getSignaturesForAddress` für das SPL-Token-Programm im konfigurierten Intervall ab
2. Ruft für jede neue Signatur die vollständigen Transaktionsdetails ab
3. Analysiert Token-Ereignisse aus den Transaktionsprotokollen
4. Wechselt zurück zu gRPC, sobald der Stream sich neu verbindet

Der Polling-Fallback ist weniger effizient, stellt aber sicher, dass bei Ausfällen des Anbieters keine Ereignisse verloren gehen.

## Überwachung

Der Solana-Indexer stellt einen Health-Endpunkt bereit:

```bash
curl http://localhost:3001/health
```

Antwort:

```json
{
  "status": "healthy",
  "mode": "grpc",
  "latestSlot": 285614923,
  "lastEventAt": "2025-04-06T12:00:00Z"
}
```

Zeigt `mode` `polling` an, ist der gRPC-Stream ausgefallen und der Fallback aktiv. Untersuchen Sie den Yellowstone-Endpunkt.

## Solana Devnet hinzufügen

Der Devnet-Indexer läuft als separate Instanz:

```bash
SOLANA_NETWORK=devnet \
  SOLANA_YELLOWSTONE_ENDPOINT=https://devnet-yellowstone.example.com:2083 \
  SOLANA_YELLOWSTONE_TOKEN=your_devnet_token \
  SOLANA_DEVNET_RPC_URL=https://api.devnet.solana.com \
  npm start
```

Oder fügen Sie in der Docker-Compose-Datei einen zweiten Dienst mit `SOLANA_NETWORK=devnet` hinzu.

# Solana – Yellowstone-gRPC-Indexer

Solana-Übertragungen werden über [Yellowstone Dragon's Mouth](https://github.com/rpcpool/yellowstone-grpc) überwacht, einen gRPC-Proxy für Solanas Geyser-Plugin-Interface.

## Yellowstone starten

```bash
docker compose -f indexer/solana/docker-compose.yml up -d
```

Setzen Sie `YELLOWSTONE_UPSTREAM_ENDPOINT` auf einen Geyser-fähigen Endpunkt:
- [Helius](https://helius.dev)
- [Triton One](https://triton.one)
- Ein selbstgehosteter Solana-Validator mit dem Yellowstone-Plugin

## Wie es funktioniert

`SolanaTransferSyncService` im Backend:
1. Öffnet beim Start ein gRPC-Abonnement bei Yellowstone (`@PostConstruct`)
2. Filtert Transaktionen, an denen eine bekannte SPL-Mint-Adresse beteiligt ist
3. Parst Übertragungen und upsertet sie in `token_transfer`

Bricht der gRPC-Stream ab, verbindet sich der Dienst automatisch neu.

## Polling-Fallback

Ein separater `@Scheduled`-Job läuft alle 10 Minuten:
- Ruft `getSignaturesForAddress` für jede bekannte SPL-Mint auf
- Füllt Lücken, die durch Stream-Ausfallzeiten entstanden sind
- Deduplizierung über eine UNIQUE-Einschränkung verhindert Doppelzählung

## Einen neuen SPL-Token registrieren

Wird über die API eine Solana-Asset-Bereitstellung angelegt, beginnt das Backend automatisch mit der Überwachung von deren Mint-Adresse. Keine manuelle Konfiguration nötig.
