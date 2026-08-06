---
title: Concessioni di amministrazione token: Autorità delegabile per l'azione forzata
description: ASSET_TOKEN_ADMIN — il permesso delegabile che regola forcedTransfer/forcedApprove/forceBurn oltre a REGISTRY_ADMIN.
---

# Concessioni amministrative token: Autorità ad azione forzata delegabile { #token-admin-grants-delegatable-forced-action-authority }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Questa pagina registra le mappature dei controlli previste. Non costituisce prova che una delega sia legalmente valida, rientri nell'autorizzazione di un operatore, o sia sufficiente per una correzione, un annullamento, un congelamento, una distruzione (burn) o un trasferimento forzato. Capacità, prove dell'istruzione, separazione dei compiti e regole relative a strumenti/giurisdizioni
    richiedono una revisione esterna.

Le operazioni obbligatorie sui token del registro di Registerwerk — **forcedTransfer**, **forcedApprove**
e **forceBurn** — consentono al registro di spostare, riapprovare o distruggere i token di un titolare senza
il suo consenso. Questi sono gli strumenti più taglienti della piattaforma: una chiamata errata sposta valore
reale verso un indirizzo scelto da un attaccante, oppure lo distrugge del tutto. Finora erano raggiungibili
solo da `REGISTRY_ADMIN` (più, per `forcedTransfer`/`forcedApprove`, qualsiasi emittente che agisce sul
proprio asset semplicemente in virtù del fatto di possederlo).

**`ASSET_TOKEN_ADMIN`** sostituisce la scorciatoia basata sulla proprietà dell'emittente con un'autorizzazione
esplicita, concessa dall'operatore. Per impostazione predefinita **nessuno ce l'ha — nemmeno l'emittente di un
asset.** Un operatore deve delegarla deliberatamente a un'entità cliente specificata (emittente o investitore),
e solo dopo aver verificato che il wallet di tale entità sia un partecipante autentico, inserito nella
whitelist (e, per gli asset ERC-3643, verificato tramite ONCHAINID).

Nota bene cosa **non** cambia: la transazione on-chain effettiva è ancora firmata dal wallet dell'operatore del
registro, esattamente come prima. `ASSET_TOKEN_ADMIN` è puramente un
**gate di autorizzazione a livello di API** — decide chi può *chiedere* al registro di eseguire un'azione
forzata, non chi la *esegue* on-chain.

---

## Cosa regola { #what-it-gates }

| Azione | Percorso operatore | Percorso cliente |
|---|---|---|
| `forcedTransfer` / `forcedTransferSingle` | `TokenAdminController` | `IssuerTokenController` |
| `forcedApprove` | `TokenAdminController` | `IssuerTokenController` |
| `forceBurn` / `forceBurnSingle` | `TokenAdminController` | — (solo operatore) |
| ERC-3643 equivalenti (incl. lotto) | `Erc3643Controller` | `Erc3643Controller` |
| Cantone `force-transfer-canton` / `burn-holding` | `TokenAdminController` | — (solo operatore) |

Ogni endpoint sopra ora richiede `hasRole('REGISTRY_ADMIN')` **o** una concessione attiva
`ASSET_TOKEN_ADMIN` per l'entità del chiamante su quella risorsa specifica (vedi
`AssetAccessChecker.canForceAdmin`). Tutto il resto — pausa, blocco, whitelist,
mint, burn (distruzione, del tipo non forzato) — non viene influenzato.

---

## eWpG §24 / §26 come base di delega (Germania) { #ewpg-24-26-as-the-delegation-basis-germany }

Le azioni forzate corrispondono a disposizioni concrete dell'eWpG: `forcedTransfer` alla **§24 Berichtigung**
(correzione del registro su ordine di BaFin o del tribunale), `forceBurn` alla **§26 Einziehung** (annullamento
obbligatorio). Entrambe le disposizioni descrivono il potere del *custode del registro* di correggere o
cancellare una voce — non contemplano di per sé la delega di tale potere a un cliente. La posizione adottata
da questa funzionalità è che il custode del registro (l'operatore) resta legalmente responsabile di ogni
azione forzata indipendentemente da chi ha avviato la chiamata API; `ASSET_TOKEN_ADMIN` è una **delega
operativa dell'avvio**, non una delega di potere legale — è lo step-up a doppio controllo dell'operatore
(vedi sotto) ad autorizzare effettivamente l'esecuzione, a ogni singola chiamata, indipendentemente dal fatto
che l'iniziatore sia REGISTRY_ADMIN o un cliente beneficiario di una concessione.

**Altre giurisdizioni:** FR, LU e LI non hanno ancora, in questo codice, un concetto direttamente analogo a
"delegare l'avvio di una correzione obbligatoria del registro". Considera la delega a un'entità cliente,
ai sensi dei regimi locali sui titoli/DLT di quelle giurisdizioni, come **non verificata** — ottieni conferma
da un consulente legale locale prima di concedere `ASSET_TOKEN_ADMIN` a un'entità non tedesca in produzione,
in linea con la convenzione di disclaimer usata altrove in questa cartella (ad es.
[Sperrvermerk](sperrvermerk.md)) e con la [panoramica delle giurisdizioni](../legal/index.md).

---

## Modello di concessione { #grant-model }

Due varianti, entrambe create/revocate esclusivamente da `REGISTRY_ADMIN` con
`@RequiresStepUp(requireSecondApprover = true)` (lo stesso TOTP + flusso a 4 occhi utilizzato per le
azioni forzate stesse):

- **Ambito asset** (`POST /api/v1/assets/{assetId}/token-admin-grants`) — il caso comune:
  un asset, un'entità beneficiaria.
- **A livello di entità** (`POST /api/v1/entities/{entityId}/token-admin-grants`) — si applica a
  ogni asset di cui l'entità è emittente/detentore, presente e futuro. Una delega di fiducia
  molto più ampia; da riservare a un emittente fidato e ricorrente, non da usare come impostazione
  predefinita.

### Idoneità, convalidata una volta al momento della concessione { #eligibility-validated-once-at-grant-time }

| Beneficiario | Verifica del wallet |
|---|---|
| Emittente dell'asset (ambito asset) | Wallet associato all'identità organizzativa dell'entità (`orgidentity.PermissionGate.isWalletBoundToEntity`) |
| Un detentore/investitore dell'asset (ambito asset) | `AssetHolder.whitelisted = true` per quel wallet su quell'asset, **più** T-REX `IdentityRegistry.isVerified` se l'asset è ERC-3643/CONF_ERC3643 |
| A livello di entità | Wallet vincolato all'identità organizzativa dell'entità (nessun singolo asset su cui verificare l'inserimento nella whitelist) |

Il controllo superato viene registrato sulla concessione (`eligibilityBasis`) a fini di audit — non viene
riverificato dal vivo a ogni successiva chiamata di azione forzata; viene verificato solo lo stato
`ACTIVE`/non scaduto della concessione stessa. Se un wallet viene successivamente rimosso dalla whitelist o
bloccato, l'operatore deve revocare separatamente la concessione.

### Ciclo di vita { #lifecycle }

Rispecchia il modello `HolderBlock` di [Sperrvermerk](sperrvermerk.md): `ACTIVE → REVOKED` (manuale, step-up +
quattro occhi) oppure `ACTIVE → EXPIRED` (job notturno `@Scheduled` dopo `expiresAt`, se ne è stato impostato uno).

---

## Interfaccia dell'operatore { #operator-ui }

- **Ambito asset** — scheda "Token Admin Grants" nella pagina di dettaglio dell'asset: elenca le concessioni
  attive, concedine di nuove (entità, wallet, configurazione chain opzionale, base giuridica, scadenza
  opzionale), revoca.
- **A livello di entità** — `/compliance/token-admin-grants`: cerca un'entità, gestiscine le concessioni a
  livello di entità. Deliberatamente una pagina separata dalla schermata Permissions dell'ecosistema
  orgidentity, non correlata (`/permissions`) — quella governa le autorizzazioni dell'organizzazione nel
  marketplace dApp e non ha alcuna dimensione legata agli asset.

---

## Audit trail { #audit-trail }

Ogni concessione e revoca pubblica eventi di audit `ASSET_TOKEN_ADMIN_GRANTED` / `ASSET_TOKEN_ADMIN_REVOKED`
(`asset.events.AssetTokenAdminGrantedEvent` / `...RevokedEvent`), acquisiti automaticamente tramite la
[catena di hash di controllo](../platform/audit-log.md) — vengono registrati attore, entità, asset
(o "a livello di entità"), wallet, base giuridica e base di idoneità.
