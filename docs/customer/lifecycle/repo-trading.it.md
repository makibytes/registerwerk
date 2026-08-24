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

!!! warning "Il contratto quadro resta indispensabile"
    Il flusso non sostituisce contratto quadro, lista delle garanzie, agente di valutazione, custodia, controversie o parere sul netting. DvP resta preferibile a FoP.

