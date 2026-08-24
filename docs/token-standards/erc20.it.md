---
title: ERC-20 — Token fungibile
description: Implementazione standard ERC-20 per security token azionari, di utilità e semplici titoli fungibili.
---

# ERC-20 — Token fungibile { #erc-20-fungible-token }

ERC-20 è lo standard fondamentale dei token fungibili per le catene EVM. Ogni unità è identica e intercambiabile. Registerwerk distribuisce token ERC-20 per strumenti azionari, strumenti di debito semplici e token di utilità per i quali non è richiesto il gating KYC a livello di contratto (la conformità viene invece applicata a livello di registro).

---

## Quando utilizzare ERC-20 { #when-to-use-erc-20 }

- **Token azionari** — azioni di una società non quotata in cui le restrizioni di trasferimento sono gestite off-chain
- **Obbligazioni semplici** — quando l'emittente non necessita dell'applicazione di restrizioni di trasferimento sulla catena
- **Token di utilità** — per crediti o incentivi interni alla piattaforma
- **Emissioni di prova** — ERC-20 è il percorso di implementazione più semplice per i nuovi emittenti che apprendono la piattaforma

Per i titoli regolamentati che richiedono il gating KYC sulla catena, prendere in considerazione [ERC-3643](erc3643.md). Per obbligazioni con più tranche, prendere in considerazione [ERC-3525](erc3525.md).

---

## Estensioni ERC-20 di Registerwerk { #registerwerk-erc-20-extensions }

Registerwerk implementa un contratto `EwpgERC20` personalizzato che estende lo standard ERC-20 con:

| Estensione | Scopo |
|---|---|
| `mintWithCap` | Rispetta il `MintControlRule.maxSupply` configurato dall'operatore |
| `pause` / `unpause` | Interruttore automatico di emergenza per l'operatore del registro |
| `freeze(address)` | Blocco a livello di registro (mappa su `HolderBlock` nel DB) |
| `setIsin(string)` | Memorizza l'ISIN sulla catena per riferimenti incrociati |
| `setRegistryRef(string)` | Memorizza l'ID risorsa Registerwerk a scopo di controllo |

---

## Flusso di distribuzione { #deployment-flow }

1. L'operatore seleziona `TokenStandard.ERC20` durante la creazione di un `Asset`
2. Dopo l'approvazione di KYC e (facoltativamente) l'autenticazione avanzata, chiama `POST /api/v1/assets/{id}/deploy`
3. `Erc20DeploymentService` costruisce e trasmette la transazione di distribuzione
4. Alla conferma del ricevimento, `AssetDeployment` viene creato con `contractAddress` e `deploymentTxHash`
5. `Asset.status` passa a `ISSUED`

---

## Operazioni di amministrazione on-chain { #on-chain-admin-operations }

| Operazione | Endpoint | Richiede |
|---|---|---|
| Conia token | `POST /api/v1/assets/{id}/mint` | REGISTRY_ADMIN + step-up (se il limite di supply è gestito) |
| Distruggi token | `POST /api/v1/assets/{id}/burn` | REGISTRY_ADMIN + step-up + 4 occhi |
| Trasferimento coattivo | `POST /api/v1/assets/{id}/force-transfer` | REGISTRY_ADMIN + step-up + 4 occhi |
| Blocca indirizzo | `POST /api/v1/assets/{id}/freeze/{address}` | REGISTRY_ADMIN + HolderBlock attivo |
| Pausa contratto | `POST /api/v1/assets/{id}/pause` | REGISTRY_ADMIN + step-up |

---

## Variante confidenziale { #confidential-variant }

`CONF_ERC20` distribuisce una variante riservata [Zama fhEVM](confidential.md) sulle reti Fhenix o Inco, dove i saldi e gli importi dei trasferimenti sono crittografati utilizzando la crittografia completamente omomorfica. Utilizzarlo quando l'emittente richiede la riservatezza delle posizioni degli investitori.
