---
title: Neue Ketten hinzufügen
---

# Neue Ketten hinzufügen

Backend-Chain-Clients können zur Laufzeit registriert werden. Die EVM-Indizierung erfordert außerdem eine Graph-Node-Netzwerkkonfiguration
und ein unterstütztes Bereitstellungsziel mit expliziten Vertragsquellen.

## Unterstützte Chain-Typen

| Typ | Beispiele |
|---|---|
| `EVM` | Ethereum, Polygon, Base, Arbitrum, Fhenix, Inco, jede EVM-kompatible Chain |
| `SOLANA` | Solana Mainnet, Devnet |

## Eine EVM-Chain hinzufügen (vollständige Anleitung)

### 1. Über die Admin-API registrieren

```bash
curl -X POST http://localhost:48000/api/v1/admin/chains \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "identifier": "OPTIMISM_MAINNET",
    "displayName": "Optimism",
    "chainType": "EVM",
    "networkType": "MAINNET",
    "chainId": 10,
    "rpcUrl": "https://mainnet.optimism.io",
    "wsUrl": "wss://mainnet.optimism.io",
    "blockExplorerUrl": "https://optimistic.etherscan.io",
    "graphNodeUrl": "http://graph-node:8000/subgraphs/name",
    "graphSubgraphName": "ewpg/optimism-mainnet"
  }'
```

### 2. Verträge bereitstellen

```bash
forge script script/Deploy.s.sol \
  --rpc-url https://mainnet.optimism.io \
  --broadcast
```

### 3. Zur Graph-Node-Konfiguration hinzufügen

Die TOML- und docker-compose-Änderungen finden Sie unter [Indexer-Konfiguration](../configuration/indexers.md).

### 4. Graph-Node mit dem neuen Netzwerk neu starten

Die Deployment-Admin-API kann für das neue Netzwerk erst ein Manifest entgegennehmen, wenn Graph-Node
seine Kettenkonfiguration neu geladen hat:

```bash
docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
```

Prüfen Sie, ob Graph-Node fehlerfrei läuft, bevor Sie fortfahren.

### 5. Subgraph konfigurieren und bereitstellen

Konfigurieren Sie jede unter [The Graph](../indexers/the-graph.md) beschriebene `*_OPTIMISM`-Quelle, dann:

```bash
SUBGRAPH_VERSION_LABEL=optimism-20260729-01 ./indexer/evm/deploy-subgraph.sh optimism
```

Der Subgraph ist eine vorläufige Ereignisprojektion. Er begründet weder Chain-Finalität, rechtliche
Wirkung, maßgeblichen Registerstatus noch die Identität des bereitgestellten Codes.

### 6. Client-Aktualisierung auslösen

```bash
curl -X POST http://localhost:48000/api/v1/admin/chains/refresh \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

`BlockchainClientRegistry` erstellt sofort einen neuen Web3j-Client für die Chain.

## Fallback-RPCs

Sie können mehrere RPC-URLs für Failover konfigurieren. Die Chain-Konfiguration speichert `fallback_rpc_urls` als durch Kommas getrennte Liste. Fällt der primäre RPC aus, versucht die Registrierung die Fallbacks der Reihe nach.

```json
{
  "rpcUrl": "https://mainnet.optimism.io",
  "fallbackRpcUrls": "https://optimism.publicnode.com,https://rpc.ankr.com/optimism"
}
```

## Governance und Vertrauen bei RPC-Knoten

Welchem RPC-Knoten die Registry zuhört, bestimmt, welchen Kettenzustand sie glaubt. Knoten werden deshalb wie Schlüssel gesteuert:

- **Step-up und zweiter Freigeber.** Hinzufügen, Ändern, Aktivieren, Deaktivieren, Festpinnen (`exclusive`) und Löschen eines Knotens (`/api/v1/admin/chains/{chainId}/nodes...`) sowie das Zurücksetzen des Genesis-Pins erfordern ein Step-up-Token und einen zweiten Freigeber (`X-Dual-Control-Token`, Grund `RPC_NODE_CHANGE`). Das Audit-Ereignis `RPC_NODE_*` hält den handelnden Operator, den zweiten Freigeber sowie die alte und neue URL fest; Geheimnisse in URLs (Userinfo, Query-Werte, schlüsselartige Pfadsegmente) werden geschwärzt.
- **Nur https.** Knoten-URLs müssen `https`/`wss` verwenden. Klartext-`http`/`ws` ist nur für Loopback- oder private Hosts und nur mit `registerwerk.rpc.allow-insecure-private=true` (Demo-Umgebungen) zulässig. `chain_config.rpc_allowed_hosts` (kommagetrennt, `*.example.org` möglich) beschränkt optional die Hosts einer Kette. Die Regeln gelten beim Hinzufügen und bei einer URL-Änderung.
- **Ketten-Identität.** Beim Hinzufügen und in jeder Health-Runde muss `eth_chainId` der fixierten `chain_config.chain_id` und der Genesis-Blockhash `chain_config.genesis_hash` entsprechen (Solana: `getGenesisHash`). Der erste passende Knoten fixiert den Genesis-Hash. Ein abweichender Knoten wird beim Hinzufügen abgelehnt; später wird er mit dem Grund `CHAIN_MISMATCH` als nicht gesund markiert und nie angesteuert, auch nicht als letzter Ausweg. Nach einem legitimen Devnet-Reset wird der Pin mit `POST /api/v1/admin/chains/{chainId}/nodes/genesis-pin/reset` gelöscht (Step-up und zweiter Freigeber); die nächste Health-Runde fixiert den neuen Genesis.
- **Bester Block ist ein Median.** Der Rückstand wird gegen die Median-Höhe der Routing-Kandidaten gemessen (bei zweien der niedrigere), nicht gegen das Maximum. Ein Knoten, der um mehr als `registerwerk.rpc.max-plausible-jump-blocks` (Standard 1000) vor dieser Referenz liegt, wird unter Quarantäne gestellt (`IMPLAUSIBLE_HEIGHT`).
- **Schneller Failover.** Ein Knoten ist nach der ersten fehlgeschlagenen Prüfung nicht mehr gesund und braucht zwei aufeinanderfolgende erfolgreiche Prüfungen zur Rückkehr (`health_reason` `RECOVERING`). Bei zwei oder mehr routbaren Knoten werden Lesezugriffe mit Transportfehler im selben Aufruf auf dem nächstbesten Knoten wiederholt (höchstens drei Versuche); der fehlerhafte Knoten steht 30 Sekunden lang zuletzt. Transaktionen werden nur an einen Knoten gesendet. Nachdem ein Knoten einen Broadcast angenommen hat, bevorzugen Lesezugriffe zwei Minuten lang diesen Knoten (Read-your-writes). Mehrstufige Lending-Lesezugriffe (Quotes, Positionsaktualisierung) laufen auf einer Blocknummer, und ein Lending-Health-Factor gilt nur als verlässlich, wenn der genutzte Knoten höchstens einen Block zurückliegt.
- **Zweitquellen-Bestätigung.** Bevor eine registerändernde Transaktion abgeschlossen wird, müssen mindestens zwei gesunde Knoten dem Receipt zustimmen: dieselbe Transaktion (Blockhash, Ziel-Contract und Absender per `eth_getTransactionByHash`) und dasselbe Receipt per `eth_getTransactionReceipt` (Status, Blocknummer und -hash, verbrauchtes Gas und die vollständige Log-Liste). Welche Funktionen als registerändernd gelten, ist eine explizite Liste von Funktionsnamen im Backend (Zwangsübertragungen, Burn, Mint, Freezes einschließlich des gerichtlich angeordneten `setAddressFrozen`, Pause, Claims, Identity Registry, Compliance-Module und ihre Limits wie `setMaxBalance`, NAV `setNavPerShare`, Ownership, Rollen, Ecosystem und dApp-Registry); eine nicht klassifizierte Funktion wird als registerändernd behandelt. `registerwerk.blockchain.tx.second-source-methods` ergänzt nur exakte Funktionsnamen (oder `*`). Eine Abweichung hält die Transaktion an (`registerwerk.confirmation.second_source_mismatch`, ERROR-Log). Bei weniger als zwei gesunden Knoten läuft die Transaktion außerhalb des Produktionsmodus weiter und `registerwerk.confirmation.single_source` zählt sie; **im Produktionsmodus (`REGISTERWERK_PRODUCTION_MODE=true`) wird sie angehalten** und bleibt `PENDING` (`registerwerk.confirmation.single_source_held`, Alert `RpcSingleSourceHeld`, später `RpcSecondSourceHoldOverdue`), bis ein zweiter unabhängiger Knoten gesund ist. Die Gebührendaten des Hauptknotens werden mit einem zweiten Knoten gegengeprüft: Gebühr = `min(A, B x 1,5)`.
- **Finalitätsparameter** stammen ausschließlich aus `chain_config` und `registerwerk.blockchain.tx.*`. Ein Chaincache-Knoten bleibt Quelle für Blockhashes, doch seine selbst erklärte SAFE-/FINALIZED-Stufe wird durch die konfigurierte Tiefe bzw. Tags gedeckelt. Welche Finalitätsstufe je Kettenmodell rechtlich für eine Eintragung genügt, ist eine offene Entscheidung (T4-08).
- **Die Chain-ID muss gesetzt sein.** Das Signieren verweigert jede Transaktion auf einer EVM-Chain, deren `chain_config.chain_id` leer ist (`chain id unpinned`). Beim Start protokolliert das Backend einen ERROR mit den aktivierten EVM-Chains, die BESTÄTIGTE (CONFIRMED) Deployments, aber keine Chain-ID haben, und stellt die Metrik `registerwerk_evm_chains_unpinned` bereit (Alarm `EvmChainIdUnpinned`). Es gibt bewusst kein automatisches Nachtragen: Setzen Sie die ID des Netzwerks ausdrücklich (`PATCH /api/v1/admin/chains/{id}` mit `chainId`), bevor die nächste Transaktion erfolgt.
