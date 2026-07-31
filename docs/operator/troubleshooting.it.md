---
title: Risoluzione dei problemi
---

# Risoluzione dei problemi { #troubleshooting }

Questa pagina copre i problemi più comuni riscontrati durante il funzionamento del registro eWpG, insieme alle relative cause e soluzioni.

## Errori Blockchain / RPC { #blockchain-rpc-errors }

### "Chiamata Blockchain RPC non riuscita" in registri backend { #blockchain-rpc-call-failed-in-backend-logs }

**Sintomo**: i registri backend mostrano `BlockchainException: RPC call failed for chain mainnet` e le chiamate API restituiscono HTTP 502.

**Causa**: l'endpoint RPC configurato non è raggiungibile o sta restituendo errori.

**Soluzione**:

1. Testare manualmente l'endpoint RPC:

   ```bash
   curl -X POST $ETH_MAINNET_RPC \
     -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
   ```

2. Se restituisce un errore, passa a un backup RPC in `.env` e riavvia il backend
3. Controlla la pagina di stato del tuo fornitore RPC per gli incidenti in corso
4. Prendi in considerazione l'aggiunta di un fallback RPC URL nella configurazione della catena

### La transazione di distribuzione del token non conferma mai { #token-deployment-transaction-never-confirms }

**Sintomo**: dopo aver fatto clic su "Distribuisci su Blockchain", lo stato rimane su "Distribuzione in corso" a tempo indeterminato.

**Causa**: la transazione di distribuzione è stata inviata ma mai confermata (ad es. prezzo del gas troppo basso, congestione della rete).

**Soluzione**:

1. Prendi nota dell'hash della transazione dalla pagina dei dettagli dell'emissione
2. Cercalo nel Block Explorer: è in sospeso o abbandonato?
3. Se in sospeso, attendi che la congestione della rete venga eliminata o utilizza `cast` per accelerare:

   ```bash
   cast send --gas-price 150gwei <tx-hash> --rpc-url $RPC_URL --private-key $DEPLOYER_KEY
   ```

4. In caso di interruzione, il backend riproverà automaticamente ogni 5 minuti (fino a 3 volte)
5. Se tutti i tentativi falliscono, l'emissione ritorna allo stato APPROVED: fai nuovamente clic su Distribuisci

---

## Lacune nell'indicizzatore { #indexer-gaps }

### Il sottografo non si sincronizza { #subgraph-is-not-syncing }

**Sintomo**: viene visualizzato il dashboard catena come "DEGRADED" o "CRITICAL". I log del graph-node mostrano che l'indicizzazione è bloccata.

**Soluzione**:

1. Controllare i log del graph-node:

   ```bash
   docker compose logs --tail=50 graph-node | grep -i "error\|failed\|panic"
   ```

2. Controlla lo stato di RPC, spesso causato dalla limitazione della velocità del provider RPC sul graph-node
3. Aggiungi un secondo provider RPC come fallback in `graph-node.toml`
4. Riavvia il graph-node:

   ```bash
   docker compose restart graph-node
   ```

5. Se il sottografo è bloccato con un errore fatale, ridistribuirlo (vedi [The Graph](./indexers/the-graph.md))

### Eventi di trasferimento mancanti nel registro { #missing-transfer-events-in-registry }

**Sintomo**: un trasferimento visibile su Block Explorer non viene visualizzato nel registro.

**Causa**: l'indicizzatore era dietro la testa della catena al momento del trasferimento, oppure il sottografo è stato ridistribuito da un blocco iniziale dopo il trasferimento.

**Soluzione**:

1. Controlla lo stato corrente dell'indicizzatore:

   ```bash
   curl http://localhost:8080/api/v1/admin/chains \
     -H "Authorization: Bearer $OPERATOR_JWT" \
     | jq '.[].latestIndexedBlock'
   ```

2. Se l'indicizzatore ha recuperato il ritardo e l'evento è ancora mancante, eseguire un confronto controllato in modo indipendente
degli eventi del sottografo rispetto a `eth_getLogs` per l'intervallo interessato. L'endpoint di amministrazione
pianificato `verify-consistency` non è implementato.

3. Se viene confermata una lacuna, ridistribuire il sottografo da un blocco prima dell'evento mancante

---

## Errori di caricamento KYC { #kyc-upload-errors }

### Errore "Documento troppo grande" { #document-too-large-error }

**Sintomo**: il caricamento del documento KYC non riesce con "la dimensione del file supera il limite".

**Causa**: il limite predefinito per la dimensione del documento è 20 MB (plug-in `request-size-limiting` di Kong).

**Soluzione**: comprimere il documento prima del caricamento. Se il documento originale supera i 20 MB, chiedi al cliente di fornire una versione compressa. Come operatore, puoi aumentare il limite in `gateway/kong.yml`:

```yaml
plugins:
  - name: request-size-limiting
    config:
      allowed_payload_size: 50  # MB
```

### "Caricamento S3 non riuscito" nei log di backend { #s3-upload-failed-in-backend-logs }

**Sintomo**: i log di backend mostrano `S3UploadException` quando un documento KYC viene salvato.

**Causa**: le credenziali S3 non sono corrette, il bucket non esiste o la policy IAM non consente `PutObject`.

**Soluzione**:

1. Verifica le credenziali S3 in `.env`
2. Testare l'accesso S3:

   ```bash
   aws s3 ls s3://your-kyc-bucket
   ```

3. Assicurati che la policy IAM includa `s3:PutObject`, `s3:GetObject` e `s3:DeleteObject` per il bucket

---

## Errori di autenticazione { #authentication-errors }

### "Convalida JWT non riuscita" — Risposte 401 { #jwt-validation-failed-401-responses }

**Sintomo**: le richieste API restituiscono 401 con `JWT validation failed` anche se il token sembra valido.

**Causa**: l'emittente del token non corrisponde a `JWT_ISSUER_URI` oppure l'endpoint JWKS è irraggiungibile.

**Soluzione**:

1. Decodifica JWT su [jwt.io](https://jwt.io) e verifica che la dichiarazione `iss` corrisponda al tuo `JWT_ISSUER_URI`
2. Verifica che il backend possa raggiungere l'endpoint JWKS:

   ```bash
   docker exec registerwerk-backend-1 \
     curl ${JWT_ISSUER_URI}/.well-known/jwks.json
   ```

3. Se l'endpoint JWKS non è raggiungibile dall'interno della rete Docker, impostare `JWT_JWKS_URI` esplicitamente

### Gli utenti non possono accedere dopo la modifica dell'IdP { #users-cannot-log-in-after-idp-change }

**Sintomo**: dopo aver passato un'entità cliente a un IdP personalizzato, i loro utenti ottengono "Accesso negato".

**Soluzione**:

1. Verificare che il reindirizzamento URI dell'IdP del cliente sia impostato correttamente
2. Testare l'integrazione dell'IdP tramite **Entità → [entità] → Provider di identità → Test**
3. Controllare i log del backend per l'errore OIDC specifico (solitamente `redirect_uri_mismatch` o `invalid_client`)

---

## Problemi del database { #database-issues }

### Il backend non si avvia: migrazione Flyway error { #backend-fails-to-start-flyway-migration-error }

**Sintomo**: il contenitore backend si chiude all'avvio con `FlywayException: Validate failed`.

**Causa**: un file di migrazione è stato modificato dopo l'applicazione oppure le migrazioni non sono in ordine.

**Soluzione**:

```bash
# Check current migration state
docker exec registerwerk-postgres-1 \
  psql -U ewpg -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;"
```

Se una migrazione mostra `success = false`, correggi la migrazione SQL e riavvia. Non modificare mai i file di migrazione che sono già stati applicati alla produzione.

### Disco pieno sul volume PostgreSQL { #disk-full-on-postgresql-volume }

**Sintomo**: il backend restituisce 500 errori. I log di Postgres mostrano `FATAL: could not write to file`.

**Soluzione**:

1. Identificare le tabelle più grandi:

   ```sql
   SELECT relname, pg_size_pretty(pg_total_relation_size(relid))
   FROM pg_catalog.pg_statio_user_tables
   ORDER BY pg_total_relation_size(relid) DESC LIMIT 10;
   ```

2. La tabella `audit_log` è suddivisa in intervalli per mese. Elimina le vecchie partizioni se il disco è critico:

   ```sql
   DROP TABLE audit_log_y2024m01;
   ```

3. Espandi il volume Docker e riavvia PostgreSQL

# Risoluzione dei problemi { #troubleshooting }

## Il backend non si avvia { #backend-wont-start }

**Sintomo**: `Connection refused` su `localhost:8080`

1. Controlla la connettività DB: `docker compose logs postgres`
2. Controlla le migrazioni Flyway: cerca `FlywayException` nei log di backend
3. Verificare che le variabili di ambiente richieste siano impostate (in particolare `DB_PASSWORD`, `JWT_ISSUER_URI`)

## I token non vengono visualizzati nella cronologia { #tokens-not-appearing-in-history }

**Sintomo**: `GET /api/v1/assets/{id}/history` restituisce vuoto

1. Controlla che il graph-node sia in esecuzione: `curl http://localhost:8020/health`
2. Controlla che il sottografo sia distribuito: `curl http://localhost:8000/subgraphs/name/ewpg/ethereum-sepolia`
3. Controllare lo stato dell'indicizzatore: `SELECT * FROM indexer_state;`
4. Verificare che `graphNodeUrl` e `graphSubgraphName` siano impostati nella configurazione della catena

## La distribuzione ERC-3643 non riesce { #erc-3643-deployment-fails }

**Sintomo**: `POST /api/v1/assets/{id}/deployments` restituisce 500

1. Controlla che il portafoglio deployer abbia ETH per gas
2. Verificare che `REGISTRY_WALLET_PRIVATE_KEY` sia impostato
3. Verificare che il sottomodulo T-REX sia inizializzato: `ls contracts/lib/erc3643/`
4. Cerca l'errore `Web3j` nei log del backend

## Kong restituisce 401 Non autorizzato { #kong-returns-401-unauthorized }

Kong non convalida i JWT: un 401 proviene sempre dal **backend**, anche sulle richieste
proxied tramite Kong. Controllare, nell'ordine:

1. `JWT_ISSUER_URI` corrisponde a `iss` il tuo fornitore OIDC restituisce effettivamente
2. `JWT_AUDIENCE` corrisponde a `aud` del token: una mancata corrispondenza qui è la causa più comune
3. Per un token operatore, `iss` è `registerwerk-local`; i token locali senza di esso sono
rifiutati dalla progettazione, quindi un token realizzato a mano in cui manca `iss` sarà sempre 401
4. Decodifica il token per esaminarne le richieste

Se il token è valido e ottieni **403**, il token va bene e il *ruolo* non è —
un problema completamente diverso. Vedere [Ruoli e autorizzazioni](customers/roles.md).

## Token di onboarding scaduto { #onboarding-token-expired }

Rigenerare tramite API:
```bash
curl -X POST http://localhost:8000/api/v1/onboarding/tokens \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -d '{"legalEntityId": "<uuid>", "recipientEmail": "admin@acme.de"}'
```

I vecchi token vengono invalidati automaticamente (indice univoco parziale `WHERE used_at IS NULL`).

## Il trasferimento del token confidenziale non riesce su Fhenix { #confidential-token-transfer-fails-on-fhenix }

1. Assicurati che il client utilizzi `fhevmjs` per crittografare l'importo
2. Verificare che l'ONCHAINID dell'investitore abbia un'attestazione KYC valida
3. Controlla che l'indirizzo dell'investitore sia inserito nella whitelist nell'IdentityRegistry del token
4. Il testnet Fhenix potrebbe avere account limitati al faucet: verifica il bilancio del gas
