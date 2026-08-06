---
title: Token riservati (Zama fhEVM)
---

# Configurazione del token confidenziale (Zama fhEVM) { #confidential-token-setup-zama-fhevm }

Questa guida illustra la distribuzione e l'amministrazione dei token confidenziali ERC-20/ERC-3643 utilizzando
fhEVM di Zama.

## Prerequisiti { #prerequisites }

1. Una catena con una **vera infrastruttura Zama fhEVM** — Ethereum Sepolia oggi (gli indirizzi documentati
sono vendorizzati (vendored) in `contracts/lib/fhevm/config/` e raggruppati in `@zama-fhe/relayer-sdk` come
`SepoliaConfig`), o mainnet Ethereum/Base una volta che Zama pubblica gli indirizzi finali lì.
La distribuzione confidenziale è limitata a `Chain.ETHEREUM`/`Chain.BASE` — **non** Fhenix/Inco.
2. `EwpgConfidentialFactory` distribuito e configurato con gli indirizzi FHEVM reali di quella catena
(`setFhevmInfra`) — vedi `docs/blockchains/confidential-evm.md` nel repository.
3. Solo per `CONF_ERC3643`: un vero T-REX `IdentityRegistry` predisposto per risorse confidenziali su
quella catena, configurata tramite `registerwerk.contracts.confidential-identity-registry.<chain>`.
La distribuzione fallisce rumorosamente se questo non è impostato.
4. Gli indirizzi di visualizzazione dedicati di sola decrittografia dell'operatore e di un revisore configurati tramite
`registerwerk.contracts.confidential-operator-viewer.<chain>` /
`.confidential-auditor-viewer.<chain>` — questi diventano visualizzatori su ogni token confidenziale
distribuito su quella catena dal blocco uno.
5. `zama-relayer` in esecuzione (`docker compose --profile confidential up`) con
`OPERATOR_DECRYPT_PRIVATE_KEY` impostato sulla chiave privata corrispondente all'indirizzo operatore-visualizzatore
sopra e `registerwerk.zama.relayer-url` del backend puntato su di esso.

## Distribuzione { #deploying }

Flusso standard di distribuzione delle risorse, uguale a qualsiasi altro standard:

```bash
curl -X POST http://localhost:8080/api/v1/assets/{assetId}/deploy \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  -d '{ "chain": "ETHEREUM", "network": "TESTNET" }'
```

Il backend instrada `CONF_ERC20`/`CONF_ERC3643` a `ConfidentialErc20Service`/
`ConfidentialErc3643Service`, che chiama `EwpgConfidentialFactory.deployConfidentialErc20`/
`deployConfidentialErc3643` — transazioni Web3j reali, passando gli indirizzi operatore/auditor configurati
viewer come `initialViewers`.

## Azioni dell'operatore disponibili oggi { #operator-actions-available-today }

| Azione | Punto finale | Note |
|---|---|---|
| Conio confidenziale (emissione di emittente/operatore) | `POST /api/v1/assets/{id}/deployments/{depId}/issuer/mint-confidential` | Crittografa l'importo lato server tramite il sidecar `zama-relayer`: non è necessario alcun browser/portafoglio |
| Distruzione forzata confidenziale (§26 Einziehung) | `POST .../admin/force-burn-confidential` | Stesso percorso di crittografia lato server; già soggetto al controllo agente/proprietario: tale controllo È l'autorità per la distruzione forzata |
| Aggiungi un visualizzatore confidenziale | `POST .../admin/confidential-add-viewer` | Concede i diritti di decrittografia sul saldo di ogni detentore in futuro, ad es. aggiunta di un revisore o del portafoglio dell'emittente dopo l'implementazione |
| Rimuovere un visualizzatore confidenziale | `POST .../admin/confidential-remove-viewer` | Interrompe le concessioni future: NON revoca retroattivamente gli handle storici già decifrabili (ACL di Zama non ha una primitiva di revoca) |
| Riconciliazione tra registro e catena | `GET /api/v1/assets/{id}/confidential-reconciliation` | Headless: decrittografa il saldo on-chain di ogni titolare tramite la chiave di decrittografia dell'operatore del backend e lo confronta con il testo in chiaro del registro `nominalAmount`. Ruolo `REGISTRY_ADMIN` o `AUDIT`. |
| Rivela + riconcilia tramite il tuo portafoglio | Portale Operatore → Asset → scheda **Confidential Balances** (Saldi confidenziali) | Collega un portafoglio visualizzatore nel browser e decrittografalo direttamente con il relè di Zama: un controllo incrociato indipendente della riconciliazione headless sopra |
| Divulgazione della fornitura pubblica/oracolare | `ConfidentialERC20.requestSupplyDisclosure()` (chiamata on-chain; nessun endpoint dell'API operatore lo incapsula ancora) | Per una divulgazione aggregata attivata dal regolatore, non il saldo di un titolare specifico |

Blocco/pausa/trasferimento forzato su `CONF_ERC3643` sono **non ancora cablati** tramite l'operatore API —
il controller di amministrazione ERC-3643 esistente ha come target l'ABI del contratto `EwpgERC3643` in chiaro, che
non corrisponde alle firme degli importi crittografati di `ConfidentialERC3643`.

## Il sidecar del relè { #the-relayer-sidecar }

`zama-relayer` (root del repository `zama-relayer/`) è il servizio di Registerwerk che racchiude il vero
`@zama-fhe/relayer-sdk` — costruito e distribuito in questo monorepo, non qualcosa che devi scrivere.
Zama non pubblica alcun client Java/JVM, che è l'unico motivo per cui esiste questo sidecar; ogni
azione confidenziale avviata dal browser (investitore/emittente/revisore dei conti che rivela un saldo, trasferimento confidenziale
di un investitore) comunica con il relè di Zama direttamente dal browser e non tocca mai questo sidecar. Abilitalo con:

```bash
docker compose --profile confidential up
```

Consulta la sezione `.env.example` ("Token confidenziali (Zama fhEVM)") per le variabili d'ambiente:
`ZAMA_CONFIG_PRESET=sepolia`, `ZAMA_OPERATOR_DECRYPT_PRIVATE_KEY` e
`REGISTERWERK_ZAMA_RELAYER_URL` sul lato backend.

## Decrittazione del saldo di investitore/emittente/revisore { #investorissuerauditor-balance-decryption }

La rivelazione di un saldo confidenziale (o la crittografia di un importo di trasferimento confidenziale) è un'operazione **lato client**
in entrambi i frontend: il portafoglio connesso firma una richiesta EIP-712 e la propria istanza del browser di
`@zama-fhe/relayer-sdk` comunica direttamente con il Relayer di Zama: vedere `FheClientService` in
`frontend-customer` (rivelazione automatica dell'investitore + trasferimento confidenziale; rivelazione dell'emittente a tutti i detentori) e
`frontend-operator` (operatore/auditor `ConfidentialViewerPanelComponent`). Nessuno di questi instrada
attraverso questo backend.
