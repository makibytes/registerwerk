---
title: La vita di uno strumento finanziario
description: Un'obbligazione, seguita dall'idea iniziale fino al rimborso, con ogni funzione di Registerwerk spiegata dove serve davvero.
---

# La vita di uno strumento finanziario

La maggior parte delle documentazioni spiega funzioni. Questa sezione racconta una *storia* e lascia che le funzioni compaiano dove hanno senso.

La storia è quella di un'obbligazione. La seguiamo dal momento in cui qualcuno vuole prendere denaro a prestito, attraverso le formalità, su una blockchain, nelle mani degli investitori, su una sede di negoziazione, dentro un mercato di finanziamento come garanzia, e infine fuori dall'esistenza quando il debito è rimborsato.

**Chi legge tutta questa sezione capisce il mestiere di cui si occupa Registerwerk.** Circa quaranta minuti.

---

## Nordwind Energie

!!! example "L'esempio che ci accompagna"

    **Nordwind Energie GmbH** costruisce parchi eolici nello Schleswig-Holstein. Le servono **50 milioni di euro** per finanziare un nuovo sito, e non vuole rivolgersi a una banca.

    Decide quindi di prendere il denaro direttamente dagli investitori, emettendo un'**obbligazione**: la promessa di restituire la somma a una data fissa, con interessi nel frattempo.

    Le condizioni previste:

    | | |
    |---|---|
    | Importo | 50.000.000 € |
    | Taglio | 1.000 € per titolo, quindi 50.000 titoli |
    | Interesse | 4,5 % annuo, pagato semestralmente |
    | Scadenza | 5 anni |
    | Rimborso | valore nominale integrale alla scadenza |

    Questo è l'intero prodotto finanziario. Tutto il resto è la macchina che rende quella promessa efficace, negoziabile e opponibile — e che dimostra a un'autorità di vigilanza che tutto è stato fatto correttamente.

??? note "Per chi non viene dalla finanza: che cos'è davvero un'obbligazione"

    Un'obbligazione è un prestito tagliato in parti uguali, perché molti finanziatori possano prenderne una ciascuno.

    Nordwind vuole 50 milioni. Invece di trovare un unico finanziatore disposto a darli tutti, divide il prestito in 50.000 parti da 1.000 €. Un investitore ne compra quante ne vuole. Ogni parte dà diritto alla propria quota di interessi e, alla fine, a 1.000 €.

    Tre parole che incontrerai continuamente:

    - **Valore nominale** (o *alla pari*): l'importo scritto sul titolo — qui 1.000 €. È ciò che viene rimborsato alla fine, indipendentemente da quanto qualcuno abbia pagato nel frattempo.
    - **Cedola**: il tasso di interesse, qui 4,5 % annuo. Il nome viene da quando le obbligazioni erano di carta e si staccava fisicamente una cedola dal certificato per riscuotere ogni pagamento.
    - **Scadenza**: la data in cui il prestito finisce e il valore nominale viene rimborsato.

    Il punto decisivo e controintuitivo: **il prezzo di un'obbligazione e il suo valore nominale sono due numeri diversi, e il prezzo si muove.** Se i tassi salgono dopo l'emissione, un'obbligazione che paga il 4,5 % diventa meno attraente e la si venderà solo a sconto — magari 960 € per un titolo da 1.000 €. Il valore nominale non è cambiato. È cambiato ciò che qualcuno è disposto a pagare per il diritto a incassarlo.

---

## Le sei fasi

<div class="grid cards" markdown>

-   **1. [Progettazione e approvazione](design.md)**

    ---

    Nordwind descrive l'obbligazione nel portale, sceglie come esisterà su una blockchain e la sottopone. L'operatore verifica e approva. Non c'è ancora nulla on-chain.

-   **2. [Emissione primaria](primary-issuance.md)**

    ---

    Il contratto viene distribuito, gli investitori vengono ammessi e i 50.000 titoli nascono nelle loro mani. Il denaro va in una direzione, i titoli nell'altra.

-   **3. [Detenzione e custodia](holding.md)**

    ---

    Gli investitori possiedono qualcosa. Dove si trova davvero, chi risulta titolare, e che cosa succede quando registro e blockchain divergono?

-   **4. [Mercato secondario](secondary-market.md)**

    ---

    Un investitore vuole uscire prima della scadenza. Un altro vuole entrare. Come i due si incontrano e come lo scambio viene messo in sicurezza.

-   **5. [Pronti contro termine e finanziamento](repo-lending.md)**

    ---

    Un investitore ha bisogno di liquidità ma vuole tenersi l'obbligazione. La costituisce in garanzia e ci prende a prestito sopra — il meccanismo più antico dei mercati finanziari, ricostruito on-chain.

-   **6. [Operazioni societarie e rimborso](redemption.md)**

    ---

    Interessi due volte l'anno per cinque anni. Poi il prestito finisce, il denaro torna indietro e il titolo viene distrutto.

</div>

---

## I due errori da evitare

Due fraintendimenti causano la maggior parte della confusione tra i nuovi arrivati. Nominarli ora risparmia molte riletture.

**«Il token *è* lo strumento finanziario.»** No. Il token è il modo in cui lo strumento viene trasferito e attestato su una blockchain. Lo strumento è il credito giuridico verso Nordwind. Il registro è la registrazione di chi lo detiene. Se domani si spegnessero tutte le blockchain del mondo, gli investitori sarebbero comunque creditori di 50 milioni di euro — avrebbero soltanto molta più difficoltà a dimostrare a chi spetta che cosa. Il token è il meccanismo, non la cosa.

**«Su una blockchain chiunque può mandare qualsiasi cosa a chiunque.»** Vero per una criptovaluta. Categoricamente falso qui. Uno strumento regolamentato può essere detenuto solo da chi è autorizzato a detenerlo, e questa restrizione deve sopravvivere al contatto con una blockchain pubblica dove chiunque può invocare qualunque funzione. Risolvere questo problema è gran parte di ciò che rende i token su strumenti finanziari più difficili dei token ordinari, ed è l'argomento di [Progettazione e approvazione](design.md).

---

[Comincia dalla fase 1: Progettazione e approvazione :octicons-arrow-right-24:](design.md){ .md-button .md-button--primary }
