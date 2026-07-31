---
title: Registro delle revisioni di assurance di Registerwerk
description: Il record di controllo proposto per una futura revisione multidisciplinare di Registerwerk — non è prova che una revisione abbia avuto luogo.
---

# Registro delle revisioni di assurance di Registerwerk { #registerwerk-assurance-review-ledger }

Ultimo aggiornamento: 2026-07-29

> **Non è stata effettuata alcuna revisione descritta in questo documento.** Nessun comitato di dominio e nessun comitato IT
> è stato convocato, nominato o consultato. Ogni voce di seguito è stata scritta da un collaboratore automatizzato
> come struttura di revisione *proposta* e autovalutazione del repository. Leggilo
> come un piano per una revisione futura, mai come una prova che sia avvenuta. Una modifica del codice completata non è
> una certificazione legale. Gli elementi che dipendono dai termini dello strumento, dalla licenza dell'operatore, da prove esterne, dalla configurazione dell'implementazione o da un consulente qualificato rimangono indecisi.

Questo documento propone il record di controllo per una futura revisione multidisciplinare di Registerwerk:
cosa verrebbe esaminato, da chi e quali prove richiederebbero ciascun verdetto.

## Protocollo decisionale proposto { #proposed-decision-protocol }

Vengono proposti i seguenti panel, senza personale. I pannelli di dominio coprirebbero l’emissione e il regolamento di obbligazioni, i pagamenti, la criminalità finanziaria e la conformità normativa, le attività crittografiche e il commercio, l’audit e i pronti contro termine/prestiti. Un comitato IT si occuperebbe di progettazione e implementazione del software, architettura, SRE, frontend e crittografia.

Secondo la proposta, il comitato IT assegnerebbe un punteggio da 0 a 2 in termini di fedeltà invariante legale, correttezza del registro, architettura, sicurezza/privacy, ciclo di vita dei dati, UX/accessibilità, operabilità e verifica. Una proposta sarebbe:

- approvato a 14–16 senza zero nelle prime cinque dimensioni;
- approvato con modifiche a 9–13;
- respinto a 0–8.

Il consiglio sarebbe in grado di porre il veto all'accesso tra tenant, alle chiavi non sicure, al denaro in virgola mobile, al regolamento non idempotente, alle migrazioni irreversibili, doppio controllo indebolito, input illimitato, gestione di finalità/riorganizzazione mancante, riconciliazione non osservabile o invariante legale senza criteri di accettazione.

Gli stati proposti per questo registro sono `PENDING`, `BLOCKED_DECISION`, `APPROVED`, `IN_PROGRESS`, `VERIFIED`, `DISMISSED` e `RESIDUAL_RISK`. Nessuno è stato assegnato da un revisore.

## Copertura delle recensioni { #review-coverage }

Ogni cella è `Not performed`. La colonna dell'ambito registra ciò che una recensione *coprirebbe*. L'autovalutazione automatizzata
viene tracciata separatamente in `docs/claims/registry.json`, dove riporta lo stato
`SELF_ASSESSED_UNREVIEWED`.

| Fase | Pezzi | Revisione del dominio | Revisione informatica | Implementazione |
|---|---|---|---|---|
| Inventario | Backend Spring e 31 moduli di dominio; Contratti EVM, Cairo e DAML; Indicizzatori EVM/Solana/Canton/Starknet/Stellar; operatore e investitore App Angular più UI condivisa; relè di token confidenziali; Kong, Compose, Helm e monitoraggio; documentazione | Non eseguito | Non eseguito | Solo riferimento |
| 0 — invarianti | Modello attore/capacità, perimetro dello strumento, autorità di registro, unità patrimoniali e monetarie, finalità, crediti, release gates | Non eseguito | Non eseguito | Parziale, solo autovalutazione |
| 1 — Autorità e conformità | Autenticazione, autorizzazione, organizzazioni, KYC/AML, screening, Travel Rule, approvazioni giurisdizionali, audit, privacy, punto di controllo operativo centrale | In attesa | In attesa | In attesa |
| 2 — emissione e regolamento | Ciclo di vita/distribuzione delle risorse, standard dei token, contratti di identità/conformità, registro, pagamenti, DvP, custodia, indicizzatori, azioni aziendali | In attesa | In attesa | In attesa |
| 3 — mercati e reporting | Mercato/negoziazione, pronti contro termine/prestiti, Oracle e NAV, servizi obbligazionari, MiFIR, DAC8/KStTG, DORA e profili giurisdizionali | In attesa | In attesa | In attesa |
| 4 — Interfacce utente | Interfaccia operatore, interfaccia utente investitore, interfaccia utente condivisa, contratti API, accessibilità e presentazione sicura delle transazioni | In attesa | In attesa | In attesa |
| 5 — operazioni | CI, dipendenze, contenitori, Kong, Helm, segreti, policy di rete, monitoraggio, backup/ripristino, SLO e runbook | In attesa | In attesa | In attesa |
| Chiusura | Test completi, prove di riproduzione/riconciliazione, riconciliazione delle richieste, note di migrazione e approvazione del rischio residuo | In attesa | In attesa | In attesa |

## Modello canonico Fase 0 { #phase-0-canonical-model }

### Autorità e finalità { #authority-and-finality }

Ciascun strumento deve avere una decisione perimetrale con versione che nomina il registro legale, il libro mastro tecnico, la direzione di proiezione, il tenutore del registro e le prove richieste per effetto legale. La selezione dello standard token non deve classificare lo strumento.

Il sistema non deve comprimere queste dimensioni in un flag `SETTLED`:

`INITIATED → EXECUTED → TECHNICALLY_FINAL → CASH_CONFIRMED → REGISTER_POSTED → RECONCILED → LEGALLY_EFFECTIVE`

Per gli strumenti eWpG tedeschi, il record del titolare del database corrente è solo il registro legale affermato in attesa di una specifica dello strumento, politica dell’autorità approvata dal consulente. Una transazione a catena da sola non deve essere descritta come una nuova registrazione legale. Lussemburgo, Francia e Liechtenstein hanno bisogno di decisioni sui propri strumenti piuttosto che ereditare il modello tedesco. La base per la distinzione tedesca è l'attuale versione ufficiale della [eWpG](https://www.gesetze-im-internet.de/ewpg/BJNR142310021.html); le decisioni rilevanti sui prodotti e sugli operatori per la Francia e il Lussemburgo devono essere confrontate con la [guida AMF sul regime pilota DLT](https://www.amf-france.org/en/news-publications/depth/pilot-regime) e la [normativa sui titoli dematerializzati del Lussemburgo](https://www.cssf.lu/en/Document/law-of-6-april-2013/).

### Convenzioni sulle unità { #unit-conventions }

| Valore | Convenzione canonica |
|---|---|
| Quantità registrata | Titoli con esplicito `quantityScale`; la conversione in unità base a catena richiede `tokenDecimals` |
| Valuta | ISO-4217 unità principali nei modelli backend più esponente valutario esplicito e arrotondamento |
| Valore nominale delle obbligazioni | Principali unità monetarie per intera unità di titolo |
| Prezzo di emissione | Frazione adimensionale del valore nominale; `1.00` significa 100% |
| Cedola fissa | Tasso decimale annuo; la cedola utilizza capitale × tasso annuo × frazione di conteggio dei giorni contrattuali, arrotondata per beneficiario |
| Prezzo commerciale | Principali unità monetarie per intera unità di titolo con valuta esplicita |
| Pagamento tramite gettone | Unità base esatte del token dopo la conversione decimale verificata |
| ERC-4626 NAV | WAD punto fisso; `1e18` indica un'unità base sottostante per unità base azionaria |
| Prezzo pronti contro termine | Unità base del token di prestito per intero token di garanzia a zero decimali |
| Tasso/indice pronti contro termine | WAD; LTV, fattore di riserva e premio di liquidazione utilizzano punti base |
| Tempo | Calendario/fuso orario legale per le date contrattuali; UTC prova di blocco istantanea e canonica per eventi a catena |

### Riferimento delle affermazioni { #claim-baseline }

| Affermazione | Riscontro | Disposizione richiesta |
|---|---|---|
| “Pienamente conforme” in DE/LU/FR/LI | Falso come affermazione incondizionata | Sostituire con decisioni con ambito, comprovate e in scadenza per strumento e operatore |
| Ogni emittente/destinatario passa KYC prima delle azioni valore | Falso | Punto di controllo operativo centrale lato server più prove di documenti esaminati/BO/screening |
| Il database o blockchain è universalmente autorevole | Documentazione contraddittoria | Selezionare l'autorità per strumento; distinguere il registro legale dal registro tecnico e dalla proiezione |
| La reportistica MiFIR è pronta per la produzione | Segnaposto | Metti in quarantena l'output finché non esistono popolamento, schema RTS 22, correzione/deduplicazione e gestione delle ricevute |
| L'esportazione DAC8 è pronta | Falso/obsoleto per l'attuale implementazione tedesca | Ricostruire attorno alla diligenza degli utenti soggetti a obbligo di comunicazione, residenza fiscale/TIN, flussi, instradamento giurisdizionale, correzioni e decisioni KStTG |
| Binari di pagamento conformi a MiCAR | Falso | Trattare come attestazioni dell'operatore finché non vengono verificate le prove dell'emittente, della classificazione, dell'autorizzazione e del rimborso |
| DORA automazione degli incidenti | Segnaposto | Conservare le registrazioni manuali etichettate come tali; implementare prove di rilevamento, classificazione, instradamento e invio prima di richiedere l'automazione |
| PII è crittografato a riposo | Falso per le colonne relative alle persone fisiche | Correggere la richiesta o implementare la crittografia del campo/dell'applicazione con ciclo di vita e migrazione della chiave |
| Tutte le catene/gli standard sono implementati | Falso | Starknet/Stellar e qualsiasi altra integrazione strutturale devono essere etichettate come segnaposto |
| Il DvP a catena stessa è atomico | Verificato solo per token a trasferimento esatto e una transazione | Aggiungere controlli della gamba esatta, prove di definitività/riorganizzazione e riconciliazione del registro legale |

## Registro delle proposte di fase 0 (autovalutato, non revisionato) { #phase-0-proposal-register-self-assessed-unreviewed }

| ID | Proposta | Autovalutazione | Stato di monitoraggio | Prove registrate / bloccanti |
|---|---|---|---|---|
| M0-3525-A | Risolto il problema con il trasferimento del modulo indirizzo ERC-3525 in modo che l'origine diminuisca e la destinazione aumenti esattamente una volta | Proposto (non rivisto) | SELF_ASSESSED | Solo prove di conservazione del contratto: test di regressione più suite Foundry completa, 449 superati / 31 saltati; ciò non dimostra la riconciliazione indicizzata o del registro legale |
| M0-3525-B| Applicare la politica di sospensione/blocco/lista bianca ai trasferimenti di proprietà dell'intero token | Proposto, modifiche annotate (non revisionate) | IN_PROGRESS | Applica ogni protezione tramite l'hook di proprietà ERC-721, preserva la semantica di mint/burn con indirizzo zero e il bypass delle operazioni forzate e testa entrambe le API di trasferimento oltre all'errore atomico del modulo di indirizzo |
| M0-3525-C | Applica limiti globali e di slot con semantica esplicita cumulativa/in circolazione | Bloccato: è necessaria una decisione | BLOCKED_DECISION | Decidere la semantica cumulativa rispetto a quella in circolazione, il margine di distruzione/rimborso/distruzione forzata, la gerarchia dei limiti, il comportamento di modifica e riduzione; riconciliare emissioni preesistenti/in circolazione per slot |
| M0-7540-A | Disabilita `deposit`, `mint`, `withdraw` e `redeem` sincroni ereditati; pubblicizzare massimi zero | Proposto (non rivisto) | SELF_ASSESSED | Tutti i percorsi sincroni vanno in revert, i massimi sono pari a zero, i test richiesti vengono superati; la suite Foundry completa è terminata con codice 0 |
| M0-7540-B| Associa l'adempimento ai metadati degli strike NAV immutabili e tempestivi | Bloccato: è necessaria una decisione | BLOCKED_DECISION | Decidere i prezzi futuri/storici, il calendario/fuso orario limite, l'età massima, lo sciopero ammissibile, la correzione/sostituzione e l'autorità di valutazione; le richieste legacy rimangono `UNVERIFIED_STRIKE` |
| M0-4626 | Applica il modello di metadati/freschezza e solvibilità di riserva NAV | Bloccato: è necessaria una decisione | BLOCKED_DECISION | Decidere il modello sincrono con copertura in contanti rispetto al modello asincrono con portafoglio gestito, riserve/custodia idonee, buffer di liquidità, commissioni e modulo di rimborso |
| M0-REPO-A | Distruggere azioni scalate con tetto arrotondato in caso di ritiro degli asset e respingere il movimento del valore delle azioni a zero | Proposto (non rivisto) | SELF_ASSESSED | Test di confine sopra l'indice 1e18 più invarianti repo: 3 superati con 256 esecuzioni / 5.120 chiamate ciascuno |
| M0-REPO-B | Impedire la rimozione/aggiunta di valore di un mercato più di una volta | Proposto (non rivisto) | SELF_ASSESSED | Aggiungere nuovamente il test di regressione e il pass completo della suite Foundry; `marketCount` ora rimane unico |
| M0-REPO-C | Rendere l'offerta, il prestito, il rimborso, l'escussione e l'uscita totale delle azioni conservativi e sicuri in caso di overflow | Proposto, modifiche annotate (non revisionate) | PROPOSED | Utilizzare `mulDiv` a prova di overflow, rifiutare unità contabili pari a zero, registrare il debito in modo conservativo, basare il movimento parziale di contanti/garanzie collaterali sui delta del debito effettivi e rendere esplicite le uscite complete; i mercati attivi immutabili richiedono ancora prove di inventario/distensione/sostituzione |
| M0-REPO-RISK | Cadenza/override Oracle, relazione LLTV/bonus, fattore di chiusura e cascata di crediti inesigibili | Bloccato: è necessaria una decisione | BLOCKED_DECISION | Decidere oracolo/cadenza/override del quorum, LLTV/relazione bonus, fattore di chiusura/regola di stallo, cascata delle perdite e termini legali/di custodia collaterali; non modificarli nel batch aritmetico |
| M0-DVP | Verifica esatta della gamba di trasferimento, ID commerciali vincolati a termine e stati finali di backend | Proposto, modifiche annotate (non revisionate) | PROPOSED | Solo batch tecnico: delta saldo di entrambi i conti, ID termine/salt separato dal dominio e ciclo di vita evento/ricevuta provvisorio; i diritti di cancellazione, la soglia di definitività della catena e il percorso di risoluzione legale rimangono decisioni sul prodotto |
| M0-BOND | Normalizzare i decimali, la scadenza, i diritti alla data di registrazione e il rimborso basato sulla quantità | Bloccato: è necessaria una decisione | BLOCKED_DECISION | Decidere il conteggio dei giorni, il calendario aziendale/fuso orario, l'autorità di registrazione/data ex, arrotondamento, ritenuta/sospensione, default/richiamo/modifica e termini di rimborso parziale; mettere in quarantena la scrivania corrente come solo riferimento |
| M0-LEDGER | Rendere monotone le transizioni degli insediamenti, ripristinare l'inventario esattamente una volta e richiedere prove di cassa/consegna indipendenti | Proposto, modifiche annotate (non revisionate) | PROPOSED | Modello additivo di stato/transizione/evidenza/prenotazione; il vecchio `SETTLED` diventa non verificato, i riferimenti dell'acquirente non possono promuovere lo stato e `LEGALLY_EFFECTIVE` rimane non disponibile senza un criterio di autorizzazione configurato |
| M0-INDEXER-A | Ripara la parità di firma del gestore configurata, gli eventi di distribuzione di fabbrica e il rendering degli indirizzi per componente | Proposto (non rivisto) | SELF_ASSESSED | Risultato tecnico limitato: 16 ABI contrattuali/71 gestori configurati, renderer di indirizzi, codegen, build WASM e passaggio wrapper di sola convalida; non dimostra l'identità del codice distribuito |
| M0-INDEXER-B | Aggiungi cursori provvisori/finali, rollback di riorganizzazione e riconciliazione a catena diretta | Proposto, modifiche annotate (non revisionate) | PROPOSED | Costruire un impianto idraulico di riconciliazione provvisorio/orfano/riavvolgimento e checkpoint chiuso in caso di guasto; nessun evento diventa `FINAL` finché non esiste una policy di catena approvata separatamente e una configurazione RPC affidabile |
| M0-INDEXER-C | Tieni traccia del valore ERC-3525 in base a token/proprietario/slot, ciclo di vita durevole della richiesta ERC-7540 inclusa la cancellazione e stato del flusso di cassa ridimensionato/vault del pronti contro termine | Proposto, modifiche annotate (non revisionate) | SELF_ASSESSED | Tutte le 25 entità hanno uno stato di proiezione enum; le prime storie incomplete osservate rimangono `INCOMPLETE`; RepoVault è il flusso di cassa patrimoniale netto firmato, non il capitale; passaggi completi del punto di controllo statico. Non esiste alcuna prova di riproduzione/finalità |
| M0-INDEXER-D1 | Supporta ogni istanza BondDesk/AMM/RepoVault configurata, aggiorna i documenti di migrazione dell'operatore e fai in modo che il gate di test compili le mappature | Proposto, modifiche annotate (non revisionate) | SELF_ASSESSED | Tutte le istanze sono esplicite; `NONE` è un'asserzione dell'operatore; la distribuzione live richiede una nuova etichetta; il ricaricamento di graph-node precede la distribuzione; i blocchi per sorgente e il rollback non distruttivo sono documentati e sottoposti a revisione incrociata |
| M0-INDEXER-D2 | Verifica il bytecode RPC e l'identità hash/componente del codice di runtime approvato prima della distribuzione | Bloccato: è necessaria una decisione | BLOCKED_DECISION | Richiede un inventario autorevole per catena, hash di runtime/proxy/admin approvati, aspettative chiave e policy di rotazione; i controlli sintattici degli indirizzi non sono verifiche dell'identità |
| F0-001 | Perimetro dello strumento, capacità giuridiche, autorizzazioni normative e politica di autorità del registro | Bloccato: è necessaria una decisione | BLOCKED_DECISION | Decisioni dei consulenti/operatori per giurisdizione e strumento; F0-002 può aggiungere un guscio di schema ma non deve seminare alcuna autorizzazione generale attiva |
| F0-002 | `AssetOperationGate` centrale applicato nei servizi e nei percorsi HTTP | Proposto, modifiche annotate (non revisionate) | PROPOSED | Istantanee decisionali a livello di servizio con versione, ambito, in scadenza/revocabili; La policy mancante/obsoleta/non riconosciuta nega senza effetti collaterali DB/catena e registra la correlazione policy/motivo/controllo |
| F0-003 | Documento esaminato, titolare effettivo, giurisdizione e prove KYC di screening aggiornato | Bloccato: è necessaria una decisione | BLOCKED_DECISION | Decidere liste di controllo, revisione/accettazione, cadenza, EDD, completezza/fonte e conservazione del titolare effettivo; i documenti legacy caricati rimangono non revisionati e il punto di controllo operativo nega |
| F0-004 | Termini economici, scale, valute, calendari e arrotondamenti espliciti immutabili | Proposto, modifiche annotate (non revisionate) | PROPOSED | Costruisci solo schemi immutabili/con versione e framework di conversione/calcolo esatto; migrare i termini attuali come `LEGACY_UNVERIFIED` e non inventare convenzioni legame/NAV |
| F0-005 | Stato degli insediamenti multidimensionali e modello di prova | Proposto, modifiche annotate (non revisionate) | PROPOSED | Stesso confine sicuro di M0-LEDGER; `LEGALLY_EFFECTIVE` rimane irraggiungibile senza F0-001 e il vecchio `SETTLED` diventa `LEGACY_SETTLED_UNVERIFIED` |
| F0-006 | Istruzione/accordo autorizzato e registro cronologico delle variazioni di registro | Bloccato: è necessaria una decisione | BLOCKED_DECISION | Decidere istruzioni/accordi/autorità di correzione, firme/prove, sequenziamento e annullamento per tipo di voce/giurisdizione; la cronologia generica di sola aggiunta non può autorizzare una mutazione |
| F0-007 | Finalità della catena e riconciliazione bytecode/admin/configurazione distribuita | Bloccato: è necessaria una decisione | BLOCKED_DECISION | M0-INDEXER-B può aggiungere tubature provvisorie, ma finalità/punto di controllo, RPC/quorum attendibile, runtime/proxy/amministratore/proprietario/chiave e policy di affidamento legale non sono risolti |
| F0-008 | Pagamento verificabile/regolamento DvP; mutazioni canoniche simulate con disabilitazione della produzione | Proposto, modifiche annotate (non revisionate) | IN_PROGRESS | Impostazioni/schema predefiniti per il regolamento iniziale e immediato false; i riferimenti alle parti sono metadati non verificati; combina gambe DvP esatte con prova dell'adattatore indipendente e nessuna mutazione del titolare senza contanti e consegna verificati |
| F0-009 | Istantanea dei diritti bloccati e pagamenti per azioni aziendali verificati in modo indipendente | Bloccato: è necessaria una decisione | BLOCKED_DECISION | Decidere autorità di registrazione/ex data, fuso orario/calendario, imposta/ritenuta, sospensione del titolare bloccato, correzioni e pagamenti inadempienti; i diritti legacy rimangono non verificati |
| F0-010 | Kill switch dei prestiti finché non esistono controlli legali/collaterali e riconciliazione | Proposto, modifiche annotate (non revisionate) | IN_PROGRESS | Esposizione backend/interfaccia utente predefinita disattivata e rifiuto in caso di errore (fail closed); i nuovi mercati sospendono l’offerta e prendono prestiti per impostazione predefinita, mentre restano disponibili ritiri/rimborsi che riducono il rischio; i vecchi mercati richiedono inventario/pausa/svolgimento/sostituzione |
| F0-011 | Metti in quarantena gli output MiFIR e DAC8/KStTG come bozze/non convalidati | Proposto, modifiche annotate (non revisionate) | SELF_ASSESSED | Disattivato per impostazione predefinita e proibito quando abilitato in produzione; spazi dei nomi prototipo e `DRAFT_UNVALIDATED`; stati/eventi di solo trasporto; Sono stati superati 20 test di unità/migrazione mirati, incluso PostgreSQL V17→V18 con seeding. Gli schemi ufficiali, il popolamento, l'instradamento, le ricevute e l'approvazione legale rimangono bloccanti |
| F0-012 | Registro delle affermazioni leggibile dalla macchina con prove, ambito, proprietario, scadenza e applicazione della CI | Proposto, modifiche annotate (non revisionate) | SELF_ASSESSED | Schema/validatore chiuso, record canonico e hash di testo/file esatto, confronto di base di sola aggiunta, controlli di scadenza/indipendenza, una singola eccezione di migrazione immutabile inclusa nella lista consentita, scansione del repository con chiusura in caso di errore e prove CI di gating obbligatorie sono state autovalutate da un collaboratore automatizzato senza revisione esterna. Riesecuzione corrente: verificatore/regressioni, ERC-3525 (17/17), reporting backend (20/20 inclusa la migrazione PostgreSQL) e passaggio di porte statiche/codegen/WASM sottografo completo. Questa è governance, non certificazione legale |

## Evidenze di base { #baseline-evidence }

| Superficie | Risultato di base | Riscontro |
|---|---|---|
| Backend `./mvnw verify -B` | La linea di base è passata all'esterno della sandbox vincolata; F0-011 combinati unità mirate/suite di migrazione passano 20/20 | I processi pianificati continuano dopo lo smontaggio dell'applicazione di test, generano errori di database di grandi dimensioni e ritardano l'arresto del fork; il JaCoCo effettivo è circa 45,0% linea / 38,6% ramo contro un 36% / 23% gate e una documentazione contrastante del 70% |
| Foundry `forge test -q` | 449 approvati, 31 saltati dopo il primo lotto approvato; ripetizione indipendente terminata 0 | I test di regressione ora coprono la conservazione del trasferimento di indirizzo ERC-3525, il bypass sincrono ERC-7540, l'arrotondamento dei prelievi pronti contro termine e la valutazione di mercato unica |
| Cairo `snforge test` | 29/29 passato | La superficie Cairo necessita ancora di revisione del dominio/sicurezza |
| Relè confidenziale | Il lint è passato; 33/33 test superati | L'audit di dipendenza ha riportato 21 risultati di elevata gravità; il file di lock era assente prima dell'installazione di base |
| EVM sottografo | 16 contratti ABI/71 gestori, 25 entità con stato di proiezione, renderer multiistanza, codegen, tutte le build di mappatura e le porte di distribuzione etichettate superano | Riorganizzazione/finalità, riproduzione live/riconciliazione e identità bytecode RPC rimangono in sospeso; il controllo delle dipendenze riporta ancora 45 risultati, inclusi due critici |
| Operatore/investitore App Angular | I comandi CI falliscono | Entrambi i flussi di lavoro richiamano una destinazione di lint mancante; Karma prevede la mancanza di `karma-jasmine`; nessun file delle specifiche trovato |
| Docusaurus documenti | Le build di produzione inglese e tedesca passano dopo le correzioni Truth/Build | L'audit delle dipendenze ha comunque riportato 37 risultati, inclusi due critici |
| DAML | Non eseguito | `dpm` non è disponibile nell'ambiente corrente |

## Blocchi operativi e di distribuzione noti { #known-deployment-and-operations-blockers }

- Helm combina un singolo volume del portafoglio `ReadWriteOnce` con 3-10 repliche anti-affinite.
- L'ingresso instrada direttamente al backend e bypassa Kong mentre la policy di rete non ammette il percorso del controller di ingresso.
- Le chiavi segrete PostgreSQL a cui fa riferimento Helm non sono d'accordo.
- I JWT front-end sono archiviati in `localStorage`; le intestazioni di protezione avanzata della risposta sono incomplete.
- Promtail, metriche Kong, avvisi di backup e ipotesi pushgateway non formano un percorso di monitoraggio funzionante.
- Una chiave di distribuzione singola non elaborata non ha un trasferimento multisig/timelock documentato.
- Non esiste copertura CI per il codice frontend condiviso, il relè, Cairo, DAML, diversi indicizzatori, documentazione, Compose/Kong o Helm.

Questi rimangono bloccanti del rilascio finché il verdetto della fase e le prove di verifica non vengono registrati qui.
