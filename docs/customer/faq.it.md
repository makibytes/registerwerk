---
title: Domande frequenti
---

# Domande frequenti

## Generale

### Che cos'è il registro eWpG?

Registerwerk è un'implementazione di riferimento per creare e amministrare registrazioni di strumenti finanziari digitali e i relativi token blockchain. Se uno strumento sia giuridicamente riconosciuto ai sensi della legge tedesca sui titoli elettronici (Elektronisches Wertpapiergesetz — eWpG) dipende dallo strumento, dal modello di registro, dall'operatore e dall'installazione, e va esaminato esternamente.

### Il registro è regolamentato?

L'autorizzazione è specifica dell'installazione e dell'operatore. Questo repository non contiene alcuna prova che un determinato operatore possieda un'autorizzazione regolamentare richiesta. Verifica le attività previste, le autorizzazioni dell'operatore e la struttura dello strumento con un consulente qualificato e con l'operatore interessato prima dell'uso.

### Posso registrarmi da solo?

No. L'onboarding è avviato dall'operatore. Contatta l'operatore del registro per richiederlo. Così si garantisce che tutti i partecipanti siano verificati prima di accedere alla piattaforma.

---

## Emittenti

### Quanto dura il processo di approvazione?

I tempi di esame dipendono dall'operatore e dal caso. Questo repository non definisce né garantisce un livello di servizio di 1–3 giorni lavorativi; chiedi all'operatore responsabile la procedura e le tempistiche applicabili.

### Posso modificare i parametri del token dopo l'approvazione?

No. Una volta che un'emissione è nello stato APPROVED, tutti i parametri (nome, ISIN, chain, standard di token, offerta totale) sono bloccati. Puoi ritirare l'invio e tornare a DRAFT per apportare modifiche.

### Che cosa significa «onchain level»?

Determina quanta parte della tua logica di conformità risiede sulla blockchain:
- **None** — solo registrazione a registro, nessun contratto intelligente distribuito
- **Simple** — contratto token standard distribuito, nessuna conformità applicata
- **Control** — contratto ERC-3643 distribuito con moduli di conformità on-chain

### Posso distribuire su più chain?

Attualmente ogni emissione è distribuita su una sola rete. Per emettere lo stesso strumento su più chain, creeresti emissioni separate con lo stesso ISIN. Contatta l'operatore del registro se ti serve il supporto multi-chain.

### Che cosa succede al mio token se il registro va offline?

Una volta distribuito un token, il contratto può continuare a esistere indipendentemente da questa applicazione, in base alla rete scelta e ai controlli del contratto. Registerwerk conserva una registrazione operativa dei titolari e proietta o riconcilia parte dello stato on-chain. Quale registrazione abbia autorità giuridica dipende dallo strumento, dal modello di registro e dalla giurisdizione, e richiede una decisione di perimetro approvata; un saldo indicizzato o on-chain, da solo, non prova la titolarità giuridica né l'efficacia giuridica.

---

## Investitori

### Serve un wallet particolare per detenere token rappresentativi di strumenti finanziari?

Per i token ERC-20 va bene qualsiasi wallet EVM standard (MetaMask, Ledger, ecc.). Per i token ERC-3643 va bene qualsiasi wallet EVM che supporti ERC-20 — la logica di conformità sta nel contratto, non nel wallet. Per i token ERC-3643 confidenziali serve un wallet compatibile FHE sulla rete Fhenix o Inco.

### Perché non riesco a ricevere token al mio indirizzo wallet?

Le cause più comuni sono:
1. Il tuo wallet non è stato ammesso dall'emittente
2. I tuoi claim KYC/antiriciclaggio sono scaduti — controlla **Profile → Identity**
3. Il tuo Paese è soggetto a restrizioni da un modulo di conformità su quel token
4. Il token è attualmente sospeso

### Come ottengo l'approvazione del KYC?

L'operatore del registro gestisce il processo KYC. Sarai guidato nell'invio dei documenti durante l'onboarding. Se il tuo KYC è in attesa o è scaduto, vai su **Profile → Identity → Renew KYC**.

### Le mie posizioni in token sono pubbliche?

Per i token ERC-20, ERC-721, ERC-1155 ed ERC-3643 standard: sì, il tuo saldo è visibile sulla blockchain pubblica a chiunque conosca il tuo indirizzo wallet. Per i token ERC-3643 confidenziali: no, il tuo saldo è cifrato on-chain.

---

## Revisori

### I revisori possono avviare transazioni?

No. Il ruolo di revisore è strettamente in sola lettura. Nessuna azione di un revisore può modificare una registrazione del registro né innescare una transazione on-chain.

### Come verifico che i dati del registro corrispondano alla blockchain?

Ogni registrazione di trasferimento nel registro include l'hash della transazione on-chain. Con quell'hash puoi verificare in modo indipendente qualsiasi trasferimento sul relativo block explorer. Vedi [la guida del revisore](workspaces/auditor.md) per il dettaglio.

### Posso esportare i dati di revisione verso i miei sistemi?

Sì. La pista di controllo e le viste di storico dei token supportano esportazioni CSV e JSON. Per intervalli di date ampi, le esportazioni sono generate in modo asincrono e inviate alla tua email.

---

## Tecnica

### Quali blockchain sono supportate?

Le chain EVM (Ethereum, Polygon, Base), Solana, Canton, StarkNet, Stellar e le reti EVM confidenziali. Per i test sono disponibili anche testnet (Sepolia, Amoy, Base Sepolia, Solana Devnet). Vedi [Blockchain supportate](../blockchains/index.md) per l'elenco completo e a che cosa ciascuna si presta.

### Quali standard di token sono supportati?

ERC-20, ERC-721, ERC-1155, ERC-3525, ERC-3643, ERC-4626, ERC-7540, le loro varianti confidenziali, Solana SPL-2022 e i modelli Daml di Registerwerk per il ciclo di vita obbligazionario su Canton. Il deployment generico `CANTON_TOKEN` è riservato ma non implementato. Vedi [Scegliere uno standard di token](./issuers/token-standards.md) per orientarti.

### Come accedo all'API?

L'API REST è disponibile all'indirizzo `https://api.registerwerk.example.com`. La documentazione è su `/swagger-ui.html`. Per autenticarti serve un token JWT del tuo provider di identità. Vedi [Accedere](./authentication.md).
