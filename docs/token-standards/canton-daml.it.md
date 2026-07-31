---
title: DAML Finance Bonds (Canton)
description: Standard di obbligazioni DAML Finance / Canton per distribuzioni su registro privato.
---

# DAML Finance Bonds (Canton) { #daml-finance-bonds-canton }

Canton è un registro distribuito incentrato sulla privacy, basato sul linguaggio dei contratti intelligenti **DAML**. DAML Finance fornisce una libreria di primitive finanziarie componibili per Canton, tra cui obbligazioni, azioni e derivati. Registerwerk supporta tre tipi di obbligazioni DAML Finance su Canton per distribuzioni su registro privato.

---

## Tipi di obbligazioni DAML Finance supportati { #supported-daml-finance-bond-types }

| Standard | Enumerazione token | Descrizione |
|---|---|---|
| `DAML_BOND_FIXED` | Obbligazione a tasso fisso | Tasso cedolare noto, calendario fisso |
| `DAML_BOND_FLOATING` | Obbligazione a tasso variabile | Tasso vincolato a EURIBOR/SOFR/altro riferimento |
| `DAML_BOND_ZERO` | Obbligazione a cedola zero | Nessuna cedola periodica; viene scambiato con uno sconto |
| `CANTON_TOKEN` | Asset Canton generico | Qualsiasi risorsa digitale basata su DAML |

---

## In cosa differisce Canton da EVM { #how-canton-differs-from-evm }

| Dimensione | EVM (standard ERC) | Canton (DAML Finance) |
|---|---|---|
| Privacy | Registro pubblico (tutti i partecipanti vedono lo stato) | Privato: ogni partecipante vede solo i propri contratti |
| Linguaggio del contratto intelligente | Solidity/Vyper | DAML (simile a Haskell) |
| Finalità | Probabilistico (n conferme) | Deterministico (conferma della ledger API) |
| Identità | Indirizzo del wallet | Canton Party (identificativo univoco per partecipante) |
| Regolamento fuori registro | Facoltativo | Nativo: il flusso di lavoro DAML include il regolamento |
| Posizioni riservate | Richiede Zama fhEVM | Nativo — contratti privati |

---

## Assegnazione del Canton Party { #canton-party-allocation }

Ogni `LegalEntity` in Registerwerk ha un **Canton Party** — un identificativo univoco sul registro Canton. Questo è gestito dal servizio `CantonPartyAllocator` nel modulo `blockchain`:

1. Quando viene integrato un cliente con uno strumento compatibile con Canton, `CantonPartyAllocator.allocate(entityId)` registra l'entità nel registro Canton
2. L'identificativo del party è memorizzato in `LegalEntity.cantonPartyId`
3. Tutti i contratti DAML Finance fanno riferimento al Canton Party, non a un indirizzo wallet

---

## Mappatura dei termini delle obbligazioni { #bond-terms-mapping }

`AssetBondTerms` memorizza i parametri finanziari per tutti i tipi di obbligazioni:

| Campo | DAML_BOND_FIXED | DAML_BOND_FLOATING | DAML_BOND_ZERO |
|---|---|---|---|
| `couponRate` | Fisso (ad esempio, 5,0%) | Spread del tasso di riferimento | N/A |
| `referenceRate` | N/A | ad esempio, EURIBOR_3M | N/A |
| `maturityDate` | ✅ | ✅ | ✅ |
| `paymentFrequency` | ANNUAL / SEMIANNUAL / QUARTERLY / MONTHLY | Lo stesso | N/A |
| `dayCountConvention` | ACT_365 / ACT_ACT / 30_360 | Lo stesso | ACT_365 |
| `issuePrice` | 100 (par) o sconto/premio | Par | Sconto (< 100) |

---

## Pagamento cedola su Canton { #coupon-payment-on-canton }

Per `DAML_BOND_FIXED` e `DAML_BOND_FLOATING`, il metodo `CantonBondOperations.payCoupon()` esercita il flusso di lavoro di pagamento cedola di DAML Finance:

1. Il nodo partecipante Canton di Registerwerk propone un contratto di pagamento cedola alla parte emittente
2. Il nodo dell'emittente esercita la scelta del ciclo di vita della cedola
3. Tutti i detentori di obbligazioni ricevono gli importi delle loro cedole tramite il batch di regolamento di DAML
4. Il record `CorporateAction(type=COUPON, status=SETTLED)` viene aggiornato nel DB di Registerwerk

---

## Il supporto del profilo Maven `-Pcanton` { #the-pcanton-maven-profile }

Canton richiede il DAML SDK e le librerie Java associate. Questi vengono attivati tramite il profilo Maven `-Pcanton`:

```bash
cd backend && ./mvnw verify -Pcanton
```

Senza questo profilo, viene inserito `CantonBondDisabledStub` al posto del vero client Canton e tutte le chiamate API correlate a Canton restituiscono `503 Service Unavailable` con un messaggio descrittivo. Ciò consente all'applicazione di avviarsi in modo pulito senza un nodo partecipante Canton.
