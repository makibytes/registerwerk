---
title: Alcance del cumplimiento normativo
---

Esta página define lo que Registerwerk implementa para soporte de cumplimiento y lo que queda con el operador regulado.

## Descargo de responsabilidad importante { #important-disclaimer }

Registerwerk es un software que permite el cumplimiento, no un motor de determinación legal. Las obligaciones regulatorias dependen de la jurisdicción, el alcance de la licencia y la interpretación de la supervisión.

## Perfiles de jurisdicción en el alcance { #jurisdiction-profiles-in-scope }

Registerwerk incluye identificadores de jurisdicción y perfiles configurables de requisitos KYC para:

- `DE_EWPG` (Alemania, BaFin, contexto eWpG)
- `LU_CSSF` (Luxemburgo, CSSF)
- `FR_AMF` (Francia, AMF)
- `LI_TVTG` (Liechtenstein, FMA, contexto TVTG)

Estos perfiles son controles operativos para los flujos de trabajo de recopilación y aprobación de documentos. No son asesoramiento legal y deben ser revisados por equipos legales/de cumplimiento antes de su uso en producción.

## Controles implementados por la plataforma { #controls-implemented-by-platform }

- Evaluación de lista de verificación de documentos KYC específica de cada jurisdicción.
- Estado de aprobación por jurisdicción con motivo de vencimiento y rechazo.
- Justificación obligatoria (`overrideNote`) para aprobaciones cuando falta evidencia requerida, está vencida o es demasiado antigua.
- Flujo de eventos de auditoría inmutable para envíos, aprobaciones, rechazos y anulaciones de KYC.
- API dedicada de informes de anulación (`/api/v1/audit/reports/kyc-overrides`) para comités de auditoría.
- Autorización de nivel API para acciones sensibles de KYC.
- Bloques de construcción de retención de datos en PostgreSQL/S3 con rutas de recuperación controladas.

## Controles fuera del alcance de la plataforma { #controls-outside-platform-scope }

Los operadores siguen siendo responsables de:

- Estado de licencia y registro ante las autoridades competentes.
- Metodología de riesgo AML/CFT y deberes de notificación de actividades sospechosas.
- Calidad, ajuste y política de escalamiento del proveedor de filtrado de sanciones.
- Estándares de verificación de la titularidad real y suficiencia de las pruebas.
- Obligaciones de divulgación y calificación legal de MiCAR/MiFID/eWpG.
- Gobernanza de la ley de privacidad (base legal, decisiones DPIA, mecanismos de transferencia, gobernanza DSAR).

## Referencias regulatorias utilizadas para la alineación de referencia { #regulatory-references-used-for-baseline-alignment }

- Alemania: estructura eWpG y deberes de registro.
- UE: principios marco MiCAR para servicios de criptoactivos.
- UE: principios RGPD para procesamiento legal, minimización, seguridad y responsabilidad.
- Línea base global AML: enfoque basado en el riesgo de las Recomendaciones del GAFI.

## Paquete de gobierno de operador sugerido { #suggested-operator-governance-pack }

Antes de entrar en funcionamiento, mantenga estos artefactos fuera del código fuente y revíselos periódicamente:

- Memo legal jurisdiccional para el alcance del producto y los límites de la licencia.
- Política KYC/AML con matriz de escalamiento y niveles de autoridad de aprobación.
- Procedimientos operativos de monitoreo de transacciones y sanciones.
- Registro de controles de protección de datos (retención, control de acceso, respuesta a incidentes).
- Proceso de gestión de cambios para actualizaciones de perfiles de jurisdicción y aprobación legal.
