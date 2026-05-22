---
title: MiFIR Transaction Reporting
description: MiFID II / MiFIR RTS 22 transaction reporting implementation.
---

# MiFIR Transaction Reporting (RTS 22)

**MiFIR** (Regulation (EU) 600/2014) and its delegated regulation **RTS 22** (Commission Delegated Regulation (EU) 2017/590) require investment firms and trading venues to report transactions in financial instruments to competent authorities **by the end of the working day following the transaction**.

---

## Applicability to Registerwerk

MiFIR reporting applies when a security token qualifies as a **MiFID II financial instrument** — specifically as a transferable security (Art. 4(1)(44)(a) MiFID II) or a money market instrument. The determination depends on jurisdiction:

| Jurisdiction | MiFIR applies | Notes |
|---|---|---|
| DE_EWPG | ✅ | eWpG tokens = transferable securities under German transposition |
| LU_CSSF | ✅ | DLT-based securities = financial instruments under LU law |
| FR_AMF | ✅ | Minibons + titres financiers = financial instruments |
| LI_TVTG | Via passporting | Bilateral arrangement pending; flag set to optional |

For instruments that do NOT qualify as MiFID II financial instruments (e.g., utility tokens, MiCAR-regulated crypto-assets), MiFIR does not apply and no report is generated.

---

## Trigger event

A MiFIR report is generated for every `TradeExecution` that:

1. Is associated with an `Asset` with `mifirReportable = true`
2. Has status `SETTLED`
3. Has not yet been included in a `RegreportSubmission` for the reporting day

`MifirReportingService` runs daily as a `@Scheduled` job and also accepts on-demand triggers via `POST /api/v1/regulatory-reporting/mifir`.

---

## Report fields (RTS 22 Annex I)

The key fields in each transaction record:

| RTS 22 field | Source in Registerwerk |
|---|---|
| Transaction reference | `TradeExecution.id` |
| Trading venue (MIC) | `TradingVenue.mic` |
| Financial instrument identifier (ISIN) | `Asset.isin` |
| Transaction price | `TradeExecution.unitPrice` |
| Transaction quantity | `TradeExecution.executedQuantity` |
| Transaction date/time | `TradeExecution.executedAt` |
| Buyer LEI | `LegalEntity.lei` (buyer side) |
| Seller LEI | `LegalEntity.lei` (seller side) |
| Counterparty capacity | `TradeExecution.capacityType` (DEAL / MATCHED_PRINCIPAL / OTHER) |
| Reporting firm LEI | Operator's LEI (configuration) |
| Short selling indicator | `TradeExecution.shortSellIndicator` |
| Commodity derivative indicator | Always `false` for securities |

---

## Per-jurisdiction filing strategy

The `regreporting` module uses a Strategy pattern: `MifirFilingStrategy` is implemented per jurisdiction.

| Jurisdiction | Strategy class | Filing channel |
|---|---|---|
| DE_EWPG | `BafinMifirStrategy` | BaFin MeldewesenPortal (SFTP) |
| LU_CSSF | `CssfMifirStrategy` | CSSF Transaction Reporting API |
| FR_AMF | `AmfMifirStrategy` | AMF ROSA (Reporting On Securities and Assets) |
| LI_TVTG | `FmaMifirStrategy` | Bilateral arrangement (optional, log only until activated) |

Each strategy:
1. Generates an XML file per ESMA ISO 20022 schema (auth.030.001.02 for transaction reports)
2. Delivers the file to the authority's endpoint
3. Stores the delivery receipt in `RegreportSubmission`

---

## `RegreportSubmission` entity

Tracks every filing:

| Field | Description |
|---|---|
| `reportType` | `MIFIR_RTS22` |
| `periodStart` / `periodEnd` | Reporting period (typically one trading day) |
| `jurisdiction` | Which authority this was filed with |
| `status` | `PENDING` / `SUBMITTED` / `ACCEPTED` / `REJECTED` |
| `submittedAt` | Filing timestamp |
| `authorityRef` | Reference number returned by the authority |
| `recordCount` | Number of transactions in the file |
| `s3Key` | Object storage key for the filed XML (for re-submission if needed) |

Failed submissions trigger a `COMPLIANCE_OFFICER` notification and are automatically retried up to 3 times before requiring manual intervention.

---

## Exemptions

The following transaction types are **exempt** from MiFIR reporting even for reportable instruments:

- Intra-group transfers (both sides same group entity, as identified by matching first 6 characters of LEI)
- Primary market transactions (new issuance directly from issuer to first holder)
- Off-exchange transfers (force-transfer by registry operator not going through a trading venue)

Exempted transactions have `mifirExempt = true` and `mifirExemptReason` set on the `TradeExecution` record.
