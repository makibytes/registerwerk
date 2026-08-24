---
title: Dashboard
---

# Dashboard

La dashboard è la prima schermata che vedi dopo l'accesso. Offre una panoramica in tempo reale della tua attività nel registro, adattata al tuo ruolo.

## Schede di sintesi

In cima alla dashboard trovi le schede di sintesi. Quali compaiono dipende dal tuo ruolo:

### Dashboard dell'emittente

| Scheda | Descrizione |
|------|-------------|
| **Active Issuances** | Numero di token attualmente nello stato ISSUED |
| **Pending Approval** | Emissioni in attesa dell'esame dell'operatore |
| **Total Investors** | Wallet di investitori distinti su tutti i tuoi token |
| **Networks** | Reti blockchain distinte su cui hai distribuito token |

### Dashboard dell'investitore

| Scheda | Descrizione |
|------|-------------|
| **Token Holdings** | Numero di token rappresentativi di strumenti finanziari distinti che detieni |
| **Connected Wallets** | Wallet registrati sul tuo account |
| **Recent Transfers** | Trasferimenti degli ultimi 30 giorni |

### Dashboard del revisore

| Scheda | Descrizione |
|------|-------------|
| **Total Issuances** | Tutte le emissioni nel registro |
| **Transfers (30d)** | Totale degli eventi di trasferimento on-chain degli ultimi 30 giorni |
| **Active Issuers** | Numero di soggetti emittenti con almeno un token attivo |
| **Pending KYC Reviews** | Pratiche KYC in attesa dell'esame dell'operatore (sola lettura) |

## Flusso attività recenti

Sotto le schede di sintesi, il pannello **Recent Activity** mostra gli ultimi eventi rilevanti per il tuo account. Ogni voce include:

- **Marca temporale** — quando l'evento si è verificato (nel tuo fuso orario locale)
- **Tipo di evento** — per esempio *Issuance Created*, *Transfer*, *KYC Approved*
- **Oggetto** — il token o il soggetto coinvolto
- **Rete** — la rete blockchain (con l'icona della chain)

Fai clic su una riga qualsiasi per andare direttamente alla pagina di dettaglio corrispondente.

## Azioni rapide

Il pannello **Quick Actions** offre una navigazione in un clic verso le attività più comuni del tuo ruolo:

- **Emittente**: New Issuance, Manage Investors, View Pending Approvals
- **Investitore**: View Holdings, Connect Wallet, Download Statement
- **Revisore**: Open Audit Log, Search Transfers, Export Report

## Stato delle reti

In fondo alla dashboard una griglia **Network Status** in tempo reale indica se ciascuna rete blockchain configurata è al momento raggiungibile e sincronizzata. Il verde significa che l'indicizzatore è aggiornato; il giallo che è indietro di più di 10 blocchi rispetto alla testa della chain; il rosso che non è disponibile.

!!! tip
    Se una rete è rossa, i dati on-chain di quella rete potrebbero essere vecchi. Attendi qualche minuto e aggiorna. Se il problema persiste, contatta l'operatore del registro.


## Aggiornamento dei dati

I dati della dashboard si aggiornano automaticamente ogni 30 secondi. Puoi forzare un aggiornamento immediato con il pulsante **Refresh** in alto a destra di ciascun pannello.
