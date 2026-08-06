---
title: Solana
description: Supporto blockchain di Solana: programmi token SPL e SPL-2022 per titoli nativi di Solana.
---

# Solana { #solana }

Solana offre un throughput elevato (oltre 50.000 TPS), finalità inferiore al secondo e costi di transazione molto bassi. Registerwerk supporta i security token nativi di Solana sia attraverso il programma classico **SPL Token** che attraverso il programma esteso **Token-2022** (SPL-2022).

---

## Reti supportate { #supported-networks }

| Rete | Enumerazione rete | Endpoint | Uso |
|---|---|---|---|
| Solana mainnet-beta | `MAINNET` | `https://api.mainnet-beta.solana.com` | Produzione |
| Solana devnet | `TESTNET` | `https://api.devnet.solana.com` | Sviluppo/test |

---

## Libreria client: Solanaj { #client-library-solanaj }

Registerwerk utilizza **Solanaj** (libreria client Java per Solana) tramite `SolanaClientFactory`. Operazioni chiave:

| Operazione | Solanaj API | Utilizzato in |
|---|---|---|
| Crea un mint account | `MintLayout.encode()` + `SystemProgram.createAccount()` | `SolanaTokenService.deploy()` |
| Conia token | Istruzione del programma token `mintTo` | `SolanaTokenService.mint()` |
| Trasferimento | Istruzione del programma token `transfer` | `SolanaTokenService.transfer()` |
| Imposta autorità | Istruzione del programma token `setAuthority` | Operazioni di amministrazione |
| Ottieni saldo | `rpcClient.getTokenAccountBalance()` | Indicizzatore, saldo del wallet |

---

## Modello di conto token { #token-account-model }

Il modello di token di Solana differisce significativamente da EVM:

- Un **mint account** definisce il token (equivalente a un indirizzo di contratto ERC-20)
- Ogni titolare necessita di un **token account** separato (Associated Token Account, ATA) per conservare il token
- Il flusso di distribuzione di Registerwerk crea automaticamente ATA per i wallet dell'operatore
- Gli ATA degli investitori vengono creati alla prima ricezione

`AssetDeployment.contractAddress` memorizza il **mint address** Solana (chiave pubblica codificata in base58).

---

## Estensioni SPL-2022 { #spl-2022-extensions }

Per una trattazione dettagliata delle estensioni Token-2022 (InterestBearing, ConfidentialTransfer, TransferHook, PermanentDelegate), vedere [SPL-2022](../token-standards/spl-2022.md).

---

## Indexer { #indexer }

L'indicizzatore di Solana ascolta le transazioni sui mint account monitorati utilizzando abbonamenti WebSocket (tramite le API avanzate di Helius o Shyft). Su ogni transazione confermata:

1. Analizza il registro delle transazioni per le istruzioni di trasferimento dei token
2. Mappa da/verso account Solana ai record `LegalEntity`
3. Scrivi un record `token_transfer` (schema coerente con l'indicizzatore EVM)
4. Aggiorna `AssetHolder.nominalAmount`

`IndexerMonitorService` controlla l'attività dell'indicizzatore Solana ogni 5 minuti. Se non viene ricevuto alcun evento per più di 30 minuti su una risorsa attiva, viene aperto un incidente `DORA_AVAILABILITY`.

---

## Wallet dell'operatore su Solana { #operator-wallet-on-solana }

Il wallet Solana di Registerwerk è una **coppia di chiavi ed25519** standard. La chiave privata viene archiviata crittografata nel vault del wallet dell'operatore (stessa busta KMS/KEK dei keystore EVM). Il wallet dell'operatore è l'autorità di conio e di congelamento per tutti i token SPL-2022.

!!! warning "Saldo SOL in affitto"
    I conti Solana richiedono un **affitto** (saldo minimo SOL) per rimanere aperti. Gli account token aperti dal servizio di distribuzione richiedono un piccolo deposito SOL. `WalletBalanceService` monitora il saldo SOL dell'operatore e avvisa quando scende al di sotto di 0,5 SOL.
