---
title: StarkNet e Stellar
description: Stato e configurazione del supporto blockchain di StarkNet (Cairo ERC-3525) e Stellar (asset nativo).
---

# StarkNet e Stellar { #starknet-stellar }

StarkNet e Stellar sono parzialmente supportati in Registerwerk. L'infrastruttura di base (cablaggio del client, scheletri del servizio di distribuzione, enumerazioni standard dei token) è a posto, ma entrambe le catene hanno **valori segnaposto** che devono essere sostituiti prima dell'uso in produzione.

---

## StarkNet { #starknet }

StarkNet è un ZK-rollup su Ethereum che utilizza il linguaggio del contratto intelligente **Cairo**. Offre una sicurezza equivalente a quella di Ethereum con costi di transazione notevolmente inferiori.

### Tipi di token supportati { #supported-token-types }

| Enumerazione token | Descrizione |
|---|---|
| `STARKNET_ERC20` | Cairo ERC-20 equivalente |
| `STARKNET_ERC3525` | Cairo ERC-3525 semi-fungibile — obbligazioni tranched |

### Stato { #status }

⚠️ **L'hash della classe StarkNet è un segnaposto zero.** Prima di distribuire i token StarkNet in produzione:

1. Compila i contratti Cairo sotto `contracts/cairo/`
2. Dichiara la classe del contratto: `starkli declare target/dev/EwpgERC3525.json`
3. Sostituisci l'hash della classe nella configurazione di `StarknetTokenService` con l'hash della classe dichiarata

`StarknetTokenService` utilizza un client Java personalizzato (Starknet4j) configurato tramite `Chain.STARKNET` + `Network.MAINNET/TESTNET`.

### Reti { #networks }

| Rete | Enumerazione rete | Note |
|---|---|---|
| Rete principale StarkNet | `MAINNET` | Produzione: hash della classe richiesto |
| StarkNet Sepolia | `TESTNET` | Sviluppo/test |

---

## Stellar { #stellar }

Stellar è una blockchain incentrata sui pagamenti con supporto nativo per **Stellar Assets** — rappresentazioni on-chain di qualsiasi valuta o strumento.

### Tipo di token supportato { #supported-token-type }

| Enumerazione token | Descrizione |
|---|---|
| `STELLAR_ASSET` | Asset emesso nativo di Stellar |

### Modello di asset Stellar { #stellar-asset-model }

A differenza di EVM o Solana, Stellar ha un tipo di asset integrato a livello di protocollo. Non è necessaria alcuna distribuzione del contratto:

1. Il **conto emittente** crea una linea di fiducia dal conto del titolare
2. Il conto di emissione invia il bene al conto del titolare tramite un'operazione `Payment`
3. I saldi vengono archiviati in modo nativo nelle voci del registro degli account Stellar

In Registerwerk:
- `AssetDeployment.contractAddress` memorizza l'**indirizzo dell'account di emissione** Stellar (chiave pubblica Stellar)
- `StellarAssetService` utilizza **Horizon API** (Java SDK) per inviare transazioni

### Stato { #status }

⚠️ **Il supporto di Stellar è un segnaposto.** Gli scheletri `StellarAssetService` sono presenti ma l'implementazione completa (gestione della linea di fiducia, conformità, indicizzatore) non è ancora stata completata.

---

## Nota sulla roadmap { #roadmap-note }

Sia StarkNet che Stellar rappresentano aree di sviluppo attive. L'infrastruttura esiste per consentire i contributi. Considerazioni prioritarie:

- **StarkNet ERC-3525**: valore elevato per gli emittenti [Liechtenstein TVTG](../legal/tvtg-li.md) che preferiscono il regolamento comprovato da ZK rispetto ai rollup ottimistici
- **Stellar**: utile per i titoli di pagamento transfrontalieri e le stablecoin nei mercati emergenti

Per contribuire con un'implementazione, seguire lo schema dei servizi di distribuzione EVM (`Erc20DeploymentService`, `Erc3525DeploymentService`) e implementare la stessa interfaccia `TokenDeploymentPort`.
