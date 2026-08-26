---
title: Processo KYC
---

Questa pagina descrive il flusso di lavoro dell'operatore per i record KYC basati sulla giurisdizione e le decisioni relative alle liste di controllo. Questi controlli non sono ancora applicati in modo uniforme su ogni percorso di emissione, distribuzione, ricezione o trasferimento.

## Ambito e modello { #scope-and-model }

Registerwerk memorizza:

- Stato KYC a livello di entità (`legal_entity.kyc_status`) per il ciclo di vita dell'onboarding generico.
- Approvazioni KYC a livello di giurisdizione (`kyc_jurisdiction_approval`) per l'idoneità all'emissione in regimi specifici.
- Documenti contrassegnati con giurisdizione (`kyc_document.jurisdiction`) dove `null` significa prova universale che può soddisfare più giurisdizioni.

## Gestione dei documenti { #document-handling }

- I file di dimensioni inferiori a 5 MB vengono archiviati in PostgreSQL.
- I file di dimensioni pari o superiori a 5 MB vengono archiviati in un archivio di oggetti compatibile con S3.
- Le azioni di accesso e di modifica dello stato vengono scritte nell'audit trail.

## Caricamento delle prove KYC { #uploading-kyc-evidence }

```bash
curl -X POST http://localhost:48000/api/v1/entities/{entityId}/kyc/documents \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -F "file=@certificate.pdf" \
   -F "documentType=INCORPORATION_CERTIFICATE" \
   -F "jurisdiction=DE_EWPG"
```

Se `jurisdiction` viene omesso, il documento viene trattato come universale.

## Elenco di controllo di conformità prima dell'approvazione { #compliance-checklist-before-approval }

Prima dell'approvazione della giurisdizione, il backend calcola la conformità rispetto al profilo configurato per quella giurisdizione.

```bash
GET /api/v1/entities/{entityId}/kyc/compliance/{jurisdiction}
```

La risposta include:

- `missingCount`
- `expiredCount`
- `tooOldCount`
- `fullyCompliant` (un nome a livello di codice che indica nessuna lacuna nella lista di controllo configurata, non conformità legale)

## Approvazione della giurisdizione KYC { #approving-jurisdiction-kyc }

Modello di autorizzazione:

- `ROLE_COMPLIANCE_OFFICER`: può approvare solo se `fullyCompliant=true` per la checklist configurata.
- `ROLE_REGISTRY_ADMIN`: può approvare i casi con o senza lacune della checklist configurata.
- L'approvazione con lacune nella lista di controllo richiede `overrideNote` e viene rifiutata per gli utenti non amministratori.

Approvazione senza lacune nella lista di controllo configurate:

```bash
curl -X POST http://localhost:48000/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/approve \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -H "Content-Type: application/json" \
   -d '{"expiresAt":"2027-01-31"}'
```

Se esistono lacune nella checklist, l'approvazione viene bloccata a meno che non venga fornita una nota di override esplicita:

```bash
curl -X POST http://localhost:48000/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/approve \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -H "Content-Type: application/json" \
   -d '{"overrideNote":"Approved by compliance officer after manual source-of-funds review."}'
```

Quando si utilizza l'override, la nota viene archiviata in `kyc_jurisdiction_approval.override_note` e i contatori di conformità vengono inclusi nell'evento di controllo.

## Flusso di lavoro di rifiuto { #rejection-workflow }

```bash
curl -X POST http://localhost:48000/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/reject \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -H "Content-Type: application/json" \
   -d '{"reason":"Missing certified beneficial ownership register extract."}'
```

## Attestazioni on-chain e controlli token { #on-chain-claims-and-token-controls }

L'approvazione della giurisdizione è disponibile per i flussi di lavoro di backend, ma un punto di controllo operativo centrale a rifiuto in caso di errore (fail closed) non è ancora applicato in modo uniforme. I contratti ERC-3643 e le attestazioni ONCHAINID forniscono restrizioni separate a livello di contratto, ove configurate; non dimostrano l'applicazione KYC completa né l'idoneità legale.

## Nota normativa { #regulatory-note }

Questo insieme di funzionalità supporta la documentazione dei controlli KYC e la loro verificabilità. Di per sé, non assolve agli obblighi di licenza, di segnalazione delle operazioni sospette, di Travel Rule o di vigilanza locale.

## Rendicontazione degli override { #override-reporting }

I revisori e gli amministratori possono elencare le approvazioni in override per giurisdizione e periodo:

```bash
GET /api/v1/audit/reports/kyc-overrides?jurisdiction=DE_EWPG&from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z
```
