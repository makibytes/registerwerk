---
title: Luxemburgo — CSSF
description: Cómo implementa Registerwerk los requisitos reglamentarios de Luxemburgo CSSF para valores tokenizados.
---

# Luxemburgo — CSSF { #luxembourg-cssf }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Esta página registra las asignaciones de control previstas y los supuestos configurados. No es asesoramiento
    jurídico de Luxemburgo, ni evidencia de la clasificación del instrumento, la autorización regulatoria, el
    cumplimiento o el efecto legal. Obtenga una revisión actual y específica de instrumento, operador, servicio e implementación.

Luxemburgo es el domicilio de fondos más grande de Europa y una jurisdicción líder para instrumentos de fondos tokenizados. La **Commission de Surveillance du Secteur Financier (CSSF)** regula el uso de la tecnología de contabilidad distribuida (DLT) para instrumentos financieros según la Circular CSSF 19/732 y directrices posteriores.

---

## Marco regulatorio aplicable { #applicable-regulatory-framework }

| Reglamento | Alcance |
|---|---|
| CSSF Circular 19/732 | Cálculo y administración de fondos de NAV basado en DLT |
| CSSF Circular 22/811 | Servicios de fondos basados en DLT e instrumentos tokenizados |
| Ley AML de 2004 (modificada) | Obligaciones de diligencia debida del cliente |
| Ley de 5 de abril de 1993 (sector financiero) | Autorización de empresas de inversión |
| MiCAR (UE) 2023/1114 | Proveedores de servicios de criptoactivos |
| DORA (UE) 2022/2554 | Resiliencia operativa de TIC |

---

## Diferencias clave con Alemania { #key-differences-from-germany }

| Dimensión | DE (eWpG) | LU (CSSF) |
|---|---|---|
| Registro autorizado | DB es canónico (§16 eWpG) | DB es canónico (guía CSSF) |
| Periodo de retención | 10 años | 5 años |
| Aplicabilidad de MiCAR | Exentos (tokens eWpG ≠ tokens de dinero electrónico) | Aplica a servicios de criptoactivos |
| Umbral UBO | 25% (GwG §3) | 25% (Ley AML Art. 1(7)) |
| DD reforzada | PEP (GwG §10(2)) | PEP + terceros países de alto riesgo |
| Registro de accionistas | No requerido | Requerido para SICAV y SICAF |
| Declaración de origen de los fondos | Opcional | Obligatorio para todos los clientes |

---

## Requisitos de documentos KYC para `LU_CSSF` { #kyc-document-requirements-for-lucssf }

Además de los documentos comunes (certificado de constitución, extracto de registro mercantil), el perfil de jurisdicción `LU_CSSF` requiere:

- **Extracto del Registre des Bénéficiaires Effectifs (RBE)** — Registro de beneficiarios reales de Luxemburgo
- **Registro de accionistas** — para sociedades de inversión (SICAV/SICAF/SIF)
- **Declaración de origen de los fondos** — firmada por el representante legal del cliente
- **Cuestionario AML específico de la CSSF**
- Informes anuales (últimos 2 años)

Consulte [KYC & AML](../compliance/kyc-aml.md) para conocer el ciclo de vida completo del documento.

---

## Detalles de los tokens de fondos { #fund-token-specifics }

Luxemburgo es el domicilio principal de los instrumentos de fondos tokenizados. Registerwerk admite los estándares de token preferidos por la CSSF para este caso de uso:

| Tipo de instrumento | Estándar de token | Soporte de Registerwerk |
|---|---|---|
| Fondo síncrono (NAV diario) | [ERC-4626](../token-standards/erc4626.md) | Completo — `AssetVaultState`, `VaultNavStrike` |
| Fondo asíncrono (T+1 / T+2) | [ERC-7540](../token-standards/erc7540.md) | Completo — `VaultRequest`, flujo de solicitud/reclamación |
| Bono con tramos | [ERC-3525](../token-standards/erc3525.md) | Completo — `AssetSlot` (tramo) |
| Acciones/bonos regulados | [ERC-3643](../token-standards/erc3643.md) | Completo — T-REX vinculado a identidad |

La entidad `AssetVaultState` registra el NAV por participación. `VaultNavStrike` registra cada punto de cálculo del NAV, lo que proporciona a los reguladores una pista de auditoría con marca de tiempo de todas las decisiones de fijación de precios.

---

## Momento de liquidación { #settlement-timing }

Las obligaciones de liquidación actuales requieren una revisión externa. El módulo `trading` puede registrar una marca de tiempo
`settledAt`, pero el prototipo [MiFIR](../compliance/mifir.md) no valida el estado de liquidación de
ni una ventana de liquidación regulatoria antes de seleccionar filas.

---

## Notificación de incidentes a la CSSF { #cssf-incident-reporting }

Conforme al Art. 19 de DORA (transpuesto en Luxemburgo mediante la ley de implementación de DORA), los incidentes graves de TIC deben notificarse a la CSSF:

- **Notificación inicial**: dentro de las 4 horas hábiles siguientes a la clasificación como grave
- **Informe intermedio**: dentro de las 72 horas
- **Informe final**: dentro del plazo de 1 mes

El `DoraService` almacena incidentes clasificados manualmente y marcas de tiempo de recordatorio de la aplicación.
No determina la clasificación ni los plazos legalmente correctos, ni enruta notificaciones a la CSSF.
Véase [DORA](../compliance/dora.md).

---

## Obligaciones de MiCAR (LU_CSSF) { #micar-obligations-lucssf }

La transposición de MiCAR por parte de Luxemburgo la hace aplicable a los proveedores de servicios de criptoactivos que operan desde Luxemburgo. Para las implementaciones de Registerwerk con `LU_CSSF` como jurisdicción principal:

- El operador debe contar con una licencia CASP de la CSSF (o una licencia pasaportable de otro Estado miembro de la UE)
- La [Travel Rule](../compliance/travel-rule.md) se aplica a todas las transferencias de criptoactivos ≥ 1.000 €
- El componente [DAC8/CARF](../compliance/dac8.md) produce una salida de prototipo `DRAFT_UNVALIDATED`;
  no presenta la declaración ante la ACD ni acredita la entrega o aceptación por parte de la autoridad
