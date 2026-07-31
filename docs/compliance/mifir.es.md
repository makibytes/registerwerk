---
title: Informes de transacciones MiFIR
description: Prototipo de exportación de transacciones DRAFT_UNVALIDATED en forma de MiFIR; no es una implementación de archivo RTS 22.
---

# Prototipo de exportación de transacciones en forma de MiFIR { #mifir-shaped-transaction-export-prototype }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    La clasificación de los instrumentos, el estado de la entidad informante, la reportabilidad, las exenciones, los plazos, la autoridad competente, la ruta de presentación, los deberes de corrección y la retención requieren una revisión actual
    específica del operador, instrumento, transacción, lugar, jurisdicción y despliegue por parte de un asesor calificado y el propietario responsable del informe. Esta página no es asesoramiento legal ni evidencia
    del cumplimiento de MiFIR.

!!! danger "DRAFT_UNVALIDATED — NO PRESENTAR"
    El resultado actual es un prototipo incompleto construido a mano. No está validado con un esquema oficial RTS 22
    y no debe usarse para la presentación legal. La generación, el almacenamiento de objetos, el hash
    o el transporte SFTP no significan que un informe haya sido presentado, reconocido, aceptado o que esté legalmente completo.

## Comportamiento actual del repositorio { #current-repository-behavior }

`MifirReportingService` se ejecuta según una programación y se puede activar a través de
`POST /api/v1/regulatory-reporting/mifir/generate`. Para cada una de sus etiquetas de autoridad configuradas:

1. selecciona filas de ejecución comercial creadas durante la fecha solicitada para activos emitidos en el subconjunto de jurisdicción DE/FR codificado
;
2. crea un pequeño documento XML que contiene un conjunto limitado de identificadores, cantidad, precio y valores de marca de tiempo
;
3. almacena los bytes generados y un hash; y
4. llama a la puerta de enlace genérica configurada.

Todos los documentos generados y el registro de seguimiento asociado deben tratarse como
`DRAFT_UNVALIDATED`, independientemente de los nombres de estado de la base de datos heredada.

## Faltan controles de población { #missing-population-controls }

El prototipo actualmente no aplica:

- una clasificación MiFID II/MiFIR a nivel de instrumento ni una decisión de reportabilidad;
- la capacidad de la entidad informante o del centro de negociación;
- el estado de liquidación;
- las exenciones de transacción;
- la identificación del comprador/vendedor y del responsable de la decisión exigida por el régimen objetivo;
- la deduplicación, corrección, cancelación o gestión de informes tardíos de informes previos; ni
- el enrutamiento completo por jurisdicción y autoridad competente.

La selección utiliza `TradeExecution.created_at`; no es una fecha de liquidación ni una población de ejecución confirmada de forma independiente
.

## Campos de destino: no implementados actualmente { #target-fields-not-currently-implemented }

Campos como los identificadores de comprador y vendedor, la identidad de la empresa informante, el MIC del centro
de negociación, la capacidad, los datos del responsable de la decisión, los indicadores de venta en corto, los campos de
exención/materias primas y otros contenidos de RTS 22 siguen siendo requisitos objetivo. Su mención en un documento
de diseño no debe interpretarse como un mapeo de fuente actual.

## XML y límite de transporte { #xml-and-transport-boundary }

No hay implementaciones de `MifirFilingStrategy` por jurisdicción ni adaptadores de presentación certificados
por la autoridad. El servicio emite XML construido a mano; no acredita la conformidad con el esquema, las reglas
de negocio, los datos de referencia ni la firma.

La puerta de enlace genérica puede ser `NOOP` o SFTP. Una carga exitosa de SFTP solo demuestra que los bytes fueron
transportados a un servidor configurado. No acredita la entrega ante autoridad competente,
presentación legal, reconocimiento, validación o aceptación. Los estados heredados como
`SUBMITTED`, `PENDING_ACK`, `ACCEPTED` o `REJECTED` no deben presentarse como resultados de autoridad.
sin un recibo de autoridad analizado y autenticado de forma independiente.

El reintento automático de tres intentos, la ingesta de recibos específica de la autoridad, la corrección de rechazos y la
notificación al regulador no están implementados.

## Condición de lanzamiento { #release-condition }

El uso en producción permanece bloqueado hasta que se implementen y verifiquen de extremo a extremo el perímetro de
informes, los datos de origen completos, la validación del esquema oficial y de las reglas de negocio, el canal
certificado por la autoridad, el ciclo de vida del recibo autenticado, el modelo de deduplicación/corrección, la
titularidad operativa y la aprobación legal.
