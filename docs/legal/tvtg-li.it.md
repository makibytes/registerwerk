---
title: Liechtenstein — TVTG
description: Come Registerwerk implementa gli obblighi di due diligence del Liechtenstein TVTG (Token Act) e SPG.
---

# Liechtenstein — TVTG (Atto sui token) { #liechtenstein-tvtg-token-act }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Questa pagina registra le mappature dei controlli previste e le ipotesi configurate. Non si tratta di consulenza legale del Liechtenstein
    né di prova della classificazione, registrazione, autorizzazione normativa, conformità
    dello strumento o effetto legale. Ottieni una revisione aggiornata specifica per strumento, operatore, servizio e implementazione.

Il Liechtenstein è stato il primo paese europeo ad approvare una legislazione completa specifica per i token. Il **Token and Trusted Technologies Service Provider Act** (TVTG, in vigore dal 1° gennaio 2020) ha creato un quadro giuridico neutrale e indipendente dalla tecnologia che considera i token come contenitori di diritti di qualsiasi tipo, compresi gli strumenti finanziari.

---

## Il modello TVTG { #the-tvtg-model }

Il TVTG stabilisce il concetto di **Token** come record di dati in un sistema TT (Trusted Technology) (ovvero un registro distribuito o un sistema equivalente protetto crittograficamente). I diritti sono collegati ai token piuttosto che direttamente all'asset sottostante, creando una netta separazione legale tra il diritto (token) e la sua rappresentazione tecnica (blockchain).

Ciò si allinea bene con il modello di registro canonico di Registerwerk: la voce del registro è lo strumento legale; la blockchain è una rappresentazione.

---

## Quadro normativo applicabile { #applicable-regulatory-framework }

| Regolamento | Ambito |
|---|---|
| TVTG (LGBl. 2019 Nr. 301) | Classificazione dei token, licenza dei fornitori di servizi |
| SPG (Sorgfaltspflichtgesetz) | Due diligence / AML per i fornitori di servizi TT |
| VPG (Vermögensverwaltungsgesetz) | Obblighi di gestione patrimoniale |
| FMA-Wegleitung TVTG | Linee guida di vigilanza della FMA del Liechtenstein |
| MiCAR (UE) 2023/1114 | Si applica tramite l'accordo SEE |
| DORA (UE) 2022/2554 | Resilienza ICT tramite l'accordo SEE |

---

## Licenza di fornitore di servizi TT { #tt-service-provider-licence }

Le entità che gestiscono un sistema TT per strumenti finanziari devono ottenere una licenza di **fornitore di servizi TT** dalla **Finanzmarktaufsicht (FMA)**. La configurazione `LI_TVTG` di Registerwerk memorizza il numero di licenza dell'operatore. Il tipo di licenza determina quali servizi possono essere forniti; Registerwerk si rivolge alle categorie di servizio **TT Token Issuer** e **TT Register Operator**.

---

## TVTG §9 — Obbligo di whitepaper sui token { #tvtg-9-token-whitepaper-obligation }

A differenza della Germania (nessun whitepaper richiesto per i titoli elettronici in quanto tali) e della Francia (documento informativo AMF), il TVTG §9 del Liechtenstein richiede un **whitepaper sui token** per ogni offerta pubblica di token. Il whitepaper deve descrivere:

- I diritti rappresentati dal token
- Le specifiche tecniche
- Rischi per i possessori di token
- Termini e condizioni

**Implementazione:** Registerwerk memorizza il documento whitepaper del token nella tabella `kyc_document` sotto tipo `TOKEN_WHITEPAPER`. Per gli emittenti `LI_TVTG`, il flusso di lavoro di distribuzione blocca l'emissione di token finché un documento `TOKEN_WHITEPAPER` con `status = APPROVED` non viene associato all'asset.

---

## Requisito di audit del contratto intelligente { #smart-contract-audit-requirement }

Le linee guida FMA consigliano (e per alcune categorie di licenza richiedono) un audit indipendente del codice del contratto intelligente prima dell'emissione pubblica. Registerwerk memorizza il rapporto di audit come `kyc_document` di tipo `SMART_CONTRACT_AUDIT`.

---

## SPG — Obblighi di dovuta diligenza { #spg-due-diligence-obligations }

Il **Sorgfaltspflichtgesetz** impone obblighi di due diligence AML/CFT ai fornitori di servizi TT, equivalenti ai requisiti AMLD5/AMLD6. Differenze chiave rispetto al GwG tedesco:

| Aspetto | DE (GwG) | LI (SPG) |
|---|---|---|
| Soglia UBO | 25% | 25% |
| Screening PEP | Obbligatorio | Obbligatorio |
| Periodo di conservazione | 6 anni (GwG §8) | 10 anni (TVTG Art. 10) |
| Persone politicamente esposte | Adeguata verifica rafforzata completa | Adeguata verifica rafforzata completa + notifica alla FMA |
| Registro dei titolari effettivi | Transparenzregister | Handelsregister del Liechtenstein (sezione UBO) |

---

## KYC requisiti dei documenti per `LI_TVTG` { #kyc-document-requirements-for-litvtg }

Il profilo giurisdizionale `LI_TVTG` richiede:

- **Handelsregisterauszug** (estratto del registro di commercio del Liechtenstein, ≤ 3 mesi)
- **Dichiarazione UBO** allineata al formato del registro del Liechtenstein
- Documenti d'identità per amministratori e UBO
- **Token whitepaper** (`TOKEN_WHITEPAPER`) — obbligatorio, deve essere approvato prima della distribuzione
- **Rapporto di audit dello smart contract** (`SMART_CONTRACT_AUDIT`) — obbligatorio per le offerte pubbliche
- Copia o conferma della **licenza del fornitore di servizi TT**
- Rendiconti finanziari annuali (ultimi 2 anni)

---

## Conservazione: 10 anni { #retention-10-years }

Il Liechtenstein richiede una conservazione di 10 anni per tutti i record relativi alle transazioni di token, corrispondenti alla Germania ma superiori a Lussemburgo e Francia. Il profilo della giurisdizione `LI_TVTG` imposta `retentionYears = 10`.

---

## Reporting MiFIR per il Liechtenstein { #mifir-reporting-for-liechtenstein }

L'applicabilità del MiFIR, la capacità di reporting, l'autorità competente e il canale richiedono una revisione esterna
attuale. Non esiste una strategia di presentazione `LI_TVTG` in `MifirReportingService`; il servizio attuale
produce solo il prototipo `DRAFT_UNVALIDATED` descritto in [MiFIR](../compliance/mifir.md).

---

## Segnalazione degli incidenti alla FMA { #fma-incident-reporting }

L'applicabilità DORA/SEE, l'autorità competente e le scadenze richiedono una revisione esterna attuale. Il modulo `dora`
non instrada né trasmette notifiche di incidenti `LI_TVTG` alla FMA.

---

## Perché il Liechtenstein per gli emittenti nativi blockchain { #why-liechtenstein-for-blockchain-native-issuers }

Il Liechtenstein offre il quadro giuridico più nativamente blockchain d'Europa:

- I token sono legalmente riconosciuti indipendentemente dalla tecnologia sottostante
- Qualsiasi diritto può essere tokenizzato: strumenti finanziari, beni immobili, diritti di proprietà intellettuale
- Il TVTG è neutrale dal punto di vista tecnologico (EVM, UTXO e DAG si qualificano tutti)
- Non è necessaria alcuna designazione separata di "titoli crittografici" — è il token stesso a incorporare il diritto

Ciò rende `LI_TVTG` interessante per tipologie di strumenti innovativi come le [obbligazioni semi-fungibili ERC-3525](../token-standards/erc3525.md), i [token vault ERC-4626](../token-standards/erc4626.md) e gli [strumenti finanziari DAML](../token-standards/canton-daml.md), per i quali non esiste ancora un tipo di strumento nazionale equivalente.
