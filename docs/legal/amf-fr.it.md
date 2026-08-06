---
title: Francia — AMF
description: Come Registerwerk implementa i requisiti normativi francesi AMF e Loi PACTE per i titoli tokenizzati.
---

# Francia — AMF { #france-amf }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Questa pagina registra le mappature dei controlli previste e le ipotesi configurate. Non si tratta di un parere legale francese o di una prova della classificazione dello strumento, dell'autorizzazione normativa, della conformità o dell'effetto legale. Ottieni una revisione aggiornata specifica per strumento, operatore, servizio e implementazione.

La Francia ha creato uno dei primi quadri giuridici dedicati in Europa per gli strumenti finanziari basati su token attraverso la **Loi PACTE** (Plan d'Action pour la Croissance et la Transformation des Entreprises, 2019). L'**Autorité des Marchés Financiers (AMF)** vigila sugli emittenti e sui fornitori di servizi.

---

## Quadro normativo applicabile { #applicable-regulatory-framework }

| Regolamento | Ambito |
|---|---|
| Loi PACTE 2019-486 | Titoli basati su token (minibons, titres financiers) |
| Code monétaire et financier (CMF) | Servizi di investimento, AML |
| Règlement général AMF | Comportamento di mercato, prospetto, emissione di token |
| AMF DOC-2022-15 | Linee guida per i DASP (Digital Asset Service Provider) |
| Linee guida ACPR sui PSAN | AML per le entità registrate come PSAN |
| MiCAR (UE) 2023/1114 | Piena applicabilità per i CASP |
| DORA (UE) 2022/2554 | Resilienza ICT |

---

## PSAN — Registrazione come prestatore di servizi su asset digitali { #psan-digital-asset-service-provider-registration }

La legge francese impone alle entità che forniscono servizi su asset digitali di registrarsi presso l'**AMF** come **Prestataire de Services sur Actifs Numériques (PSAN)**. Con l'adozione del MiCAR nel 2024, la registrazione PSAN evolve verso un'autorizzazione CASP ai sensi del MiCAR, ma le registrazioni PSAN esistenti restano valide (grandfathering) durante un periodo transitorio.

Il profilo di giurisdizione `FR_AMF` di Registerwerk riporta, in configurazione, il numero di registrazione PSAN/CASP dell'operatore. Questo numero compare nelle segnalazioni normative.

---

## Differenze chiave rispetto alla Germania { #key-differences-from-germany }

| Dimensione | DE (eWpG) | FR (AMF) |
|---|---|---|
| Normativa primaria sui token | eWpG (specifica per i titoli) | Loi PACTE / CMF (DLT generale) |
| Tipo di registro supportato | Centralizzato + decentralizzato | Registro basato su DLT (minibons, obligations) |
| Autorità competente | BaFin | AMF (titoli) + ACPR (bancario/AML) |
| Periodo di conservazione | 10 anni | 5 anni |
| Documento KYC — registro delle imprese | Handelsregisterauszug | Extrait Kbis (non più vecchio di 3 mesi) |
| Registro dei titolari effettivi | Transparenzregister | Registre des Bénéficiaires Effectifs (RBE) |
| Questionario AML | Specifico GwG | Specifico AMF/ACPR PSAN |
| Segnalazione TRACFIN | BaFin | AMF/ACPR inoltrano a TRACFIN |

---

## Requisiti documentali KYC per `FR_AMF` { #kyc-document-requirements-for-framf }

Il profilo di giurisdizione `FR_AMF` in `JurisdictionRequirementConfig` richiede:

- **Extrait Kbis** (non più vecchio di 3 mesi, rilasciato dal Greffe du Tribunal de Commerce)
- **Déclaration de bénéficiaires effectifs** dal RBE nazionale
- Statuts (statuto sociale)
- Documenti di identità di tutti gli amministratori e i titolari effettivi (UBO)
- Relazione annuale (ultimi 2 anni, se disponibile)
- Questionario AML AMF/ACPR
- Dichiarazione della provenienza dei fondi (per investimenti superiori alla soglia AMF)

---

## Minibons e titres financiers { #minibons-and-titres-financiers }

La legge francese consente la tokenizzazione di due categorie di strumenti:

**Minibons** (strumenti di debito da crowdfunding): obbligazioni a breve termine emesse tramite piattaforme di crowdfunding, ora idonee per l'emissione basata su DLT ai sensi della Loi PACTE.

**Titres financiers** (strumenti finanziari): strumenti azionari e di debito di qualsiasi tipo, idonei per l'emissione basata su DLT tramite un Prestataire de Compensation (equivalente della controparte centrale nel contesto DLT).

Entrambi sono rappresentati in Registerwerk usando [ERC-3643](../token-standards/erc3643.md) (vincolato all'identità, regolamentato) oppure [ERC-3525](../token-standards/erc3525.md) (obbligazioni tranched). La distribuzione sotto `FR_AMF` attiva controlli aggiuntivi:

1. notifica all'AMF del programma di token (memorizzata come `Asset.regulatoryNotificationRef`)
2. verifica dell'assegnazione dell'ISIN
3. controllo dell'esenzione dal prospetto (sotto la soglia di 8 milioni di euro per i minibons)

---

## Reporting MiFIR per la Francia { #mifir-reporting-for-france }

L'applicabilità del MiFIR, la capacità di reporting, l'autorità competente e il canale richiedono una revisione esterna specifica per transazione e
strumento. L'attuale servizio [MiFIR](../compliance/mifir.md) produce un prototipo XML
`DRAFT_UNVALIDATED`; non dispone di una strategia `FR_AMF` e non presenta né dimostra la consegna
all'AMF o a un'altra autorità.

---

## TRACFIN — Segnalazione di operazioni sospette { #tracfin-suspicious-transaction-reporting }

L'ambito e il processo di segnalazione all'intelligence finanziaria francese richiedono una revisione esterna. Il modulo di screening
di Registerwerk registra i cicli di screening e le decisioni di revisione dell'operatore, ma non invia una segnalazione a
TRACFIN né verifica in modo indipendente un riferimento a tale segnalazione.

---

## Segnalazione degli incidenti DORA (Francia) { #dora-incident-reporting-france }

L'ambito dell'autorità e le attuali scadenze per la segnalazione degli incidenti richiedono una revisione esterna. Il modulo `dora`
non instrada né trasmette incidenti ad ACPR, AMF o ad un'altra autorità. I valori seguenti
sono ipotesi di progettazione storiche, non prove di una presentazione configurata:

- Notifica iniziale: 4 ore dalla classificazione come `MAJOR`
- Rapporto intermedio: 72 ore
- Rapporto finale: 30 giorni

Vedi [DORA](../compliance/dora.md).
