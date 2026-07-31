---
title: Per gli operatori
description: Gestire un registro Registerwerk — il mestiere, l'architettura e i processi rivolti ai clienti che costituiscono gran parte del lavoro.
---

# Per gli operatori

**Sei tu a gestire il registro.** I clienti vi emettono strumenti finanziari, li detengono, li negoziano. Il tuo lavoro è decidere chi entra, controllare che cosa fanno, tenere viva la piattaforma e aiutare quando qualcosa va storto.

Non devi conoscere i mercati dei titoli con la profondità di un emittente. Devi però capirne abbastanza da sapere che cosa stai approvando e perché conta.

---

## Da dove iniziare

<div class="grid cards" markdown>

-   :material-flag:{ .lg .middle } **[Che cosa fa un operatore](getting-started.md)**

    ---

    Il mestiere per intero, il portale e le decisioni che spettano solo a te.

-   :material-sitemap:{ .lg .middle } **[Come è costruito Registerwerk](architecture.md)**

    ---

    L'architettura, inquadrata secondo ciò che si rompe e che cosa significa quando accade.

-   :material-account-group:{ .lg .middle } **[Servire i clienti](customers/index.md)**

    ---

    Onboarding, KYC, approvazioni, assistenza, modalità supporto, cessazione. Gran parte del lavoro reale.

-   :material-server:{ .lg .middle } **[Installazione](installation/prerequisites.md)**

    ---

    Metterlo in funzione, dai presupposti al gateway.

</div>

---

## Le quattro cose che solo tu puoi fare

I clienti possono fare moltissimo. Queste quattro sono tue, e lo sono perché ciascuna può causare danni difficili o impossibili da annullare.

| | | |
|---|---|---|
| **Ammettere un'organizzazione** | Nessuno usa il registro finché non approvi il soggetto e il suo KYC. | [Onboarding](customers/onboarding-flow.md) · [KYC](customers/kyc-process.md) |
| **Approvare un'emissione** | Nessuno strumento esiste finché non dici di sì. | [Approvare le emissioni](customers/approving-issuances.md) |
| **Rettificare il registro** | Trasferimenti coattivi, distruzioni coattive, blocchi del titolare — i poteri dei §24 e §26 eWpG. | [Sperrvermerk](../compliance/sperrvermerk.md) |
| **Agire come un cliente** | La modalità supporto. Potente e interamente attribuita. | [Modalità supporto](customers/impersonation.md) |

È sulla seconda e sulla quarta che i nuovi operatori chiedono più spesso indicazioni; entrambe hanno una pagina dedicata.

---

## Le abitudini che conviene prendere presto

!!! tip "Leggi la pista di controllo quando non c'è nulla che non va"
    Se la apri solo durante un incidente, non saprai com'è fatta la normalità e non noterai la cosa che non dovrebbe esserci.

!!! tip "Considera i quattro occhi una funzione, non un ostacolo"
    Diverse operazioni richiedono una seconda persona: stornare un'operazione regolata, approvare il regolamento di un'operazione societaria, reimpostare l'MFA di un cliente, emettere un permesso di accesso temporaneo. Sono esattamente le operazioni in cui una singola azione errata o malevola fa più danni.

    Le installazioni in cui una sola persona detiene tutte le credenziali hanno controlli a quattro occhi solo di nome. È l'organico a renderli reali.

!!! tip "Di' «non lo so» ad alta voce"
    Ti verrà chiesto se uno strumento è conforme, se un token ha efficacia giuridica, se un cliente può lecitamente fare qualcosa. La piattaforma modella regole; non le decide.

    Rimettere una domanda al legale è la risposta giusta molto più spesso di quanto gli operatori si aspettino.

---

## Che cosa non sei

Vale la pena dirlo, perché i clienti presumeranno il contrario.

- **Non sei il loro avvocato.** Approvi secondo i tuoi criteri, non i loro.
- **Non sei il loro depositario.** Non puoi recuperare una chiave di wallet perduta. Puoi eseguire un trasferimento coattivo ai sensi del §24, che è una rettifica formale, non un reset di password.
- **Non sei un servizio di valutazione.** Il registro annota valori nominali, non prezzi di mercato.
- **Non sei un garante.** Se un emittente è inadempiente, tu ne prendi atto; non risarcisci i titolari.

---

## Quando qualcosa non va

| | |
|---|---|
| La piattaforma si comporta male | [Risoluzione dei problemi](troubleshooting.md) |
| Qualcosa è giù | [Monitoraggio](maintenance/monitoring.md) · [Manuale di ripristino](dr/runbook.md) |
| Cliente bloccato fuori | [Assistenza due fattori](customers/two-factor-support.md) |
| Cliente confuso | [Modalità supporto](customers/impersonation.md) — vedere esattamente ciò che vede lui |
| Difetti noti | [Registro dei rilievi](../assurance-review-ledger.md) |
