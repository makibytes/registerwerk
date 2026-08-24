---
title: DAC8 / CARF
description: Prototipo di esportazione delle partecipazioni DRAFT_UNVALIDATED; non un'implementazione di presentazione DAC8, CARF o KStTG.
---

# Prototipo di esportazione delle partecipazioni in stile DAC8/CARF { #dac8-carf-shaped-holdings-export-prototype }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Lo stato dell'entità segnalante, l'ambito, gli utenti segnalabili e le cripto-attività, gli obblighi di due diligence, i periodi, le scadenze, la giurisdizione, l'autorità competente, le correzioni e la conservazione richiedono una revisione
    corrente specifica per l'operatore, il cliente, il bene, la transazione, la giurisdizione e l'implementazione, condotta da un consulente fiscale/legale qualificato e dal responsabile della segnalazione. Questa pagina non costituisce consulenza legale o fiscale
    e non stabilisce la conformità a DAC8, CARF o al KStTG tedesco.

!!! danger "DRAFT_UNVALIDATED — NON PRESENTARE"
    Il risultato attuale è un prototipo di partecipazioni incompleto e costruito a mano. Non è convalidato
    rispetto a uno schema ufficiale DAC8, CARF o KStTG e non deve essere utilizzato per la presentazione legale.
    La generazione, l'archiviazione degli oggetti, l'hashing o il trasporto SFTP non significano che un rapporto sia stato presentato,
    riconosciuto, accettato o legalmente completo.

## Comportamento attuale del repository { #current-repository-behavior }

`Dac8ExportService` viene eseguito il 31 gennaio per un anno precedente richiesto e può essere attivato su richiesta.
Per ciascuna delle quattro etichette di giurisdizione configurate:

1. interroga i saldi dei detentori attualmente memorizzati per le attività emesse;
2. conta le righe di trasferimento token in entrata per ciascun titolare selezionato;
3. crea un piccolo documento XML utilizzando l'anno richiesto come metadati del rapporto;
4. memorizza i byte generati e un hash; e
5. richiama il gateway generico configurato.

La query non ricostruisce un'istantanea al 31 dicembre né i flussi annuali di acquisizione/cessione.
Ogni documento generato e il relativo record di tracciabilità devono essere trattati come
`DRAFT_UNVALIDATED`, a prescindere dai nomi di stato legacy nel database.

## Controlli di due diligence e popolamento mancanti { #missing-due-diligence-and-population-controls }

Il prototipo attualmente non implementa:

- una classificazione dell'entità segnalante/CASP o una decisione sul perimetro del KStTG tedesco;
- la due diligence degli utenti segnalabili e la classificazione della persona controllante;
- la raccolta, convalida, i codici motivo o l'autocertificazione completi di residenza fiscale e TIN;
- la classificazione e le esclusioni delle cripto-attività segnalabili;
- l'acquisizione, cessione, scambio, trasferimento lordi annuali o l'aggregazione del valore equo di mercato;
- un'istantanea affidabile del saldo di fine anno;
- la selezione della popolazione specifica per giurisdizione o l'instradamento verso la giurisdizione partner; oppure
- la gestione di correzioni, cancellazioni, segnalazioni nulle, duplicati e segnalazioni tardive.

La stessa popolazione del prototipo viene attualmente emessa sotto etichette di giurisdizioni multiple. Un
`crossBorderIndicator`, un trattamento CRS completo per il partner e i campi relativi a utente/entità segnalabile
precedentemente descritti in questa documentazione non sono implementati.

## Dati target — attualmente non implementati { #target-data-not-currently-implemented }

Identità fiscale, residenza, persona controllante, classificazione del bene, tipo di transazione, valutazione,
valuta, aggregazione annuale e campi di fine anno sono requisiti target per l'analisi esterna;
la loro presenza in una tabella di progetto non deve essere descritta come una mappatura di origine attuale.

## Confine tra XML e trasporto { #xml-and-transport-boundary }

Il servizio emette XML costruito a mano e non stabilisce la conformità a uno schema o a regole aziendali ufficiali dell'OCSE, dell'UE o
tedesche. Non esistono adattatori di portale specifici per l'autorità né processori di ricevute autenticati.

Il gateway generico può essere `NOOP` o SFTP. Un caricamento SFTP riuscito dimostra solo che i byte sono stati
trasportati a un server configurato. Non dimostra la consegna a un'autorità fiscale, la presentazione legale, il riconoscimento, la convalida o l'accettazione. Stati legacy come `SUBMITTED`,
`PENDING_ACK`, `ACCEPTED` o `REJECTED` non devono essere presentati come esiti dell'autorità senza una
ricevuta dell'autorità autenticata e analizzata in modo indipendente.

## Tempistica e normativa vigente { #timing-and-current-law }

Non fare affidamento su dichiarazioni storiche secondo cui il primo anno di riferimento era il 2025 o che i portali degli Stati membri
fossero ancora in fase di implementazione nel 2025. I periodi applicabili, i requisiti del KStTG tedesco,
le scadenze di presentazione, gli schemi, i portali e le regole transitorie devono essere verificati rispetto alle fonti ufficiali attuali
durante la revisione esterna.

## Rapporto con MiFIR { #relationship-to-mifir }

La segnalazione delle transazioni ai sensi di MiFIR e la segnalazione fiscale DAC8/CARF/KStTG hanno perimetri legali, popolazioni,
dati, destinatari, periodi e processi di correzione diversi. La condivisione di una tabella di persistenza o di un'interfaccia di trasporto
non dimostra la conformità di nessuno dei due prototipi al proprio regime target.

## Condizione di rilascio { #release-condition }

L'uso in produzione resta bloccato finché non saranno implementati e verificati end-to-end il perimetro di segnalazione, il modello di due diligence,
i dati di origine completi e le istantanee storiche, la convalida rispetto allo schema ufficiale e alle regole aziendali,
il canale certificato dall'autorità, il ciclo di vita delle ricevute autenticate, il modello di correzione, la titolarità operativa
e l'approvazione legale/fiscale.
