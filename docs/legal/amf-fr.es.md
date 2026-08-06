---
title: Francia — AMF
description: Cómo implementa Registerwerk los requisitos reglamentarios franceses AMF y Loi PACTE para valores tokenizados.
---

# Francia — AMF { #france-amf }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Esta página registra las asignaciones de control previstas y los supuestos configurados. No es asesoramiento legal
    francés ni evidencia de la clasificación del instrumento, la autorización regulatoria, el cumplimiento o el
    efecto legal. Obtenga una revisión actual y específica de instrumento, operador, servicio e implementación.

Francia creó uno de los primeros marcos legales específicos de Europa para instrumentos financieros basados en tokens a través de la **Loi PACTE** (Plan d'Action pour la Croissance et la Transformation des Entreprises, 2019). La **Autorité des Marchés Financiers (AMF)** supervisa a los emisores y a los proveedores de servicios.

---

## Marco regulatorio aplicable { #applicable-regulatory-framework }

| Reglamento | Alcance |
|---|---|
| Loi PACTE 2019-486 | Valores basados en tokens (minibons, titres financiers) |
| Code monétaire et financier (CMF) | Servicios de inversión, AML |
| Reglamento General de la AMF (Règlement général) | Conducta de mercado, folleto, emisión de tokens |
| AMF DOC-2022-15 | Orientación para los DASP (proveedores de servicios de activos digitales) |
| Guía PSAN de la ACPR | AML para entidades registradas como PSAN |
| MiCAR (UE) 2023/1114 | Aplicabilidad total para los CASP |
| DORA (UE) 2022/2554 | Resiliencia de TIC |

---

## PSAN — Registro de proveedor de servicios de activos digitales { #psan-digital-asset-service-provider-registration }

La ley francesa exige que las entidades que brindan servicios de activos digitales se registren en **AMF** como **Prestataire de Services sur Actifs Numériques (PSAN)**. Con la adopción de MiCAR en 2024, el registro PSAN pasa a una autorización MiCAR CASP, pero los registros PSAN existentes tienen derechos adquiridos durante un período de transición.

El perfil de jurisdicción `FR_AMF` de Registerwerk conserva en su configuración el número de registro PSAN/CASP del operador. Ese número aparece en las presentaciones reglamentarias.

---

## Diferencias clave con respecto a Alemania { #key-differences-from-germany }

| Dimensión | DE (eWpG) | FR (AMF) |
|---|---|---|
| Ley principal sobre tokens | eWpG (específica de valores) | Loi PACTE / CMF (DLT general) |
| Tipo de registro admitido | Centralizado + descentralizado | Registro basado en DLT (minibons, obligations) |
| Autoridad competente | BaFin | AMF (valores) + ACPR (banca/AML) |
| Período de conservación | 10 años | 5 años |
| Documento KYC — registro mercantil | Handelsregisterauszug | Extrait Kbis (≤ 3 meses de antigüedad) |
| Registro de titulares reales | Transparenzregister | Registre des Bénéficiaires Effectifs (RBE) |
| Cuestionario AML | Específico del GwG | Específico de AMF/ACPR PSAN |
| Informes TRACFIN | BaFin | AMF/ACPR remiten a TRACFIN |

---

## Requisitos de documentos KYC para `FR_AMF` { #kyc-document-requirements-for-framf }

El perfil de jurisdicción `FR_AMF` en `JurisdictionRequirementConfig` requiere:

- **Extracto Kbis** (≤ 3 meses de antigüedad, del Greffe du Tribunal de Commerce)
- **Déclaration de bénéficiaires effectifs** del RBE nacional
- Statuts (estatutos sociales)
- Documentos de identidad para todos los directores y UBO
- Informe anual (últimos 2 años si están disponibles)
- Cuestionario AML de AMF/ACPR
- Declaración de origen de los fondos (para inversiones que superen el umbral de la AMF)

---

## Minibons y titres financiers { #minibons-and-titres-financiers }

La ley francesa permite la tokenización de dos categorías de instrumentos:

**Minibons** (instrumentos de deuda de crowdfunding): bonos a corto plazo emitidos a través de plataformas de crowdfunding, ahora elegibles para emisiones basadas en DLT bajo Loi PACTE.

**Titres financiers** (instrumentos financieros): acciones y instrumentos de deuda de cualquier tipo, elegibles para emisión basada en DLT a través de un Prestataire de Compensation (contraparte central equivalente en el contexto de DLT).

Ambos se representan en Registerwerk mediante [ERC-3643](../token-standards/erc3643.md) (vinculado a identidad, regulado) o [ERC-3525](../token-standards/erc3525.md) (bonos divididos en tramos). El despliegue bajo `FR_AMF` activa comprobaciones adicionales:

1. Notificación AMF del programa de token (almacenado como `Asset.regulatoryNotificationRef`)
2. Verificación de asignación ISIN
3. Verificación de exención del folleto (por debajo del umbral de 8 millones de euros para minibons)

---

## Informes MiFIR para Francia { #mifir-reporting-for-france }

La aplicabilidad de MiFIR, la capacidad de presentación de informes, la autoridad competente y el canal
requieren una revisión externa específica de la transacción y del instrumento. El servicio actual de
[MiFIR](../compliance/mifir.md) produce un XML de prototipo `DRAFT_UNVALIDATED`; no cuenta con una
estrategia `FR_AMF` y no presenta ni acredita la entrega a la AMF ni a ninguna otra autoridad.

---

## TRACFIN — Notificación de operaciones sospechosas { #tracfin-suspicious-transaction-reporting }

El alcance y el proceso franceses de notificación de inteligencia financiera requieren una revisión
externa. El módulo de detección de Registerwerk registra las ejecuciones de detección y las decisiones
de revisión del operador, pero no presenta una declaración a TRACFIN ni verifica de forma independiente
una referencia de declaración.

---

## Notificación de incidentes a DORA (Francia) { #dora-incident-reporting-france }

El alcance de la autoridad y los plazos actuales de notificación de incidentes requieren una revisión
externa. El módulo `dora` no enruta ni transmite incidentes a la ACPR, la AMF ni a ninguna otra
autoridad. Los valores siguientes son supuestos de diseño históricos, no evidencia configurada de
presentación:

- Notificación inicial: 4 horas desde la clasificación como grave
- Informe intermedio: 72 horas
- Informe final: 30 días

Véase [DORA](../compliance/dora.md).
