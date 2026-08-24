---
title: Reporting sulle transazioni MiFIR
description: Prototipo di esportazione di transazioni in stile MiFIR, DRAFT_UNVALIDATED; non un'implementazione di presentazione RTS 22.
---

# Prototipo di esportazione di transazioni a forma di MiFIR { #mifir-shaped-transaction-export-prototype }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    La classificazione dello strumento, lo stato dell'entità segnalante, la notificabilità, le esenzioni, le scadenze, l'autorità competente, il percorso di presentazione, gli obblighi di correzione e la conservazione richiedono un'attuale revisione
    specifica dell'operatore, dello strumento, della transazione, della sede, della giurisdizione e dell'implementazione
    da parte di un consulente qualificato e del proprietario responsabile della segnalazione. Questa pagina non costituisce consulenza legale o prova della conformità MiFIR.

!!! danger "DRAFT_UNVALIDATED — NON PRESENTARE"
    L'output attuale è un prototipo incompleto, costruito a mano. Non è convalidato rispetto a uno schema RTS 22 ufficiale
    e non deve essere utilizzato per la presentazione legale. La generazione, l'archiviazione di oggetti, l'hashing
    o il trasporto SFTP non significano che un report sia stato presentato, riconosciuto, accettato o
    legalmente completo.

## Comportamento attuale del repository { #current-repository-behavior }

`MifirReportingService` viene eseguito in base a una pianificazione e può essere attivato tramite
`POST /api/v1/regulatory-reporting/mifir/generate`. Per ciascuna delle etichette di autorità configurate:

1. seleziona le righe di esecuzione dell'operazione create durante la data richiesta per gli asset emessi nel sottoinsieme di giurisdizioni DE/FR codificato a livello di codice;
2. crea un piccolo documento XML contenente un set limitato di identificatori, quantità, prezzo e valori di timestamp;
3. memorizza i byte generati e un hash; e
4. richiama il gateway generico configurato.

Ogni documento generato e il record di tracciamento associato devono essere trattati come
`DRAFT_UNVALIDATED`, indipendentemente dai nomi di stato del database legacy.

## Controlli della popolazione mancanti { #missing-population-controls }

Il prototipo attualmente non applica:

- una classificazione MiFID II/MiFIR a livello di strumento o una decisione in materia di notificabilità;
- capacità dell'entità segnalante o della sede;
- stato di regolamento;
- esenzioni delle transazioni;
- identificazione dell'acquirente/venditore e del decisore richiesta dal regime target;
- deduplicazione, correzione, cancellazione o gestione delle segnalazioni in ritardo; o
- giurisdizione completa e instradamento dell'autorità competente.

La selezione utilizza `TradeExecution.created_at`; non è una popolazione basata sulla data di regolamento o su esecuzioni confermate in modo indipendente.

## Campi obiettivo: non attualmente implementati { #target-fields-not-currently-implemented }

Campi quali identificativi dell'acquirente e del venditore, identità dell'impresa segnalante, sede MIC, capacità,
dati dei decisori, indicatori di vendita allo scoperto, i campi relativi a deroghe (waiver) e materie prime, e altri
contenuti dell'RTS 22 restano requisiti target. La loro menzione in un documento di progettazione non deve essere
letta come una mappatura attuale.

## Confine tra XML e trasporto { #xml-and-transport-boundary }

Non esistono implementazioni `MifirFilingStrategy` per giurisdizione né adattatori di presentazione certificati
dall'autorità. Il servizio emette XML costruito a mano; ciò non dimostra la conformità dello schema, delle
regole aziendali, dei dati di riferimento o della firma.

Il gateway generico può essere `NOOP` o SFTP. Un caricamento SFTP riuscito dimostra solo che i byte sono stati
trasportati su un server configurato. Non dimostra la consegna all'autorità competente, la presentazione
legale, il riconoscimento, la convalida o l'accettazione. Stati legacy come
`SUBMITTED`, `PENDING_ACK`, `ACCEPTED` o `REJECTED` non devono essere presentati come esiti dell'autorità
senza una ricevuta dell'autorità autenticata e analizzata in modo indipendente.

Il nuovo tentativo automatico a tre invii, l'acquisizione di ricevute specifiche dell'autorità, la correzione
dei rifiuti e la notifica al regolatore non sono implementati.

## Condizione di rilascio { #release-condition }

L'uso in produzione resta bloccato finché non saranno implementati e verificati end-to-end il perimetro di
reporting, i dati di origine completi, la convalida rispetto allo schema ufficiale e alle regole aziendali,
il canale certificato dall'autorità, il ciclo di vita della ricevuta autenticata, il modello di
deduplicazione/correzione, la titolarità operativa e l'approvazione legale.
