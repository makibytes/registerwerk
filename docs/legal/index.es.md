---
title: Marcos legales
description: Descripción general de las cuatro jurisdicciones admitidas y sus marcos regulatorios.
---

# Marcos legales { #legal-frameworks }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Esta página registra las asignaciones de control previstas y los supuestos configurados. No es un consejo legal
    o evidencia de cumplimiento, autorización regulatoria, certificación o efecto legal.
    La aplicabilidad depende del operador, servicio, instrumento, transacción, jurisdicción y la implementación
    y debe ser aprobada por un asesor calificado y los propietarios de control responsables.

Registerwerk contiene componentes técnicos y de configuración destinados a respaldar implementaciones en cuatro jurisdicciones europeas. Las tablas siguientes son datos de revisión, no determinaciones de que se aplica una ley o de que se han implementado todas las obligaciones.

---

## Jurisdicciones admitidas { #supported-jurisdictions }

| Jurisdicción | Autoridad competente | Ley principal | Marco de tokens | Retención | MiFIR | MiCAR |
|---|---|---|---|---|---|---|
| 🇩🇪 **Alemania** | BaFin | eWpG/GwG | Kryptowertpapier — valor cripto (KryptoFAV) | 10 años | Sí | No (MiCAR Art. 2(3)) |
| 🇱🇺 **Luxemburgo** | CSSF | Circular CSSF 19/732 / Ley AML de 2004 | Instrumentos de fondos basados en DLT | 5 años | Sí | Sí |
| 🇫🇷 **Francia** | AMF | Code monétaire / Loi PACTE | Minibons / Titres financiers | 5 años | Sí | Sí |
| 🇱🇮 **Liechtenstein** | FMA | TVTG 2020 / SPG | Token (proveedor de servicios TT) | 10 años | Mediante pasaporte | Sí |

---

## La enumeración `Jurisdiction` { #the-jurisdiction-enum }

En el código, cada jurisdicción está representada por la enumeración `Jurisdiction` en el módulo `customer`:

```java
public enum Jurisdiction {
    DE_EWPG,   // Germany — eWpG
    LU_CSSF,   // Luxembourg — CSSF
    FR_AMF,    // France — AMF
    LI_TVTG    // Liechtenstein — TVTG
}
```

A `LegalEntity` lleva un único `Jurisdiction` configurado. El código utiliza ese valor para perfiles y flujos de trabajo seleccionados; no es una decisión de clasificación de instrumentos y no prueba que una autoridad reciba un informe o que un período de retención configurado sea legalmente correcto.

---

## Configuración por jurisdicción { #per-jurisdiction-configuration }

La clase `JurisdictionRequirementConfig` (`kyc/api/`) contiene supuestos de aplicación para el comportamiento por jurisdicción seleccionado. No es una fuente legal de verdad. Produce un bean `JurisdictionProfile` por jurisdicción, que contiene valores configurados como:

- Tipos de documentos KYC requeridos (consulte [KYC y AML](../compliance/kyc-aml.md))
- Proveedores de detección de sanciones (OpenSanctions + Refinitiv World-Check opcional)
- Umbral de titular real (25 % en las cuatro jurisdicciones)
- Cadencia de renovación de KYC (365 días para todas, con monitoreo reforzado para Luxemburgo)
- Umbral de la Travel Rule (1.000 € en todas)
- Autoridad supervisora para las notificaciones de incidentes DORA

---

## Obligaciones comunes { #common-obligations }

El repositorio agrupa varios componentes técnicos bajo títulos de cumplimiento comunes. Su presencia no establece que se aplique o haya sido satisfecha una obligación:

| Obligación | Implementación | Referencia |
|---|---|---|
| Verificación de identidad del cliente | `KycDocument`, `NaturalPerson`, `BeneficialOwner` | [KYC & AML](../compliance/kyc-aml.md) |
| Monitoreo continuo de AML | `KycMonitoringJob`, revisión de sanciones | [Detección de sanciones](../compliance/sanctions-screening.md) |
| Travel Rule / IVMS-101 | `TravelRuleProtocolPort`, `Ivms101` | [Travel Rule](../compliance/travel-rule.md) |
| Integridad del registro de valores | Cadena de hash `audit_event` a prueba de manipulaciones | [Registro de auditoría](../platform/audit-log.md) |
| Restricciones comerciales | `HolderBlock` (Sperrvermerk) | [Sperrvermerk](../compliance/sperrvermerk.md) |
| Gestión de incidentes de TIC | `IctIncident`, `ThirdPartyProvider` | [DORA](../compliance/dora.md) |
| Informes de transacciones | `MifirReportingService` | [MiFIR](../compliance/mifir.md) |
| Declaración de impuestos sobre criptoactivos | Módulo `regreporting` | [DAC8 / CARF](../compliance/dac8.md) |

---

## Explorar por jurisdicción { #explore-by-jurisdiction }

- [Alemania — eWpG](ewpg.md)
- [Luxemburgo — CSSF](cssf-lu.md)
- [Francia — AMF](amf-fr.md)
- [Liechtenstein — TVTG](tvtg-li.md)
