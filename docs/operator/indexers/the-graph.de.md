---
title: Der Graph (EVM Indexer)
---

# The Graph – EVM-Indexierung

Registerwerk verwendet `graph-node`, um vorläufige, ereignisabgeleitete Projektionen für konfigurierte
EVM-Verträge zu erstellen. Subgraph-Entitäten sind keine Chain-Finalitätsbescheinigungen, keine rechtlichen Registereinträge,
kein rechtlicher Abwicklungsnachweis und kein Beweis für die Identität des bereitgestellten Codes. Gleichen Sie die konfigurierte Chain,
Bestätigungen, die Vertragsbereitstellung und das maßgebliche Rechtsregister ab, bevor Sie sich darauf verlassen.

## Installieren und prüfen

```bash
cd indexer/evm/subgraph
npm install
npm test
```

`npm test` prüft die eingecheckte ABI-/Ereignisparität gegen Forge-Artefakte, testet das Rendern des Manifests,
führt die Graph-Codegenerierung aus und kompiliert jedes Mapping.

## Erforderliche Bereitstellungskonfiguration

Das Bereitstellungsziel wählt ein Umgebungssuffix:

| Ziel | Graph-Netzwerk | Suffix |
|---|---|---|
| `mainnet` | `mainnet` | `MAINNET` |
| `sepolia` | `sepolia` | `SEPOLIA` |
| `polygon` | `polygon` | `POLYGON` |
| `polygon-amoy` | `polygon-amoy` | `POLYGON_AMOY` |
| `base` | `base` | `BASE` |
| `base-sepolia` | `base-sepolia` | `BASE_SEPOLIA` |
| `arbitrum-one` | `arbitrum-one` | `ARBITRUM` |
| `arbitrum-sepolia` | `arbitrum-sepolia` | `ARBITRUM_SEPOLIA` |
| `avalanche` | `avalanche` | `AVALANCHE` |
| `avalanche-fuji` | `avalanche-fuji` | `AVALANCHE_FUJI` |
| `optimism` | `optimism` | `OPTIMISM` |
| `optimism-sepolia` | `optimism-sepolia` | `OPTIMISM_SEPOLIA` |

Konfigurieren Sie für jedes Suffix die vier Singleton-Quellen unten. Ihr Startblock ist standardmäßig null,
aber Betreiber sollten stets den tatsächlichen Bereitstellungsblock verwenden, um den Replay-Umfang explizit festzulegen. Jede
Quelle hat eine eigenständige Herkunft: Kopieren Sie den Block einer Factory nicht in die anderen Quellfelder,
es sei denn, die Bereitstellungsbelege belegen tatsächlich, dass sie diesen Block gemeinsam nutzen.

```dotenv
ASSET_TOKEN_FACTORY_ADDRESS_SEPOLIA=0x...
ASSET_TOKEN_FACTORY_START_BLOCK_SEPOLIA=120
REPO_MARKET_FACTORY_ADDRESS_SEPOLIA=0x...
REPO_MARKET_FACTORY_START_BLOCK_SEPOLIA=130
DVP_SETTLEMENT_ADDRESS_SEPOLIA=0x...
DVP_SETTLEMENT_START_BLOCK_SEPOLIA=140
CONFIDENTIAL_FACTORY_ADDRESS_SEPOLIA=0x...
CONFIDENTIAL_FACTORY_START_BLOCK_SEPOLIA=150
```

BondDesk-, Stablecoin-AMM- und RepoVault-Bereitstellungen lassen sich über die Factory nicht zuverlässig auffinden. Listen Sie
jede Instanz explizit als `address@deploymentBlock` auf, durch Kommas getrennt:

```dotenv
BOND_DESK_INSTANCES_SEPOLIA=0xDesk1@123,0xDesk2@456
STABLECOIN_AMM_INSTANCES_SEPOLIA=0xAmm1@123,0xAmm2@456
REPO_VAULT_INSTANCES_SEPOLIA=0xVault1@123,0xVault2@456
```

Konfiguriert der Betreiber null Instanzen für eine Rolle, setzen Sie ihre Liste exakt auf `NONE`. Das ist eine
Aussage des Betreibers zur Konfiguration, kein Beleg dafür, dass on-chain keine Bereitstellung existiert. Eine nicht gesetzte
oder leere Liste schlägt fail-closed fehl. Der Renderer weist außerdem Nulladressen, fehlerhafte Blöcke und eine
Adresse zurück, die von einer anderen statischen Quelle wiederverwendet wird.

## Bereitstellen

```bash
SUBGRAPH_VERSION_LABEL=sepolia-20260729-01 ./indexer/evm/deploy-subgraph.sh sepolia
```

Verwenden Sie `SUBGRAPH_VALIDATE_ONLY=true`, um zu rendern, zu generieren und zu kompilieren, ohne eine graph-node-Bereitstellung
einzureichen. `all` verarbeitet jedes Ziel in der Tabelle und erfordert daher eine Konfiguration für
jedes Suffix. Eine echte Bereitstellung erfordert außerdem `SUBGRAPH_VERSION_LABEL`; wählen Sie für
jede Bereitstellung unter diesem Graph-Namen eine neue Bezeichnung. Der Wrapper weist eine fehlende Bezeichnung zurück; der Betreiber muss sicherstellen,
dass die angegebene Bezeichnung neu ist. Halten Sie die vorherige Version verfügbar, bis die Ersatzversion aufgeholt hat
und den unabhängigen Ereignisbereichsabgleich bestanden hat.

Die AssetTokenFactory erzeugt dynamische Token-Datenquellen aus `TokenDeployed` und `VaultDeployed`.
Die RepoMarketFactory erzeugt ebenso RepoMarket-Quellen aus `MarketCreated`. Neue Instanzen der
drei explizit gelisteten Vertragstypen erfordern eine Listenaktualisierung und eine erneute Subgraph-Bereitstellung.
Von der Factory ausgegebene Adressen, Asset-IDs, Token-Referenzen, Oracle-Parameter und Beobachtungsblöcke
werden als Ereignis-Claims gespeichert. Sie belegen weder den bereitgestellten Bytecode noch die Herkunft der Bereitstellung
noch die Verknüpfung mit einem Datensatz in der Anwendungsdatenbank.

## Projektionsmigration und Replay

Die ERC-3525-Owner/Slot-Nominalwerte und die ERC-7540-Request-Lebenszyklus-Entitäten benötigen die Ereignisreihenfolge ab
der Vertragsbereitstellung. Vorhandene `HolderBalance`-Zeilen für ERC-3525 zählten Token-IDs und können nicht in Nominalwerte
umgewandelt werden. Kopieren Sie sie nicht nach `Erc3525OwnerSlotBalance`.

Stellen Sie für diese Schema-Revision eine neue Subgraph-Version bereit und spielen Sie jede Quelle ab ihrem tatsächlichen
Bereitstellungsblock erneut ab. Eine `INCOMPLETE`-Projektion kann fehlende Owner, Slots, Werte,
Request-Typen oder frühere RepoVault-Marktkonfigurationen nicht rekonstruieren. Jede RepoVault-Projektion bleibt
`INCOMPLETE`, sofern Bereitstellungsherkunft und vollständiger Replay nicht außerhalb dieses Subgraphs nachgewiesen werden; das
bloße Beobachten des ersten Ereignisses an einer konfigurierten statischen Adresse liefert diesen Nachweis nicht. Halten Sie die alte
Bereitstellung für ein Rollback verfügbar, bis die neue Projektion den Chain-Head erreicht hat und unabhängig abgeglichen wurde.

RepoVault-Beträge für `Allocated` und `Deallocated` werden nur als vorzeichenbehafteter Netto-Cashflow projiziert.
Eine Deallokation kann die vorherige Allokation übersteigen, etwa durch Zinsen oder Verlustrealisierung, daher ist dieser Wert
weder ausstehendes Kapital noch skalierte Marktposition noch NAV, und eine negative Gesamtsumme ist für sich genommen keine
Inkonsistenz.

## Überwachen und abfragen

```bash
curl -s http://localhost:8030/graphql \
  -d '{"query":"{indexingStatuses{subgraph synced health chains{network latestBlock{number}}}}"}' \
  | jq '.data.indexingStatuses[]'
```

`synced: true` beschreibt nur den Fortschritt von graph-node; es ist kein Finalitäts- oder Rechtswirkungssignal.
Fragen Sie eine Bereitstellung unter `http://localhost:8000/subgraphs/name/<subgraph-name>` ab.

Häufige Fehlerursachen sind RPC-Throttling, unzureichender Speicher, veraltete Forge-Artefakte, ABI-Drift oder eine
fehlende Konfiguration einer statischen Quelle. Führen Sie `npm test` aus, bevor Sie einen Bereitstellungsfehler diagnostizieren.
