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
curl http://localhost:8080/api/v1/admin/chains/11155111 \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  | jq '.factoryAddress'
```

## Passaggio 2: impostare il registro come emittente attendibile { #step-2-set-up-the-registry-as-trusted-issuer }

Il portafoglio dell'operatore backend del registro deve essere registrato in `TrustedIssuersRegistry` in modo che possa emettere attestazioni KYC/AML. Questa operazione viene eseguita una volta per ogni distribuzione di fabbrica.

```bash
cast send $TRUSTED_ISSUERS_REGISTRY \
  "addTrustedIssuer(address,uint256[])" \
  $REGISTRY_OPERATOR_ADDRESS "[1,2]" \
  --rpc-url $RPC_URL \
  --private-key $DEPLOYER_PRIVATE_KEY
```

Parametri:
- Primo argomento: indirizzo dell'operatore del registro (portafoglio di distribuzione)
- Secondo argomento: array di ID degli argomenti di attestazione che questo emittente è autorizzato a firmare (1=KYC, 2=AML)

Verificare:

```bash
cast call $TRUSTED_ISSUERS_REGISTRY \
  "isTrustedIssuer(address)(bool)" \
  $REGISTRY_OPERATOR_ADDRESS \
  --rpc-url $RPC_URL
# Expected: true
```

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

1. Costruisce un'attestazione con ID argomento, indirizzo dell'emittente e un hash del record di verifica KYC
2. Firma l'attestazione con la chiave privata dell'operatore
3. Chiama `addClaim` sul contratto ONCHAINID dell'investitore

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
