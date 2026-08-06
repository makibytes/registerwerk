---
title: Liechtenstein — TVTG
description: Cómo implementa Registerwerk las obligaciones de diligencia debida TVTG (Ley de tokens) y SPG de Liechtenstein.
---

# Liechtenstein — TVTG (Ley de tokens) { #liechtenstein-tvtg-token-act }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Esta página registra las asignaciones de control previstas y los supuestos configurados. No es asesoramiento legal
    de Liechtenstein ni prueba de la clasificación, el registro, la autorización reglamentaria, el cumplimiento o el
    efecto legal del instrumento. Obtenga una revisión actual y específica de instrumento, operador, servicio e implementación.

Liechtenstein fue el primer país europeo en aprobar una legislación integral específica para tokens. La **Ley de proveedores de servicios de tokens y tecnologías confiables** (TVTG, vigente desde el 1 de enero de 2020) creó un marco legal neutral e independiente de la tecnología que trata los tokens como contenedores de derechos de cualquier tipo, incluidos los instrumentos financieros.

---

## El modelo TVTG { #the-tvtg-model }

El TVTG establece el concepto de **Token** como un registro de datos en un sistema TT (Trusted Technology) (es decir, un libro mayor distribuido o un sistema criptográficamente seguro equivalente). Los derechos se vinculan a los tokens en lugar de directamente al activo subyacente, lo que crea una separación legal nítida entre el derecho (el token) y su representación técnica (la blockchain).

Esto se alinea bien con el modelo de registro canónico de Registerwerk: la entrada del registro es el instrumento legal; la cadena de bloques es una representación.

---

## Marco regulatorio aplicable { #applicable-regulatory-framework }

| Reglamento | Alcance |
|---|---|
| TVTG (LGBl. 2019 Nr. 301) | Clasificación de tokens, licencia de proveedores de servicios |
| SPG (Sorgfaltspflichtgesetz) | Diligencia debida / AML para los proveedores de servicios TT |
| VPG (Vermögensverwaltungsgesetz) | Obligaciones de gestión de activos |
| FMA-Wegleitung TVTG | Guía de supervisión de la FMA de Liechtenstein |
| MiCAR (UE) 2023/1114 | Aplica mediante el acuerdo EEE |
| DORA (UE) 2022/2554 | Resiliencia de TIC a través del acuerdo EEE |

---

## Licencia de proveedor de servicios TT { #tt-service-provider-licence }

Las entidades que operan un sistema TT para instrumentos financieros deben obtener una licencia de **Proveedor de servicios TT** del **Finanzmarktaufsicht (FMA)**. La configuración `LI_TVTG` de Registerwerk almacena el número de licencia del operador. El tipo de licencia determina qué servicios se pueden proporcionar; Registerwerk se dirige a las categorías de servicio **TT Token Issuer** y **TT Register Operator**.

---

## TVTG §9 — Obligación del documento técnico de tokens { #tvtg-9-token-whitepaper-obligation }

A diferencia de Alemania (donde no se exige un documento técnico para los valores electrónicos propiamente dichos) y de Francia (documento informativo de la AMF), el §9 del TVTG de Liechtenstein exige un **documento técnico del token (whitepaper)** para toda oferta pública de tokens. El documento técnico debe describir:

- Los derechos representados por el token
- La especificación técnica
- Los riesgos para los titulares del token
- Los términos y condiciones

**Implementación:** Registerwerk almacena el documento técnico del token en la tabla `kyc_document` bajo el tipo `TOKEN_WHITEPAPER`. Para los emisores `LI_TVTG`, el flujo de despliegue bloquea la emisión de tokens hasta que un documento `TOKEN_WHITEPAPER` con `status = APPROVED` esté asociado al activo.

---

## Requisito de auditoría de contrato inteligente { #smart-contract-audit-requirement }

La guía de la FMA recomienda (y, para ciertas categorías de licencia, exige) una auditoría independiente del código del smart contract antes de la emisión pública. Registerwerk almacena el informe de auditoría como un `kyc_document` de tipo `SMART_CONTRACT_AUDIT`.

---

## SPG — Obligaciones de diligencia debida { #spg-due-diligence-obligations }

La **Sorgfaltspflichtgesetz** impone a los proveedores de servicios TT obligaciones de diligencia debida en materia de AML/CFT equivalentes a los requisitos de la AMLD5/AMLD6. Diferencias clave respecto del GwG alemán:

| Aspecto | DE (GwG) | LI (SPG) |
|---|---|---|
| Umbral UBO | 25 % | 25 % |
| Detección de PEP | Obligatoria | Obligatoria |
| Período de conservación | 6 años (GwG §8) | 10 años (TVTG Art. 10) |
| Personas políticamente expuestas | Diligencia debida reforzada completa | Diligencia debida reforzada completa + notificación a la FMA |
| Registro de titulares reales | Transparenzregister | Handelsregister de Liechtenstein (sección UBO) |

---

## Requisitos de documentos KYC para `LI_TVTG` { #kyc-document-requirements-for-litvtg }

El perfil de jurisdicción `LI_TVTG` requiere:

- **Handelsregisterauszug** (extracto del registro comercial de Liechtenstein, ≤ 3 meses)
- **Declaración UBO** alineada con el formato de registro de Liechtenstein
- Documentos de identidad para directores y UBO
- **Documento técnico del token** (`TOKEN_WHITEPAPER`) — obligatorio, debe aprobarse antes del despliegue
- **Informe de auditoría del smart contract** (`SMART_CONTRACT_AUDIT`) — obligatorio para ofertas públicas
- **Copia de la licencia de proveedor de servicios TT** o confirmación
- Estados financieros anuales (últimos 2 años)

---

## Retención: 10 años { #retention-10-years }

Liechtenstein exige una retención de 10 años para todos los registros relacionados con transacciones de tokens, igualando a Alemania pero superando a Luxemburgo y Francia. El perfil de jurisdicción `LI_TVTG` establece `retentionYears = 10`.

---

## Informes MiFIR para Liechtenstein { #mifir-reporting-for-liechtenstein }

La aplicabilidad de MiFIR, la capacidad de presentación de informes, la autoridad competente y el canal
requieren una revisión externa actual. No existe una estrategia de presentación `LI_TVTG` en
`MifirReportingService`; el servicio actual produce únicamente el prototipo `DRAFT_UNVALIDATED`
descrito en [MiFIR](../compliance/mifir.md).

---

## Informes de incidentes a la FMA { #fma-incident-reporting }

La aplicabilidad de DORA/EEE, la autoridad competente y los plazos requieren una revisión externa
actual. El módulo `dora` no enruta ni transmite a la FMA las notificaciones de incidentes `LI_TVTG`.

---

## Por qué Liechtenstein para emisores nativos de blockchain { #why-liechtenstein-for-blockchain-native-issuers }

Liechtenstein ofrece el marco legal más nativo de blockchain en Europa:

- Los tokens se reconocen legalmente independientemente de la tecnología subyacente
- Cualquier derecho puede ser tokenizado: instrumentos financieros, bienes raíces, derechos de propiedad intelectual
- El TVTG es tecnológicamente neutral (EVM, UTXO y DAG todos califican)
- No se necesita una designación separada de "valores criptográficos": el token en sí lleva el derecho

Esto hace que `LI_TVTG` resulte atractivo para tipos de instrumentos innovadores como los [bonos semifungibles ERC-3525](../token-standards/erc3525.md), los [tokens de bóveda ERC-4626](../token-standards/erc4626.md) y los [instrumentos DAML Finance](../token-standards/canton-daml.md), para los que aún no existe un tipo de instrumento nacional equivalente.
