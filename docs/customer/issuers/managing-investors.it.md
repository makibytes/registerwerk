---
title: Gestire gli investitori
---

# Gestire gli investitori

Questa guida spiega come aggiungere investitori alla tua emissione, ammettere i loro wallet e gestire il loro ONCHAINID per i token ERC-3643.

## Aggiungere un investitore

Gli investitori devono prima essere registrati come soggetti nel registro eWpG. Se il tuo investitore non è ancora nel sistema, contatta l'operatore del registro per l'onboarding.

Una volta che un soggetto investitore esiste nel registro:

1. Vai alla tua emissione e fai clic su **Investors → Add Investor**
2. Cerca l'investitore per nome, email o identificativo del soggetto
3. Seleziona l'investitore e fai clic su **Add**

L'investitore è ora collegato alla tua emissione nel database del registro. Per i token **Simple** (ERC-20/721/1155) questo basta — puoi trasferire token direttamente al suo wallet.

Per i token **Control** (ERC-3643) devi anche ammettere il wallet dell'investitore (vedi sotto).

## Ammettere i wallet (ERC-3643)

I token ERC-3643 impongono che solo investitori ammessi e verificati KYC possano ricevere token. L'elenco delle ammissioni è conservato on-chain nel contratto **Identity Registry**.

### Passo 1 — L'investitore fornisce l'indirizzo del wallet

L'investitore collega il proprio wallet nel portale cliente sotto **Wallets → Connect Wallet** (vedi [Configurazione del wallet](../investors/wallet-setup.md)) e ti comunica l'indirizzo.

### Passo 2 — Verificare che l'investitore abbia un ONCHAINID

Ogni investitore ERC-3643 deve avere un **ONCHAINID** — uno smart contract che funge da sua identità on-chain. Il registro ne crea uno automaticamente quando il soggetto investitore viene attivato.

Puoi verificarlo sotto **Investor → [nome] → ONCHAINID**. Se esiste, viene mostrato l'indirizzo del contratto ONCHAINID.

### Passo 3 — Controllare i claim KYC/antiriciclaggio

I token ERC-3643 richiedono che gli investitori possiedano **claim** validi sul proprio ONCHAINID — attestazioni crittografiche rilasciate da un fornitore KYC affidabile. La tua emissione richiede come minimo:

- **Topic di claim 1**: KYC (Know Your Customer)
- **Topic di claim 2**: antiriciclaggio

L'operatore del registro rilascia questi claim dopo che l'investitore ha completato il processo di verifica KYC. Lo stato dei claim è visibile nella pagina di dettaglio dell'investitore.

!!! warning
    Non puoi ammettere un investitore il cui ONCHAINID non abbia claim KYC/antiriciclaggio validi. Il tentativo verrà respinto dall'identity registry on-chain.


### Passo 4 — Iscrivere il wallet nell'Identity Registry

Una volta che l'investitore ha un ONCHAINID valido e i claim:

1. Vai alla tua emissione → **Investors → [nome dell'investitore]**
2. Fai clic su **Add Wallet**
3. Inserisci l'indirizzo wallet fornito dall'investitore
4. Fai clic su **Register on Chain**

Il backend del registro invia una transazione al contratto Identity Registry, collegando l'indirizzo wallet all'ONCHAINID dell'investitore. Di norma richiede 5–15 secondi.

Una volta iscritto, il wallet è ammesso. L'investitore può ora ricevere token a quell'indirizzo.

## Rimuovere un investitore

Per togliere il wallet di un investitore dall'elenco delle ammissioni:

1. Vai su **Investors → [nome dell'investitore] → Wallets**
2. Fai clic su **Remove from Whitelist** accanto all'indirizzo
3. Conferma l'operazione

Il registro invia una transazione che rimuove il wallet dall'Identity Registry. L'investitore non potrà più ricevere token e qualsiasi trasferimento futuro verso quel wallet sarà automaticamente respinto dallo smart contract.

!!! note
    Togliere un investitore dall'elenco delle ammissioni non confisca il suo saldo di token esistente. Se devi recuperare token (per esempio a seguito di un provvedimento giudiziario), contatta l'operatore del registro — serve un'operazione di trasferimento coattivo eseguita dall'agente del token.


## Moduli di conformità

Per i token ERC-3643 l'operatore configura moduli di conformità che applicano automaticamente regole aggiuntive:

| Modulo | Descrizione |
|--------|-------------|
| **MaxBalance** | Limita il saldo massimo di token che un singolo investitore può detenere |
| **MaxInvestors** | Pone un tetto al numero totale di investitori distinti |
| **CountryRestrict** | Blocca gli investitori di determinate giurisdizioni |

Questi moduli girano automaticamente a ogni tentativo di trasferimento. Se un trasferimento violasse la regola di un modulo, viene respinto on-chain senza che tu debba fare nulla.

Contatta l'operatore del registro se devi modificare i parametri dei moduli per la tua emissione.
