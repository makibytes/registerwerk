---
title: Configurazione ERC-3643
---

# Configurazione ERC-3643 (T-REX) { #erc-3643-t-rex-setup }

Questa guida illustra la configurazione completa dell'infrastruttura ERC-3643 T-REX: dall'implementazione del contratto all'emissione di attestazioni KYC agli investitori.

## Cosa viene implementato { #what-gets-deployed }

Per ogni emissione di ERC-3643, la fabbrica implementa sei contratti:

| Contratto | Ruolo |
|----------|------|
| `Token` | Il token ERC-3643 (contratto principale, interfaccia compatibile ERC-20) |
| `IdentityRegistry` | Mappa i portafogli degli investitori sui loro ONCHAINID |
| `IdentityRegistryStorage` | Memoria aggiornabile per il registro delle identità |
| `ClaimTopicsRegistry` | Definisce gli ID degli argomenti di attestazione richiesti (ad esempio, KYC=1, AML=2) |
| `TrustedIssuersRegistry` | Definisce quali emittenti di identità possono firmare attestazioni |
| `ModularCompliance` | Contenitore per moduli di regole di conformità collegabili |

Tutti e sei vengono distribuiti atomicamente da `EwpgTREXFactory` tramite `AssetTokenFactory`.

## Passaggio 1: distribuire la suite di fabbrica { #step-1-deploy-the-factory-suite }

Assicurarsi che `AssetTokenFactory` e `EwpgTREXFactory` siano stati distribuiti secondo [Distribuzione di contratti](./deploying-contracts.md). Conferma che l'indirizzo di fabbrica è impostato in `.env` e che il backend lo ha caricato:

```bash
curl http://localhost:48080/api/v1/admin/chains/11155111 \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  | jq '.factoryAddress'
```

## Passaggio 2: distribuire il ClaimIssuer del registro e registrarlo come emittente attendibile { #step-2-deploy-the-registry-claimissuer-and-trust-it }

Il backend emette le attestazioni KYC/AML tramite un **contratto** ONCHAINID `ClaimIssuer`, uno per catena, la cui chiave MANAGEMENT è il firmatario del registro del backend. Il wallet firmatario non può essere esso stesso l'emittente: `addClaim` di ONCHAINID chiama `isClaimValid` sull'emittente, che per un semplice wallet va in revert; tali attestazioni quindi non raggiungono mai la catena.

```bash
cd contracts
REGISTRY_WALLET_PRIVATE_KEY=$REGISTRY_SIGNER_KEY \
  forge script script/DeployClaimIssuer.s.sol --rpc-url $RPC_URL --broadcast
# Logs "ClaimIssuer : 0x…". The MANAGEMENT key is the broadcasting wallet, i.e. the
# backend's registry signer (default); CLAIM_ISSUER_MANAGEMENT_KEY is an optional override.
```

!!! warning "Chiave di gestione = chiave di firma «calda» per impostazione predefinita"
    Per impostazione predefinita il firmatario del registro firma le attestazioni e controlla allo stesso tempo l'insieme di chiavi del ClaimIssuer (`addKey`/`removeKey`) e i suoi aggiornamenti; il backend ha bisogno dei diritti MANAGEMENT per chiamare `revokeClaimBySignature`. Trattare il firmatario del registro come una chiave di alto valore (in produzione supportata da KMS/HSM). `CLAIM_ISSUER_MANAGEMENT_KEY` può indicare invece una chiave separata (fredda o multifirma); tale chiave deve quindi eseguire `addKey(keccak256(abi.encode(registrySigner)), 3, 1)` affinché il firmatario possa firmare le attestazioni, e la revoca da parte del backend va in revert finché il firmatario non detiene anche una chiave MANAGEMENT (purpose 1): in tal caso le attestazioni vanno revocate dalla chiave di gestione.

Impostare `CLAIM_ISSUER_<CHAIN>` (ad esempio `CLAIM_ISSUER_ETH_TESTNET`, associato a `registerwerk.contracts.claim-issuer.<chain>`) e riavviare il backend. Senza questo valore il backend rifiuta l'emissione di attestazioni e la distribuzione di suite T-REX su quella catena (rifiuto in caso di errore), invece di inviare transazioni che andrebbero in revert. Prima di ogni `addClaim` verifica inoltre che il firmatario detenga una chiave sul ClaimIssuer.

Le nuove suite distribuite dal backend considerano attendibile questo ClaimIssuer per gli argomenti 1 (KYC) e 2 (AML). Per una suite distribuita **prima** di questa modifica, registrarlo una volta (API dell'operatore, riservata al proprietario del `TrustedIssuersRegistry` della suite):

```bash
curl -X POST http://localhost:48080/api/v1/assets/$ASSET_ID/erc3643/$DEPLOYMENT_ID/trusted-issuers \
  -H "Authorization: Bearer $OPERATOR_JWT" -H "Content-Type: application/json" \
  -d "{\"issuerAddress\": \"$CLAIM_ISSUER\", \"claimTopics\": [1,2]}"
```

Verificare:

```bash
cast call $TRUSTED_ISSUERS_REGISTRY \
  "isTrustedIssuer(address)(bool)" $CLAIM_ISSUER --rpc-url $RPC_URL
# Expected: true
```

Per le dApp dell'ecosistema controllate tramite `PermissionOracle`, registrare lo stesso ClaimIssuer nell'`EcosystemTrustedIssuersRegistry`, tramite l'amministrazione degli emittenti attendibili dell'operatore del registro.

!!! note
    La revoca di un'attestazione la rimuove dall'identità **e** chiama `revokeClaimBySignature` sul ClaimIssuer. Il secondo passaggio impedisce a chiunque di aggiungere di nuovo in seguito la firma pubblicata. Entrambi i passaggi richiedono che il firmatario del registro detenga la chiave MANAGEMENT del ClaimIssuer.

## Passaggio 3: configurare gli argomenti di attestazione { #step-3-configure-claim-topics }

`ClaimTopicsRegistry` elenca tutti gli argomenti di attestazione richiesti per l'idoneità al trasferimento:

```bash
cast send $CLAIM_TOPICS_REGISTRY "addClaimTopic(uint256)" 1 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY

cast send $CLAIM_TOPICS_REGISTRY "addClaimTopic(uint256)" 2 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

| ID argomento | Significato |
|----------|---------|
| 1 | KYC — verifica dell'identità |
| 2 | AML — screening antiriciclaggio |

Il backend fornisce automaticamente questi argomenti quando si crea una nuova emissione T-REX.

## Passaggio 4: registrare i contratti degli investitori ONCHAINID { #step-4-register-investor-onchainid-contracts }

Quando un investitore completa l'onboarding, il backend distribuisce per lui un contratto ONCHAINID e lo registra nel registro delle identità. Ciò avviene automaticamente quando inserisci nella whitelist un investitore tramite il frontend dell'operatore.

Ogni registrazione richiede un paese in formato numerico ISO 3166-1. La finestra di dialogo
dell'operatore lo precompila con il paese di registrazione KYC del soggetto giuridico e l'API
rifiuta un paese mancante o il paese `0`. Non appena un paese è bloccato per un token,
`EwpgComplianceModule` rifiuta i trasferimenti verso un wallet il cui paese registrato è `0`. La
tabella del registro delle identità segnala questi wallet come **Country missing** (paese
mancante). Correggili con `updateCountry(address,uint16)` sull'Identity Registry.

Per verificare che l'ONCHAINID di un investitore sia registrato:

```bash
cast call $IDENTITY_REGISTRY \
  "contains(address)(bool)" \
  $INVESTOR_WALLET_ADDRESS \
  --rpc-url $RPC_URL
# Expected: true
```

Per cercare l'indirizzo ONCHAINID per un portafoglio:

```bash
cast call $IDENTITY_REGISTRY \
  "identity(address)(address)" \
  $INVESTOR_WALLET_ADDRESS \
  --rpc-url $RPC_URL
```

## Passaggio 5: emissione delle attestazioni KYC/AML { #step-5-issuing-kycaml-claims }

Dopo l'approvazione di KYC nel frontend dell'operatore, il backend emette automaticamente attestazioni sull'ONCHAINID dell'investitore:

1. Costruisce i dati dell'attestazione `abi.encode(topic, scheme=1, claimIssuer, expiresAt, "")`
2. Firma `keccak256(abi.encode(identity, topic, data))` (con prefisso EIP-191) con il firmatario del registro
3. Chiama `addClaim(topic, 1, claimIssuer, signature, data, "")` sul contratto ONCHAINID dell'investitore, con il contratto ClaimIssuer della catena come emittente

Le attestazioni includono una data di scadenza (impostazione predefinita: 365 giorni). Il backend pianifica le e-mail di promemoria della scadenza e può riemettere le attestazioni al rinnovo.

Per verificare manualmente le attestazioni su un ONCHAINID:

```bash
cast call $INVESTOR_ONCHAINID \
  "getClaimIdsByTopic(uint256)(bytes32[])" 1 \
  --rpc-url $RPC_URL
# Returns array of claim IDs for topic 1 (KYC)
```

## Passaggio 6: moduli di conformità { #step-6-compliance-modules }

Configura i moduli di conformità per emissione dal frontend dell'operatore in **Issuances → [issuance] → Compliance Modules** (Emissioni → [emissione] → Moduli di conformità).

### Modulo MaxBalance { #maxbalance-module }

Limita il saldo massimo dei token che un singolo investitore può detenere.

Configura tramite il frontend dell'operatore o direttamente:

```bash
cast send $MAX_BALANCE_MODULE \
  "setMaxBalance(address,uint256)" $TOKEN_ADDRESS 100000 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

### Modulo MaxInvestors { #maxinvestors-module }

Limita il numero totale di titolari di token distinti (utile per i limiti di esenzione del Regolamento D):

```bash
cast send $MAX_INVESTORS_MODULE \
  "setMaxInvestors(address,uint256)" $TOKEN_ADDRESS 499 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

### Modulo CountryRestrict { #countryrestrict-module }

Blocca gli investitori dai codici paese numerici ISO 3166-1 specificati:

```bash
# Block US (840) and CN (156)
cast send $COUNTRY_RESTRICT_MODULE \
  "batchRestrictCountries(address,uint16[])" \
  $TOKEN_ADDRESS "[840,156]" \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

### EwpgComplianceModule (modulo proprio di Registerwerk) { #ewpgcompliancemodule-registerwerks-own-module }

`EwpgComplianceModule` riunisce numero massimo di investitori, saldo massimo per investitore,
paesi bloccati, intervallo minimo tra trasferimenti (cooldown) ed esenzione per i pool di
nominee. Tutte le impostazioni sono memorizzate per contratto `ModularCompliance`. I suoi setter
ricevono questo indirizzo di conformità come primo argomento, ad esempio
`setMaxInvestors(address compliance, uint256)`.

- **Chi può configurarlo.** Solo il proprietario del contratto di conformità può chiamare un
  setter, oppure la conformità stessa tramite `callModuleFunction`. Qualsiasi altro chiamante
  fallisce con `CallerNotComplianceAdmin`. T-REX trasferisce la proprietà in due passaggi, quindi
  dopo il deployment di una suite il wallet del registro è solo proprietario *in attesa*. Il
  backend chiama `acceptOwnership()` sulla conformità al momento del deployment. Se questo
  passaggio è fallito, lo ripete prima della successiva modifica di un modulo di conformità.
- **Aggiunta dal backend.** Il backend collega il modulo, invia i setter e rilegge il risultato
  con `getConfig(address)` e `isCountryBlocked(address,uint16)`. Scrive la riga nel database solo
  quando ogni valore è on-chain. Se la configurazione fallisce, scollega il modulo e segnala
  l'errore.
- **«Investitore» significa ONCHAINID.** Saldi e numero di investitori sono sommati per
  identità: più wallet collegati allo stesso ONCHAINID condividono un unico limite di saldo e
  contano come un solo investitore. I trasferimenti tra due wallet della stessa identità sono
  sempre consentiti.
- **Paese sconosciuto.** Finché almeno un paese è bloccato, un destinatario senza paese
  registrato (`0`) viene rifiutato.

#### Migrare una suite in produzione al modulo corretto { #migrating-a-live-suite-to-the-fixed-module }

I contratti distribuiti prima di questa correzione usano il vecchio modulo. In esso **chiunque**
può modificare le impostazioni e i limiti si applicano per wallet anziché per identità. Il modulo
non è aggiornabile (upgradeable), quindi ogni suite in produzione deve passare a un modulo
distribuito ex novo.

1. Distribuire il nuovo `EwpgComplianceModule`.
2. Come proprietario della conformità (chiama prima `acceptOwnership()` se sei ancora solo
   proprietario in attesa), eseguire `addModule(newModule)` sulla `ModularCompliance` della suite.
3. Configurare il nuovo modulo con i valori registrati nel database
   (`erc3643_compliance_module`): `setMaxInvestors`, `setMaxBalance`, `setTransferCooldown`,
   `blockCountry` e `setNomineePool`. Non copiare i valori dallo stato on-chain del vecchio
   modulo, perché chiunque potrebbe averli modificati.
4. Recuperare i titolari esistenti:
   `syncHolders(compliance, wallets)` con ogni wallet di `erc3643_identity_registry` per la suite
   (più ogni altro wallet di titolare noto all'indicizzatore). La chiamata è idempotente, quindi
   può essere eseguita a lotti e ripetuta in sicurezza.
5. Confrontare `getConfig(compliance)` con il database, incluso il numero di investitori rispetto
   al numero di ONCHAINID distinti con saldo positivo.
6. Eseguire `removeModule(oldModule)` sulla conformità.

!!! warning "Un modulo legacy non può essere collegato dal backend"
    Dopo aver collegato un modulo, il backend rilegge la configurazione con `getConfig(address)`.
    Un modulo distribuito prima di questa correzione non ha `getConfig`, quindi aggiungerlo dal
    backend viene sempre annullato. Non riprovare: distribuisci di nuovo `EwpgComplianceModule` e
    collega la nuova istanza.

Finché una suite non è migrata, genera un avviso per qualsiasi differenza tra `isCountryBlocked` /
i limiti del vecchio modulo e il database. Segnala inoltre agli operatori, come attività
`updateCountry`, ogni voce del registro delle identità con paese `0`, verificata on-chain con
`investorCountry(wallet)`.

## Passaggio 7: ruoli dell'agente { #step-7-agent-roles }

Il portafoglio backend del registro deve contenere ruoli dell'agente su ciascun token distribuito per eseguire operazioni di gestione. Lo script di distribuzione li concede automaticamente.

| Ruolo | Consente |
|------|--------|
| Agente del registro delle identità | `registerIdentity`, `updateIdentity`, `deleteIdentity` |
| Agente token | `mint`, `burn`, `freezePartialTokens`, `forcedTransfer` |
| Agente di conformità | `addModule`, `removeModule`, `callModuleFunction` |

Per concedere manualmente i ruoli agente (se necessario):

```bash
cast send $IDENTITY_REGISTRY \
  "addAgent(address)" $BACKEND_OPERATOR_ADDRESS \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY

cast send $TOKEN \
  "addAgent(address)" $BACKEND_OPERATOR_ADDRESS \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```
