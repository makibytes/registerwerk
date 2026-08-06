---
title: DORA — Gestione del rischio ICT
description: Prototipo di incidenti ICT, test di resilienza e record di fornitori di terze parti; non un'implementazione DORA completa.
---

# DORA — Atto sulla resilienza operativa digitale { #dora-digital-operational-resilience-act }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Questa pagina registra le mappature dei controlli previste e il comportamento corrente del repository. Non costituisce un consiglio o una prova legale che DORA si applichi a un particolare operatore, che esista un quadro di controllo DORA completo o che un incidente sia stato validamente classificato o segnalato. L'applicabilità, la classificazione
    , le scadenze, le autorità competenti, i moduli, i canali e le prove richiedono una revisione attuale specifica dell'operatore, del servizio, dell'incidente, della giurisdizione e dell'implementazione da parte di un consulente qualificato
    e dei proprietari responsabili della resilienza e della conformità.

Il repository contiene un record operativo manuale per gli incidenti ICT, i test di resilienza e i
fornitori di terze parti. Non è un'implementazione di segnalazione alle autorità.

## Ambito e applicabilità { #scope-and-applicability }

L'applicabilità di DORA non può essere dedotta dal nome del repository, da un valore di giurisdizione `eWpG`, da uno standard di
token o dalla presenza di un modulo `dora`. Le capacità regolamentate dell'operatore e i servizi
effettivamente prestati devono essere classificati esternamente prima di fare affidamento su qualsiasi mappatura di controllo.

Le dichiarazioni sul diritto vigente relative agli articoli DORA, alle norme tecniche, alle soglie di classificazione e alle scadenze di rendicontazione devono essere verificate rispetto alle fonti ufficiali attuali come parte di tale revisione.

## Registrazione dell'incidente corrente { #current-incident-record }

Un operatore autorizzato può creare manualmente un `IctIncident` tramite
`POST /api/v1/dora/incidents`. L'entità attuale registra:

- categoria: `DATA_BREACH`, `SYSTEM_OUTAGE`, `RANSOMWARE`, `THIRD_PARTY_FAILURE` o `OTHER`;
- gravità: `LOW`, `MEDIUM`, `HIGH` o `MAJOR`;
- stato: `DETECTED`, `INVESTIGATING`, `CONTAINED`, `RESOLVED`,
  `REPORTED_TO_AUTHORITY` o `CLOSED`;
- descrizione, etichette degli eventi di origine, timestamp, causa principale, rimedio, assegnazione e un riferimento dell'autorità inserito dall'operatore;
- timestamp di promemoria calcolati dall'applicazione per gli incidenti classificati come `MAJOR`.

Questi valori sono dati operativi inseriti dall'operatore. Uno stato come `REPORTED_TO_AUTHORITY` o
un `authorityRef` registra un'asserzione dell'operatore; l'applicazione non verifica in modo indipendente la ricezione o l'accettazione da parte di un'autorità.

## Monitoraggio delle scadenze { #deadline-monitoring }

`DoraService` esegue un lavoro quotidiano che interroga le scadenze delle domande scadute e scrive messaggi di registro.
Espone inoltre indicatori per i record scaduti. Il lavoro non invia una notifica, non crea un
report formattato dall'autorità, non dimostra che la scadenza configurata è legalmente corretta, né avvisa tutto il
personale responsabile.

Il modello corrente non rappresenta un flusso di lavoro completo di segnalazione iniziale/intermedia/finale.
Gli operatori non devono utilizzare i suoi timestamp come scadenze legali senza un'attuale revisione giuridica e regolamentare.

## Rilevamento automatico degli incidenti: non implementato { #automatic-incident-detection-not-implemented }

Gli eventi di audit interno, deriva della catena (chain-drift), indicizzatore, RPC o screening non vengono classificati automaticamente
e convertiti in record `IctIncident`. `sourceEventType` e `sourceEventRef` sono campi di correlazione forniti manualmente,
non prova di una pipeline di rilevamento automatico.

## Registrazioni ICT di terze parti { #ict-third-party-records }

L'entità `ThirdPartyProvider` memorizza i campi operativi tra cui nome, categoria, criticità,
LEI, paese, date del contratto, note di sub-outsourcing, contatto, SLA, RTO/RPO e un
flag di notifica gestito dall'operatore. I record sono elencati tramite:

- `GET /api/v1/dora/providers`
- `GET /api/v1/dora/providers/expiring`

Questa tabella non è un registro di informazioni DORA completo o approvato dalle autorità. Non è implementata alcuna
esportazione ai sensi dell'art. 28 pronta per l'autorità e convalidata rispetto allo schema.

## Record dei test di resilienza { #resilience-test-records }

Il modulo può registrare ed elencare i metadati dei test di resilienza ed evidenziare i record la cui data di scadenza successiva configurata
è passata. Non esegue un test di resilienza, né convalida le sue prove, né stabilisce l'ambito
TLPT, né certifica il risultato.

## Instradamento e presentazione alle autorità: non implementati { #authority-routing-and-filing-not-implemented }

Il repository non implementa l'instradamento verso le autorità DORA specifico per giurisdizione, moduli o
schemi ufficiali, trasmissione autenticata, ricevute di consegna, correzioni, gestione dei rifiuti o accettazione da parte dell'autorità. Registrare che un incidente è stato segnalato non costituisce prova della presentazione.
