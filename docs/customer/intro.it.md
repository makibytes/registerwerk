---
title: Che cos'è Registerwerk
description: Una spiegazione semplice di che cosa fa la piattaforma, che cosa non fa e che cosa puoi aspettarti da essa.
---

# Che cos'è Registerwerk

**È un registro.** Un'annotazione di chi possiede quali strumenti finanziari, tenuta da un operatore, con quegli stessi strumenti rappresentati anche come token su una blockchain.

È tutta qui l'idea. Il resto è conseguenza.

---

## Il problema che risolve

Uno strumento finanziario era un documento. Possederlo significava tenerlo fisicamente, o farlo tenere a un depositario. Venderlo significava consegnarlo.

Funzionava, ed era costoso: caveau, corrieri, riconciliazioni e giorni tra l'accordo su un'operazione e il suo completamento.

Gli strumenti finanziari digitali eliminano il documento. La proprietà diventa un'iscrizione a registro. In Germania l'**eWpG**, in vigore da giugno 2021, lo rende giuridicamente possibile: uno strumento può esistere come iscrizione in un registro anziché come certificato.

Registerwerk realizza un registro di questo tipo e aggiunge un secondo livello — le stesse posizioni rappresentate come token su una blockchain, così che i trasferimenti possano essere eseguiti e verificati in modo indipendente senza che una parte debba fidarsi delle scritture dell'altra.

---

## Le due registrazioni

È l'unica idea strutturale che vale la pena capire, perché da lì discende quasi ogni sorpresa.

<div class="grid" markdown>

!!! abstract "Il registro"
    Un database, tenuto dall'operatore. Indica il titolare, l'importo, le restrizioni.

    **La registrazione con rilievo giuridico.**

!!! abstract "Il token"
    Un saldo in uno smart contract su una blockchain. Pubblico e verificabile in modo indipendente.

    **La registrazione che esegue.**

</div>

Un software osserva la chain e tiene il registro allineato. Quasi sempre concordano. Quando non lo fanno, il registro fa fede e la differenza la risolve una persona.

[:octicons-arrow-right-24: Detenzione e custodia](lifecycle/holding.md) entra nel merito come si deve.

---

## Che cosa puoi fare

| | |
|---|---|
| **Emettere** | Creare uno strumento, farlo approvare, distribuirlo, ammettere investitori e amministrarlo per tutta la sua vita. |
| **Detenere** | Possedere strumenti finanziari, vedere le tue posizioni, ricevere estratti e pagamenti. |
| **Negoziare** | Vendere prima della scadenza, o comprare da altri titolari. |
| **Prendere a prestito** | Costituire in garanzia le posizioni e ottenere un prestito su di esse, dove è abilitato. |
| **Pubblicare** | Costruire applicazioni sul framework di permessi dell'ecosistema ed elencarle. |
| **Revisionare** | Leggere l'intero registro senza poter cambiare nulla. |

[:octicons-arrow-right-24: Trova la tua area di lavoro](workspaces/index.md)

---

## Dove possono vivere gli strumenti finanziari

Il registro supporta diverse blockchain, scelte per ciascuna emissione. Ognuna ha mainnet e testnet.

| Famiglia | |
|---|---|
| **EVM** | Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism |
| **EVM confidenziale** | Fhenix, Inco — importi cifrati on-chain |
| **Solana** | SPL e SPL-2022 |
| **Canton** | Un ledger privato in cui le controparti vedono solo le proprie transazioni |
| **Altre** | StarkNet, Stellar |

Quale scegliere conta più di quanto sembri: determina chi può vedere le tue transazioni, quanto costa un trasferimento, quanto rapidamente si regola e quali standard di token sono disponibili. [Blockchain supportate](../blockchains/index.md) le confronta.

---

## Che cosa non fa

Essere chiari su questo è più utile di un elenco di funzioni.

!!! warning "Registerwerk è un'implementazione di riferimento"
    Software funzionante che modella come si può costruire un registro di strumenti finanziari digitali — perché il disegno possa essere esaminato, criticato e riutilizzato.

    **Usarlo non rende nessuno conforme all'eWpG né ad altra legge.** Non conferisce autorizzazioni regolamentari e non dà a un token efficacia giuridica di strumento finanziario. Ciò dipende dall'autorizzazione dell'operatore, dallo strumento, dall'offerta, dalle parti e dall'installazione.

    Potresti imbatterti in materiale più vecchio che sostiene che i token emessi qui sono «giuridicamente equivalenti alle obbligazioni al portatore e alle azioni tradizionali». **Quell'affermazione è errata** ed è stata rimossa. Se uno strumento produca effetti giuridici lo stabiliscono la legge e il modo in cui è stato realmente emesso — mai il software che l'ha registrato.

Più precisamente, non è:

- **Un servizio di valutazione.** Il registro annota valori nominali, non prezzi di mercato.
- **Un custode delle tue chiavi.** La chiave privata del tuo wallet la tieni tu. Nessuno può recuperarla.
- **Una sede di negoziazione.** Si collega a sedi di negoziazione; non gestisce un mercato.
- **Un sistema di pagamento.** Supporta diversi canali di pagamento; il denaro si muove lì, non qui.
- **Un garante.** Se un emittente è inadempiente, la piattaforma lo annota. Non risarcisce i titolari.

---

## Il contesto regolamentare, in breve

L'**eWpG** (*Gesetz über elektronische Wertpapiere*) consente strumenti finanziari elettronici senza documento fisico e ne impone l'iscrizione in un registro titoli. Le disposizioni che incontrerai più spesso:

| | |
|---|---|
| **§16** | Che cosa contiene il registro e che cosa significa un'iscrizione. |
| **§17(2)** | Contenuti aggiuntivi richiesti per le iscrizioni individuali. |
| **§19(2)** | Gli estratti di registro dovuti ai titolari consumatori. |
| **§24** | La rettifica del registro. |

Registerwerk modella anche il Lussemburgo (CSSF), la Francia (AMF) e il Liechtenstein (TVTG), e tocca l'antiriciclaggio, la Travel Rule, le segnalazioni MiFIR, DAC8/CARF, DORA, MiCAR e il GDPR.

[:octicons-arrow-right-24: Quadri giuridici](../legal/index.md)

!!! note "Ogni emissione in produzione è prima approvata dall'operatore"
    L'operatore verifica le emissioni rispetto ai propri criteri di ammissione prima che si distribuisca alcunché. È un controllo operativo, non un parere legale sul tuo strumento.

---

## Dove andare adesso

<div class="grid cards" markdown>

-   **Capire il mestiere**

    ---

    [La vita di uno strumento finanziario](lifecycle/index.md) — un'obbligazione, dall'idea al rimborso. Quaranta minuti, nessuna conoscenza pregressa richiesta.

-   **Prepararsi**

    ---

    [Ottenere l'account](onboarding.md) → [Farsi verificare](kyc.md) → [Collegare un wallet](investors/wallet-setup.md)

-   **Fare il proprio lavoro**

    ---

    [Investitore](workspaces/investor.md) · [Trader](workspaces/trader.md) · [Emittente](workspaces/issuer.md) · [Revisore](workspaces/auditor.md)

-   **Cercare qualcosa**

    ---

    [Glossario](glossary.md) · [Domande e risposte](faq.md)

</div>
