---
title: Bonos DAML Finance (Canton)
description: Canton / Estándares de bonos DAML Finance para implementaciones de ledger privado.
---

# Bonos DAML Finance (Canton) { #daml-finance-bonds-canton }

Canton es un ledger distribuido que prioriza la privacidad, construido sobre el lenguaje de contrato inteligente **DAML**. DAML Finance proporciona una biblioteca de primitivas financieras componibles para Canton — incluidos bonos, acciones y derivados. Registerwerk admite tres tipos de bonos de DAML Finance en Canton para implementaciones de ledger privado.

---

## Tipos de bonos de DAML Finance admitidos { #supported-daml-finance-bond-types }

| Estándar | Enumeración de token | Descripción |
|---|---|---|
| `DAML_BOND_FIXED` | Bono a tipo fijo | Cupón conocido, calendario fijo |
| `DAML_BOND_FLOATING` | Bono a tipo variable | Tipo ligado a EURIBOR/SOFR/otra referencia |
| `DAML_BOND_ZERO` | Bono cupón cero | Sin cupón periódico; cotiza con descuento |
| `CANTON_TOKEN` | Activo genérico de Canton | Cualquier activo digital basado en DAML |

---

## En qué se diferencia Canton de EVM { #how-canton-differs-from-evm }

| Dimensión | EVM (estándares ERC) | Canton (DAML Finance) |
|---|---|---|
| Privacidad | Ledger público (todos los participantes ven el estado) | Privado — cada participante ve solo sus propios contratos |
| Lenguaje de contrato inteligente | Solidity / Vyper | DAML (similar a Haskell) |
| Finalidad | Probabilística (n confirmaciones) | Determinista (confirmación de la Ledger API) |
| Identidad | Dirección de monedero | Canton Party (identificador único por participante) |
| Liquidación fuera del ledger | Opcional | Nativa: el flujo de trabajo de DAML incluye la liquidación |
| Posiciones confidenciales | Requiere Zama fhEVM | Nativas — contratos privados |

---

## Asignación de Canton Party { #canton-party-allocation }

Cada `LegalEntity` en Registerwerk tiene una **Canton Party** — un identificador único en el ledger de Canton. Esto lo gestiona el servicio `CantonPartyAllocator` del módulo `blockchain`:

1. Cuando se incorpora un cliente con un instrumento compatible con Canton, `CantonPartyAllocator.allocate(entityId)` registra la entidad en el ledger de Canton
2. El identificador de party se almacena en `LegalEntity.cantonPartyId`
3. Todos los contratos de DAML Finance hacen referencia a la Canton Party, no a una dirección de monedero

---

## Correspondencia de las condiciones del bono { #bond-terms-mapping }

`AssetBondTerms` almacena los parámetros financieros de todos los tipos de bono:

| Campo | DAML_BOND_FIXED | DAML_BOND_FLOATING | DAML_BOND_ZERO |
|---|---|---|---|
| `couponRate` | Fijo (por ejemplo, 5,0 %) | Diferencial sobre el tipo de referencia | N/D |
| `referenceRate` | N/D | por ejemplo, EURIBOR_3M | N/D |
| `maturityDate` | ✅ | ✅ | ✅ |
| `paymentFrequency` | ANNUAL / SEMIANNUAL / QUARTERLY / MONTHLY | Igual | N/D |
| `dayCountConvention` | ACT_365 / ACT_ACT / 30_360 | Igual | ACT_365 |
| `issuePrice` | 100 (a la par) o con descuento/prima | A la par | Con descuento (< 100) |

---

## Pago de cupón en Canton { #coupon-payment-on-canton }

Para `DAML_BOND_FIXED` y `DAML_BOND_FLOATING`, el método `CantonBondOperations.payCoupon()` ejecuta el flujo de trabajo de pago de cupón de DAML Finance:

1. El nodo participante de Registerwerk en Canton propone un contrato de pago de cupón a la party del emisor
2. El nodo del emisor ejerce el choice del ciclo de vida del cupón
3. Todas las parties titulares de bonos reciben sus importes de cupón a través del lote de liquidación de DAML
4. El registro `CorporateAction(type=COUPON, status=SETTLED)` se actualiza en la base de datos de Registerwerk

---

## El perfil de Maven `-Pcanton` { #the-pcanton-maven-profile }

El soporte de Canton requiere el SDK de DAML y las bibliotecas Java asociadas. Estas se activan mediante el perfil de Maven `-Pcanton`:

```bash
cd backend && ./mvnw verify -Pcanton
```

Sin este perfil, se inyecta `CantonBondDisabledStub` en lugar del cliente Canton real, y todas las llamadas de API relacionadas con Canton devuelven `503 Service Unavailable` con un mensaje descriptivo. Esto permite que la aplicación arranque limpiamente sin un nodo participante de Canton.
