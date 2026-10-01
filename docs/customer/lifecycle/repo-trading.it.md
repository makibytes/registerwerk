---
title: 5a. Negoziazione repo
description: Negoziare e gestire pronti contro termine bilaterali tramite RFQ mirate o diffuse.
---

# Fase 5a — Negoziazione repo

Un **pronti contro termine (repo)** collega due operazioni concordate insieme: vendita di titoli contro contante alla data iniziale e riacquisto di titoli equivalenti per un importo fisso alla scadenza. La differenza è il rendimento repo.

Il Repo Desk modella questo flusso bilaterale. È separato dal [prestito garantito da titoli](repo-lending.md), nel quale la garanzia è depositata in un pool on-chain.

| | Repo Desk | Prestito garantito |
|---|---|---|
| Controparte | Imprese identificate | Mercato aggregato |
| Struttura | Vendita e riacquisto concordato | Prestito con garanzia |
| Prezzo | Quotazione e importo di riacquisto fissi | Tasso variabile per utilizzo |
| Rischio | Haircut, margin call, sostituzione | LTV, oracolo, liquidazione |

## Flusso

1. In **Trader → Repo Desk → New RFQ** indica prestito di contante, garanzia, importo, date, tasso indicativo e haircut.
2. Una RFQ **mirata** è visibile solo alle imprese scelte; una **broadcast** a tutti i trader idonei.
3. Un dealer non vede mai le quotazioni concorrenti. Il richiedente confronta importo, tasso annuo, haircut e validità e ne accetta una.
4. L'importo di riacquisto è fissato con ACT/360. `3,25` significa 3,25% annuo.
5. In apertura e chiusura ogni destinatario conferma la gamba contante o titoli ricevuta con un riferimento.
6. Margin call e sostituzioni restano nello storico condiviso e immutabile.

## Controlli del desk

- **Le quotazioni sono versionate.** Una quotazione sostituita diventa `SUPERSEDED` e non può più essere accettata. L'accettazione contiene il `termsHash` del server; se differisce (409), verificare le condizioni attuali. Le condizioni accettate sono fissate sull'operazione. Importi e interessi sono arrotondati alla sottounità della valuta (ACT/360, ACT/365 per GBP e altre).
- **Ogni gamba ha un pagatore** (dichiara «inviato» con riferimento) **e un ricevente** (conferma o contesta). Una richiesta di margine richiede riferimento di valutazione e importo, non può superare il deficit risultante e concede almeno 24 ore; **solo la conferma del finanziatore la chiude**.
- **L'inadempimento avviene in due fasi:** comunicazione da parte del creditore e, dopo il periodo di tolleranza (24 ore di default), dichiarazione – finché l'obbligazione resta inadempiuta e la controparte non ha dichiarato l'adempimento. Se il mutuatario ha pagato e il finanziatore non restituisce i titoli, può dichiarare l'inadempimento il *mutuatario*.
- **Contestazione:** ciascuna parte può congelare l'operazione; l'operatore registra l'esito con base giuridica e secondo approvatore, senza decidere nel merito.
- **Sostituzione:** richiesta separata; la garanzia cambia solo dopo la conferma di entrambe le gambe e mai su operazioni chiuse, inadempiute o contestate.
- **Accesso:** adesione della società, cliente professionale o controparte idonea, controllo KYC/screening. Il mutuatario deve detenere i titoli nel registro; quelli già costituiti in pegno o messi in vendita sono indisponibili (vincolo interno, nessun Sperrvermerk nel registro). La durata deve terminare prima della scadenza o del richiamo della garanzia; il rimborso è bloccato con un repo aperto. Le operazioni societarie sono annotate; eventuali pagamenti compensativi spettano alle parti.
- **SFTR:** entrambe le parti necessitano di un LEI; ogni operazione riceve un UTI ed espone i campi SFTR disponibili (`/sftr-fields`). Registerwerk non effettua segnalazioni; le parti restano responsabili. Il regolamento è bilaterale e autoconfermato, non atomico.

!!! warning "Il contratto quadro resta indispensabile"
    Il flusso non sostituisce contratto quadro, lista delle garanzie, agente di valutazione, custodia, controversie o parere sul netting. DvP resta preferibile a FoP.
