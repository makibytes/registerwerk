---
title: Cosa fa un operatore
description: Il ruolo completo dell'operatore: le tue decisioni, il portale e un avvio locale di quindici minuti.
---

# Cosa fa un operatore

Esegui il registro. I clienti dipendono dal fatto che sia corretto, disponibile e gestito da qualcuno che capisca cosa stanno approvando.

Questa pagina è il lavoro. [Come è costruito Registerwerk](architecture.md) è il sistema; [Servire i clienti](customers/index.md) è il dettaglio di ogni processo.

---

## Il ruolo, onestamente

La maggior parte del lavoro è **giudizio sulle persone e sugli strumenti**, non sull'infrastruttura. Trascorrerai molto più tempo a decidere se un'entità è chi afferma di essere e se un'emissione debba essere ammessa, piuttosto che riavviare i contenitori.

I poteri che sono solo tuoi condividono tutti una proprietà: **ognuno può causare danni difficili o impossibili da invertire.**

| | Perché è tuo |
|---|---|
| **Ammettere un'organizzazione** | Tutto a valle presuppone che questo controllo sia avvenuto. |
| **Approvazione di un'emissione** | Crea qualcosa che diventa un obbligo legale per gli investitori. |
| **Correzione del registro** | I trasferimenti coattivi e le distruzioni coattive ai sensi dei §§24/26 eWpG incidono su beni altrui. |
| **Agire come cliente** | [Modalità supporto](customers/impersonation.md) (impersonation) ti inserisce nel loro portale. |

---

## La tua giornata

### Routine

- **La coda di approvazione.** Entità in attesa di revisione KYC, emissioni in attesa di approvazione.
- **L'audit log.** Leggilo quando non c'è niente di sbagliato, in modo da sapere come appare normale.
- **Salute.** Ritardo dell'indicizzatore, integrità della catena RPC, disponibilità dello screening, [headroom della partizione di controllo](maintenance/monitoring.md).
- **Supporto.** Di solito una delle tre cose: vedere di seguito.

### Su un programma

- **Rivedi l'appartenenza al ruolo `REGISTRY_ADMIN`.** Ogni titolare del ruolo può approvare le emissioni, correggere il registro e impersonare qualsiasi cliente.
- **Controlla le scadenze di KYC in arrivo.** Avvisare un cliente con un mese di anticipo previene un'interruzione che percepirà come colpa tua.
- **Verifica la catena di controllo (audit chain)** e conserva le prove. Un controllo di integrità che nessuno esercita è indistinguibile da uno che non funziona.
- **Testa i ripristini.** Un backup che nessuno ha mai ripristinato è un'ipotesi.

### Il test delle tre domande

Prima di indagare su qualcosa di esotico, un problema del cliente di solito è:

1. **KYC scaduto** — i trasferimenti si interrompono, tutto il resto sembra normale.
2. **Wallet non registrato o non ammesso**: i trasferimenti falliscono sulla chain invece di restare in sospeso.
3. **Ruolo mancante**: ricevono un `403` e lo chiamano "la pagina è rotta".

A `401` significa che il token non è valido. Un `403` significa che il token va bene e il ruolo no. Questa distinzione da sola risolve una grande percentuale di ticket.

---

## Il portale dell'operatore

Su `:44200`. Ignora completamente il gateway e utilizza il login integrato con nome utente/password, con TOTP locale per l'autenticazione rafforzata (step-up) — in ogni configurazione, comprese le distribuzioni in cui i clienti utilizzano Microsoft Entra ID.

| Area | |
|---|---|
| **Customers** | Soggetti giuridici, il loro stato, il loro KYC. |
| **Onboarding** | Crea soggetti, genera token di invito. |
| **Assets** | Ogni emissione per ogni cliente. |
| **Users** | Account e ruoli, incluso il [supporto 2FA](customers/two-factor-support.md). |
| **Compliance** | Casi di screening delle sanzioni, revisione KYC. |
| **Audit** | La pista di controllo a prova di manomissione. |
| **Organizations / Permissions** | Identità on-chain e autorizzazioni dell'ecosistema. |
| **dApp review** | Candidature inviate al marketplace. |
| **Payment rails** | Cura del catalogo dei canali di pagamento per la gamba di contante. |
| **Wallets / Network nodes** | Wallet in custodia, salute di chain e RPC. |

!!! warning "La navigazione del portale non è un confine di sicurezza"
    I percorsi del portale operatore non vengono filtrati in base al ruolo nel browser. L'accesso viene imposto dal **backend**, per richiesta, dal tuo token.

    Quindi un utente con solo `AUDIT` vede le voci di menu per cose che non può fare e riceve un rifiuto all'apertura. Non viene esposto nulla, ma non dedurre da una voce di menu visibile che qualcuno possa usarla.

---

## Quindici minuti per un registro locale

```bash
git clone <your-registerwerk-remote> && cd registerwerk
git submodule update --init --recursive
cp .env.example.test .env
# CHAINCACHE_IMAGE in .env deve indicare un'immagine fornita indipendentemente.
docker compose up -d --build
```

Con `CHAINCACHE_ENABLED=true`, lo stesso comando avvia entrambi i workload Chaincache e i relativi
PostgreSQL privato. Registerwerk richiede solo l'immagine indicata da `CHAINCACHE_IMAGE` e
non compila `../chaincache`. Con `false`, lo stack principale si avvia in modo indipendente.

!!! danger "Lascia `JWT_ISSUER_URI` vuoto per un avvio locale"
    Impostandolo, il portale clienti passa alla modalità OIDC, che richiede un vero tenant Entra, registrazioni di app e accesso condizionale. Un URI dell'issuer configurato solo in parte genera errori di accesso che sembrano bug.

    La modalità locale è l'impostazione predefinita e il punto di partenza corretto. Attiva Entra deliberatamente, seguendo [Configurazione di Entra ID](../platform/entra-setup.md).

Poi:

| | |
|---|---|
| Portale operatore | `http://localhost:44200` |
| Portale clienti | `http://localhost:44201` |
| Salute del backend | `curl http://localhost:48080/actuator/health` |
| Attraverso il gateway | `curl http://localhost:48000/api/v1/public/chains` |
| Documentazione | `docker compose --profile docs up` → `http://localhost:48003` |

Accedi con `DEFAULT_ADMIN_EMAIL` / `DEFAULT_ADMIN_PASSWORD` dal tuo `.env`.

Kong esegue in modalità DB-less da `gateway/kong.yml`, quindi non esistono credenziali per un database del gateway, né un database `kong` o `konga`. La sua API di amministrazione è vincolata al loopback: raggiungila con `docker compose exec kong kong health`, non esporla mai.

Per qualsiasi cosa oltre una prova locale, vai a [Prerequisiti](installation/prerequisites.md) e leggi [Ambiente](configuration/environment.md) correttamente.

---

## Prima di servire clienti reali

- [ ] `DEFAULT_ADMIN_PASSWORD` e `JWT_DEV_SECRET` modificati rispetto ai valori predefiniti.
- [ ] `JWT_AUDIENCE` impostato, se Entra è abilitato. **Non facoltativo**: senza di esso, un token rilasciato a qualsiasi altra applicazione nel tenant viene accettato qui come sessione valida.
- [ ] Backup configurati **e ripristinati almeno una volta** — incluso l'archivio oggetti, che non è nel database.
- [ ] [Monitoraggio](maintenance/monitoring.md) sul posto, con avviso di headroom della partizione di controllo.
- [ ] Più di uno `REGISTRY_ADMIN`, detenuto da **persone diverse**, quindi [i controlli a quattro occhi](../compliance/step-up-mfa.md) sono reali.
- [ ] Una procedura testata [di disaster recovery](dr/runbook.md).
- [ ] I tuoi criteri di KYC e di approvazione delle emissioni messi per iscritto, in modo che le decisioni siano coerenti e spiegabili.

---

## Dove andare adesso

- [Come è costruito Registerwerk](architecture.md)
- [Servire i clienti](customers/index.md)
- [Risoluzione dei problemi](troubleshooting.md)
