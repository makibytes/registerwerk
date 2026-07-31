---
title: Distribuire sulla blockchain
---

# Distribuire sulla blockchain

Una volta che l'operatore del registro ha approvato la tua emissione, puoi distribuire il contratto del token sulla blockchain. Questo passaggio è irreversibile — l'indirizzo del contratto entra a far parte in modo permanente della registrazione a registro.

## Presupposti

- Lo stato dell'emissione è **APPROVED**
- Hai il ruolo **Issuer** o **Company Admin**
- Per le emissioni ERC-3643: l'operatore ha già distribuito i contratti factory sulla chain di destinazione

## Avviare la distribuzione

1. Vai su **Issuances** e trova la tua emissione (stato: APPROVED)
2. Fai clic su **Deploy to Blockchain**
3. Compare una finestra di conferma che riepiloga i parametri della distribuzione:

| Parametro | Valore |
|-----------|-------|
| Token standard | ERC-3643 |
| Network | Polygon Mainnet |
| ISIN | DE000EXAMPLE0 |
| Name | Example AG Bond 2025 |
| Symbol | EAGB25 |
| Total supply | 10.000.000 |

4. Fai clic su **Confirm Deployment**

## Che cosa succede durante la distribuzione

Il backend del registro invia per tuo conto una transazione di distribuzione alla blockchain, usando un wallet deployer controllato dall'operatore. Non devi firmare alcuna transazione né detenere ETH/MATIC.

Per un'emissione **ERC-3643** vengono distribuiti in sequenza i seguenti contratti:

1. **Contratto del token** — il token ERC-3643 principale
2. **Identity Registry** — associa gli indirizzi wallet degli investitori al loro ONCHAINID
3. **Identity Registry Storage** — archiviazione persistente del registry
4. **Claim Topics Registry** — elenca i topic di claim KYC richiesti (per esempio topic 1 = KYC, topic 2 = antiriciclaggio)
5. **Trusted Issuers Registry** — elenca quali emittenti di identità sono ritenuti affidabili nel rilasciare claim
6. **Modular Compliance** — contenitore dei moduli di regole di conformità

Di norma richiede 30–120 secondi, a seconda della congestione della rete.

## Seguire l'avanzamento

La pagina di dettaglio dell'emissione mostra un indicatore di avanzamento in tempo reale durante la distribuzione. Ogni distribuzione di contratto è elencata con il proprio hash di transazione, che rimanda al block explorer.

Se un passaggio fallisce (per esempio per un'interruzione di rete o gas insufficiente), la distribuzione viene ritentata automaticamente fino a tre volte. Se falliscono tutti i tentativi, l'emissione torna allo stato **APPROVED** e riceverai una notifica via email.

## Dopo una distribuzione riuscita

Quando tutti i contratti sono distribuiti, l'emissione passa allo stato **ISSUED**. Puoi vedere:

- **Indirizzo del contratto** — l'indirizzo del contratto principale del token
- **Collegamento al block explorer** — verificare il contratto su Etherscan, Polygonscan, ecc.
- **Transazione di distribuzione** — la transazione che ha creato il token

!!! tip
    Comunica ai tuoi investitori l'indirizzo del contratto e il collegamento all'explorer, così potranno verificare le proprie posizioni in modo indipendente.


## Passi successivi

- [Aggiungere investitori e ammettere i wallet](./managing-investors.md)
- Configurare i moduli di conformità (per le configurazioni ERC-3643 standard lo fanno automaticamente gli operatori)
- Annunciare l'emissione ai tuoi investitori
