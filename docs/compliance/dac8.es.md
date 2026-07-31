---
title: DAC8 / CARF
description: Prototipo de exportación de participaciones DRAFT_UNVALIDATED; no es una implementación de archivo DAC8, CARF o KStTG.
---

# Prototipo de exportación de participaciones con forma DAC8/CARF { #dac8-carf-shaped-holdings-export-prototype }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    El estado de la entidad que reporta, el alcance, los usuarios y criptoactivos reportables, las obligaciones de diligencia debida, los períodos, los plazos, la jurisdicción, la autoridad competente, las correcciones y la retención requieren una revisión
    actual y específica del operador, del cliente, de los activos, de la transacción, de la jurisdicción y de la implementación, por parte de un asesor fiscal/legal calificado y el propietario responsable del informe. Esta página no es asesoramiento legal o fiscal
    y no establece el cumplimiento de DAC8, CARF o KStTG alemán.

!!! danger "DRAFT_UNVALIDATED — NO PRESENTAR"
    El resultado actual es un prototipo de holding incompleto y construido a mano. No está validado
    contra un esquema oficial DAC8, CARF o KStTG y no debe usarse para la presentación legal.
    La generación, el almacenamiento de objetos, el hashing o el transporte SFTP no significa que se haya presentado un informe,
    reconocido, aceptado o completado legalmente.

## Comportamiento actual del repositorio { #current-repository-behavior }

`Dac8ExportService` se ejecuta el 31 de enero para un año anterior solicitado y se puede activar a pedido.
Para cada una de las cuatro etiquetas de jurisdicción configuradas:

1. consulta los saldos de los titulares almacenados actualmente para los activos emitidos;
2. cuenta las filas de transferencia de tokens entrantes para cada titular seleccionado;
3. crea un pequeño documento XML utilizando el año solicitado como metadatos del informe;
4. almacena los bytes generados y un hash; y
5. llama a la puerta de enlace genérica configurada.

La consulta no reconstruye una instantánea del 31 de diciembre ni los flujos anuales de adquisición/enajenación.
Todos los documentos generados y el registro de seguimiento asociado deben tratarse como
`DRAFT_UNVALIDATED`, independientemente de los nombres de estado de la base de datos heredada.

## Falta de diligencia debida y controles de población { #missing-due-diligence-and-population-controls }

El prototipo no implementa actualmente:

- una clasificación de entidad declarante/CASP o decisión de perímetro alemán KStTG;
- debida diligencia de usuario reportable y clasificación de persona controladora;
- residencia fiscal completa y TIN recopilación, validación, códigos de motivo o autocertificación;
- clasificación y exclusiones de criptoactivos reportables;
- adquisición bruta anual, enajenación, intercambio, transferencia o agregación de valor justo de mercado;
- una instantánea confiable del saldo de fin de año;
- selección de población específica de la jurisdicción o enrutamiento de jurisdicción asociada; o
- corrección, cancelación, informe nulo, duplicado y manejo de informes tardíos.

La misma población de prototipos se emite actualmente bajo múltiples etiquetas de jurisdicción. A
`crossBorderIndicator`, el tratamiento completo de socios CRS y los campos de entidad/usuario reportable
descritos anteriormente en esta documentación no están implementados.

## Datos objetivo: no implementados actualmente { #target-data-not-currently-implemented }

Identidad fiscal, residencia, persona controladora, clasificación de activos, tipo de transacción, valoración,
moneda, agregación anual y campos de fin de año son requisitos objetivo para el análisis externo;
su presencia en una tabla de diseño no debe describirse como un mapeo de fuente actual.

## XML y límite de transporte { #xml-and-transport-boundary }

El servicio emite XML construido a mano y no establece conformidad con un esquema o reglas comerciales oficiales OCDE, UE o
alemanas. No hay adaptadores de portal específicos de autoridad ni procesadores de recibos
autenticados.

La puerta de enlace genérica puede ser `NOOP` o SFTP. Una carga exitosa de SFTP solo demuestra que los bytes fueron
transportados a un servidor configurado. No acredita la entrega a autoridad fiscal, presentación legal, reconocimiento, validación o aceptación. Los estados heredados como `SUBMITTED`,
`PENDING_ACK`, `ACCEPTED` o `REJECTED` no se deben presentar como resultados de autoridad sin un recibo de autoridad
autenticado y analizado de forma independiente.

## Calendario y normativa vigente { #timing-and-current-law }

No se base en declaraciones históricas de que el primer año de informe fue 2025 o que los portales de los estados miembros
todavía se estaban implementando durante 2025. Los períodos aplicables, los requisitos alemanes de KStTG,
las fechas de presentación, los esquemas, los portales y las reglas de transición deben verificarse con las fuentes oficiales actuales
durante la revisión externa.

## Relación con MiFIR { #relationship-to-mifir }

Los informes de transacciones de MiFIR y los informes fiscales de DAC8/CARF/KStTG tienen diferentes perímetros legales,
poblaciones, datos, destinatarios, períodos y procesos de corrección. Compartir una tabla de persistencia o una interfaz de transporte
no demuestra la conformidad de ninguno de los prototipos con su régimen objetivo.

## Condición de lanzamiento { #release-condition }

El uso en producción permanece bloqueado hasta que se implementen y verifiquen de principio a fin el perímetro de informes,
el modelo de diligencia debida, los datos de origen completos y las instantáneas históricas, la validación del esquema oficial
y de las reglas comerciales, el canal certificado por la autoridad, el ciclo de vida del recibo autenticado, el modelo de
corrección, la titularidad operativa y la aprobación legal/fiscal.
