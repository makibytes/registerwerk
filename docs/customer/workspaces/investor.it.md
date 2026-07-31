---
title: Investitore
description: Per chi detiene strumenti finanziari — vedere che cosa possiedi, quanto vale e che cosa ti spetta.
---

# Investitore

**Possiedi strumenti finanziari e vuoi tenerne traccia.** Non negozi attivamente, non emetti nulla e non cerchi leva. Hai comprato qualcosa e vuoi sapere come sta.

È l'area di lavoro più piccola, e lo è di proposito.

---

## Prima che qualcosa funzioni

Tre condizioni devono essere vere perché uno strumento possa raggiungerti. Se qualcosa non funziona, quasi sempre è una di queste.

<div class="grid cards" markdown>

-   **1. La tua organizzazione è attivata**

    ---

    La tua società esiste nel registro come soggetto giuridico con stato attivo.

    [:octicons-arrow-right-24: Ottenere l'account](../onboarding.md)

-   **2. Il tuo KYC è approvato**

    ---

    L'operatore ha verificato la tua organizzazione. Non solo presentato — **approvato**, e non scaduto.

    [:octicons-arrow-right-24: Verifica](../kyc.md)

-   **3. Hai registrato un wallet**

    ---

    Un indirizzo a cui gli strumenti possono essere inviati. Senza, non c'è dove consegnare.

    [:octicons-arrow-right-24: Collegare un wallet](../investors/wallet-setup.md)

</div>

!!! warning "L'ordine conta"
    Per uno strumento regolamentato come un titolo [ERC-3643](../../token-standards/erc3643.md), il tuo wallet deve essere ammesso nell'identity registry di quello strumento *prima* che qualcosa possa esserti trasferito. Un trasferimento verso un wallet non registrato non resta in sospeso: fallisce on-chain.

    Se un emittente dice di averti inviato dei titoli e non è arrivato nulla, questa è la prima cosa da controllare.

---

## La tua quotidianità

### Dashboard

Che cosa è cambiato dall'ultima volta: le tue posizioni, l'attività recente, tutto ciò che richiede attenzione — un KYC in scadenza, un'operazione in sospeso, una posizione bloccata.

### Positions

Tutto ciò che detieni, su tutti gli asset e tutte le chain.

| Colonna | Come leggerla |
|---|---|
| **Asset** | Quale strumento. |
| **Nominal amount** | Il valore nominale che detieni. |
| **Wallet** | Quale dei tuoi indirizzi lo detiene. |
| **Entry type** | Iscrizione collettiva o individuale — [che cosa significa](../lifecycle/primary-issuance.md#che-cosa-contiene-uniscrizione-a-registro). |
| **Status** | Attiva o bloccata. |

!!! note "Il nominale non è il valore di mercato"
    100.000 € nominali significano che a scadenza ti spettano 100.000 €. Non significano che la posizione valga 100.000 € oggi — un'obbligazione può quotare sopra o sotto la pari per tutta la sua vita.

    Registerwerk è un registro. Annota che cosa detieni, non quanto qualcuno ti darebbe.

### Investments

Una posizione, in profondità. Le condizioni dello strumento, il suo indirizzo on-chain e lo storico delle transazioni, le operazioni societarie che ti riguardano e i tuoi estratti di registro.

È qui che vai quando devi *dimostrare* qualcosa anziché limitarti a vederlo.

---

## Che cosa ti succederà

### Riceverai un estratto di registro

Se detieni tramite **iscrizione individuale** e sei un consumatore, il §19(2) eWpG ti dà diritto a un *Registerauszug* — dopo l'iscrizione iniziale, dopo ogni variazione che ti riguarda e almeno una volta l'anno.

Sono documenti permanenti e riproducibili, non email di notifica. [Approfondisci](../lifecycle/holding.md#il-tuo-estratto-di-registro).

I titolari istituzionali in un'iscrizione collettiva non li ricevono — ecco perché potresti non vederne nessuno.

### Riceverai le cedole

Per un'obbligazione gli interessi arrivano secondo un calendario. Che *tu* riceva un dato pagamento dipende dalla **data di registrazione**, non dalla data di pagamento — se detieni alla data di registrazione, il pagamento è tuo anche se vendi il giorno dopo.

[:octicons-arrow-right-24: Come funzionano le operazioni societarie](../lifecycle/redemption.md)

### Il tuo KYC scadrà

La verifica ha una scadenza. Quando si avvicina, la piattaforma ti avvisa; quando è superata, i trasferimenti si fermano.

**Questo non ti toglie gli strumenti.** Resti titolare, resti legittimato a ricevere i pagamenti. Semplicemente non puoi muovere nulla finché la tua organizzazione non è verificata di nuovo.

### Una posizione può essere bloccata

Un provvedimento giudiziario, una corrispondenza con una lista sanzioni, una costituzione in garanzia, una questione di conformità irrisolta. Vedrai il blocco e la sua motivazione accanto alla posizione.

Resta tua. Non puoi muoverla. [Approfondisci](../lifecycle/holding.md#quando-una-posizione-e-bloccata).

---

## Che cosa non puoi fare qui

Detto chiaramente, così non lo cerchi:

- **Non puoi vendere dall'area Investor.** Vendere richiede il ruolo `TRADER` e l'[area Trader](trader.md).
- **Non puoi valorizzare il tuo portafoglio.** Registerwerk non detiene prezzi di mercato per gli strumenti che registra.
- **Non puoi trasferire a un indirizzo qualsiasi.** Per gli strumenti regolamentati il destinatario deve essere un titolare ammesso.
- **Non puoi recuperare da solo un wallet perduto.** Vedi sotto.

!!! danger "Se perdi la chiave del wallet"
    Nessuno può ripristinarla. Né l'operatore né l'emittente.

    Il tuo *credito* sopravvive — il registro continua a riportarti come titolare, e resti legittimato a cedole e rimborso. Ciò che hai perso è la possibilità di muovere i token.

    Il recupero passa da un **trasferimento coattivo** eseguito dall'operatore ai sensi del §24 eWpG: una correzione formale e documentata che sposta la tua posizione su un wallet che controlli. Contatta l'operatore. Richiede prove, richiede il [principio dei quattro occhi](../../compliance/step-up-mfa.md), e non è veloce.

---

## Dove andare adesso

- [La vita di uno strumento finanziario](../lifecycle/index.md) — che cosa succede davvero intorno a te
- [Detenzione e custodia](../lifecycle/holding.md) — dove risiedono davvero i tuoi strumenti
- [Domande e risposte](../faq.md)
