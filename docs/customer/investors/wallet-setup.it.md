---
title: Configurazione del wallet
---

# Configurazione del wallet

Per detenere e consultare token rappresentativi di strumenti finanziari devi collegare un wallet blockchain al tuo account del registro. Questa pagina spiega come configurare un wallet compatibile e farlo ammettere per i token ERC-3643.

## Tipi di wallet supportati

Il registro eWpG supporta qualsiasi wallet ad autocustodia in grado di produrre firme EIP-712. Wallet consigliati:

| Wallet | Tipo | Reti |
|--------|------|----------|
| MetaMask | Estensione del browser / mobile | Tutte le reti EVM |
| Ledger Live | Hardware | Tutte le reti EVM |
| Trezor Suite | Hardware | Tutte le reti EVM |
| Phantom | Estensione del browser / mobile | Solana (ed EVM) |
| Rabby | Estensione del browser | Tutte le reti EVM |

!!! tip
    Per l'uso istituzionale i wallet hardware (Ledger, Trezor) sono fortemente consigliati. Tengono la chiave privata offline e richiedono una conferma fisica per ogni transazione.


## Collegare un wallet

1. Vai su **Profile → Wallets**
2. Fai clic su **Connect Wallet**
3. Seleziona dall'elenco il tipo di wallet
4. L'estensione del wallet si apre e chiede di collegarsi. Approva la connessione.
5. Il portale ti chiede di **firmare un messaggio** — una firma senza costi di gas che prova il possesso dell'indirizzo. Firmala nel wallet.
6. L'indirizzo compare ora nel tuo elenco di wallet.

Puoi collegare più wallet. Le posizioni di tutti i wallet collegati sono aggregate nella vista **Investments**.

## Farsi ammettere per i token ERC-3643

Collegare un wallet al portale non lo ammette automaticamente ai trasferimenti di token ERC-3643. L'ammissione è un passaggio distinto, compiuto dall'**emittente** del token dopo aver verificato il tuo stato KYC.

Il processo:

1. Collega il wallet nel portale (come descritto sopra)
2. Comunica all'emittente il tuo indirizzo wallet (visibile nella pagina **Wallets**)
3. Assicurati che la tua verifica KYC/antiriciclaggio sia completa (controlla **Profile → Identity**)
4. L'emittente iscrive il tuo wallet nel proprio contratto identity registry
5. Riceverai una notifica quando l'ammissione sarà completata

Dopo l'ammissione puoi ricevere token a quell'indirizzo. L'ammissione è memorizzata on-chain e persiste indipendentemente dal portale.

## Rimuovere un wallet

Per rimuovere un wallet dal tuo account:

1. Vai su **Profile → Wallets**
2. Fai clic su **Remove** accanto all'indirizzo

Rimuovere un wallet dal tuo account del portale non lo rimuove da nessuna lista di ammissione on-chain di un emittente. Contatta ciascun emittente singolarmente se vuoi che il tuo indirizzo sia tolto dal suo identity registry.

## Aggiungere un wallet Solana

Per i token basati su Solana:

1. Vai su **Profile → Wallets**
2. Fai clic su **Connect Wallet → Solana**
3. Collegati con Phantom o un altro wallet Solana supportato
4. Firma il messaggio di verifica

Gli indirizzi wallet Solana usano un formato diverso (base58) da quelli EVM. Per chiarezza il portale mostra entrambi i formati affiancati.

## Buone pratiche di sicurezza

- **Non condividere mai la tua chiave privata** con nessuno — nemmeno con l'operatore del registro
- Usa un wallet dedicato agli strumenti finanziari; evita di mescolarlo con attività DeFi personali
- Attiva la protezione con password o biometria del wallet
- Conserva una copia della seed phrase in un luogo sicuro e offline
- Per posizioni rilevanti usa un wallet hardware

!!! warning
    L'operatore del registro non ti chiederà mai la chiave privata né la seed phrase. Se qualcuno che dice di essere del registro ti chiede queste informazioni, è una truffa — non fornirle e segnala subito l'accaduto.

