---
title: Lussemburgo — CSSF
description: In che modo Registerwerk implementa i requisiti normativi CSSF lussemburghesi per i titoli tokenizzati.
---

# Lussemburgo — CSSF { #luxembourg-cssf }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Questa pagina registra le mappature dei controlli previste e le ipotesi configurate. Non si tratta di consulenza legale lussemburghese
    né di prova della classificazione dello strumento, dell'autorizzazione normativa, della conformità,
    o dell'effetto legale. Ottieni una revisione aggiornata specifica per strumento, operatore, servizio e implementazione.

Il Lussemburgo è il più grande domicilio di fondi in Europa e una giurisdizione leader per gli strumenti di fondi tokenizzati. La **Commission de Surveillance du Secteur Financier (CSSF)** regola l'uso della tecnologia di registro distribuito (DLT) per gli strumenti finanziari ai sensi della Circolare CSSF 19/732 e successive linee guida.

---

## Quadro normativo applicabile { #applicable-regulatory-framework }

| Regolamento | Ambito |
|---|---|
| CSSF Circular 19/732 | Calcolo del NAV basato su DLT e amministrazione dei fondi |
| CSSF Circular 22/811 | Servizi di fondi su DLT e strumenti tokenizzati |
| AML Law 2004 (modificata) | Obblighi di adeguata verifica della clientela |
| Legge del 5 aprile 1993 (settore finanziario) | Autorizzazione delle imprese di investimento |
| MiCAR (UE) 2023/1114 | Prestatori di servizi su cripto-attività |
| DORA (UE) 2022/2554 | Resilienza operativa ICT |

---

## Differenze chiave rispetto alla Germania { #key-differences-from-germany }

| Dimensione | DE (eWpG) | LU (CSSF) |
|---|---|---|
| Registro autorevole | Il DB è canonico (§16 eWpG) | Il DB è canonico (linee guida CSSF) |
| Periodo di conservazione | 10 anni | 5 anni |
| Applicabilità MiCAR | Esente (i token eWpG non sono token di moneta elettronica) | Si applica ai servizi su cripto-attività |
| Soglia UBO | 25% (GwG §3) | 25% (AML Law Art. 1(7)) |
| Adeguata verifica rafforzata | PEP (GwG §10(2)) | PEP + paesi terzi ad alto rischio |
| Registro dei soci | Non richiesto | Obbligatorio per SICAV e SICAF |
| Dichiarazione della provenienza dei fondi | Facoltativa | Obbligatoria per tutti i clienti |

---

## Requisiti documentali KYC per `LU_CSSF` { #kyc-document-requirements-for-lucssf }

Oltre ai documenti comuni (atto costitutivo, estratto del registro delle imprese), il profilo di giurisdizione `LU_CSSF` richiede:

- **Estratto del Registre des Bénéficiaires Effectifs (RBE)** — registro dei titolari effettivi del Lussemburgo
- **Registro dei soci** — per le società di investimento (SICAV/SICAF/SIF)
- **Dichiarazione della provenienza dei fondi** — firmata dal rappresentante legale del cliente
- **Questionario AML specifico CSSF**
- Relazioni annuali (ultimi 2 anni)

Vedere [KYC e AML](../compliance/kyc-aml.md) per il ciclo di vita completo del documento.

---

## Specifiche dei token dei fondi { #fund-token-specifics }

Il Lussemburgo è la piazza principale per gli strumenti di fondo tokenizzati. Registerwerk supporta gli standard di token preferiti dalla CSSF per questo caso d'uso:

| Tipo di strumento | Standard di token | Supporto Registerwerk |
|---|---|---|
| Fondo sincrono (NAV giornaliero) | [ERC-4626](../token-standards/erc4626.md) | Completo — `AssetVaultState`, `VaultNavStrike` |
| Fondo asincrono (T+1 / T+2) | [ERC-7540](../token-standards/erc7540.md) | Completo — `VaultRequest`, flusso di richiesta e riscossione |
| Obbligazione con tranche | [ERC-3525](../token-standards/erc3525.md) | Completo — `AssetSlot` (tranche) |
| Azioni/obbligazioni regolamentate | [ERC-3643](../token-standards/erc3643.md) | Completo — T-REX legato all'identità |

L'entità `AssetVaultState` tiene traccia del NAV per quota. `VaultNavStrike` registra ogni punto di calcolo del NAV, fornendo ai regolatori una pista di controllo con timestamp di tutte le decisioni di prezzo.

---

## Tempistiche di regolamento { #settlement-timing }

Gli attuali obblighi di regolamento richiedono una revisione esterna. Il modulo `trading` può registrare un timestamp
`settledAt`, ma il prototipo [MiFIR](../compliance/mifir.md) non convalida lo stato di regolamento
né una finestra di regolamento normativa prima di selezionare le righe.

---

## Segnalazione degli incidenti alla CSSF { #cssf-incident-reporting }

Ai sensi del DORA Art. 19 (recepito in Lussemburgo tramite la legge di attuazione del DORA), gli incidenti ICT gravi devono essere segnalati alla CSSF:

- **Notifica iniziale**: entro 4 ore lavorative dalla classificazione come `MAJOR`
- **Rapporto intermedio**: entro 72 ore
- **Rapporto finale**: entro 1 mese

`DoraService` memorizza gli incidenti classificati manualmente e i timestamp dei promemoria applicativi. Non
determina la classificazione/le scadenze legalmente corrette né instrada le notifiche alla CSSF.
Vedi [DORA](../compliance/dora.md).

---

## Obblighi MiCAR (LU_CSSF) { #micar-obligations-lucssf }

Il recepimento del MiCAR da parte del Lussemburgo lo rende applicabile ai prestatori di servizi su cripto-attività che operano dal Lussemburgo. Per le implementazioni di Registerwerk con `LU_CSSF` come giurisdizione primaria:

- l'operatore deve possedere una licenza CASP dalla CSSF (o una licenza passaportabile da un altro Stato membro UE)
- la [Travel Rule](../compliance/travel-rule.md) si applica a tutti i trasferimenti di cripto-attività ≥ € 1.000
- il componente [DAC8/CARF](../compliance/dac8.md) produce un output prototipale `DRAFT_UNVALIDATED`;
  non presenta nulla all'ACD né dimostra la consegna o l'accettazione da parte dell'autorità
