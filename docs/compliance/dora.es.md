---
title: DORA — Gestión de riesgos de TIC
description: Prototipo ICT de registros de incidentes, pruebas de resiliencia y proveedores externos; no es una implementación completa de DORA.
---

# DORA — Ley de resiliencia operativa digital { #dora-digital-operational-resilience-act }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Esta página registra las asignaciones de control previstas y el comportamiento actual del repositorio. No es asesoramiento legal
    ni evidencia de que DORA se aplica a un operador en particular, de que existe un marco de control DORA completo, o de que un incidente ha sido clasificado o reportado válidamente. La aplicabilidad, la clasificación,
    los plazos, las autoridades competentes, los formularios, los canales y las pruebas requieren una revisión actual específica del operador, el servicio, el incidente, la jurisdicción y la implementación,
    por parte de un abogado calificado y de los propietarios responsables de resiliencia y cumplimiento.

El repositorio contiene un registro operativo manual para incidentes de TIC, pruebas de resiliencia y proveedores externos.
No es una implementación de informes a la autoridad.

## Alcance y aplicabilidad { #scope-and-applicability }

La aplicabilidad de DORA no se puede inferir del nombre del repositorio, un valor de jurisdicción `eWpG`, un estándar de token
o la presencia de un módulo `dora`. Las capacidades reguladas del operador y los servicios
realmente realizados deben clasificarse externamente antes de depender de cualquier mapeo de control.

Las declaraciones de la legislación vigente sobre los artículos DORA, las normas técnicas, los umbrales de clasificación y los plazos de presentación de informes
deben verificarse frente a las fuentes oficiales actuales como parte de esa revisión.

## Registro de incidentes actual { #current-incident-record }

Un operador autorizado puede crear manualmente un `IctIncident` a través de
`POST /api/v1/dora/incidents`. La entidad actual registra:

- categoría: `DATA_BREACH`, `SYSTEM_OUTAGE`, `RANSOMWARE`, `THIRD_PARTY_FAILURE` o `OTHER`;
- gravedad: `LOW`, `MEDIUM`, `HIGH` o `MAJOR`;
- estado: `DETECTED`, `INVESTIGATING`, `CONTAINED`, `RESOLVED`,
  `REPORTED_TO_AUTHORITY` o `CLOSED`;
- descripción, etiquetas de eventos de origen, marcas de tiempo, causa raíz, solución, asignación y una referencia de autoridad introducida por el operador;
- marcas de tiempo de recordatorio calculadas por la aplicación para incidentes registrados como `MAJOR`.

Estos valores son datos operativos introducidos por el operador. Un estado como `REPORTED_TO_AUTHORITY`, o un `authorityRef`, registra una afirmación del operador; la aplicación no verifica de forma independiente un acuse de recibo o una aceptación por parte de la autoridad.

## Monitoreo de fechas límite { #deadline-monitoring }

`DoraService` ejecuta un trabajo diario que consulta las fechas límite de las solicitudes vencidas y escribe mensajes de registro.
También expone indicadores para registros vencidos. El trabajo no envía una notificación, no genera un informe
con formato de autoridad, no demuestra que la fecha límite configurada sea legalmente correcta, ni notifica a todo el personal responsable.

El modelo actual no representa un flujo de trabajo completo de informes inicial/intermedio/final.
Los operadores no deben utilizar sus marcas de tiempo como plazos legales sin una revisión legal y regulatoria vigente.

## Detección automática de incidentes — no implementada { #automatic-incident-detection-not-implemented }

Los eventos de auditoría interna, deriva de cadena, indexador, RPC o filtrado no se clasifican automáticamente
y se convierten en registros `IctIncident`. `sourceEventType` y `sourceEventRef` son campos de correlación proporcionados manualmente,
no evidencia de una canalización de detección automatizada.

## Registros de terceros de TIC { #ict-third-party-records }

La entidad `ThirdPartyProvider` almacena campos operativos que incluyen nombre, categoría, criticidad,
LEI, país, fechas de contrato, notas de subcontratación, contacto, SLA, RTO/RPO y un indicador de notificación mantenido por el operador.
Los registros se enumeran a través de:

- `GET /api/v1/dora/providers`
- `GET /api/v1/dora/providers/expiring`

Esta tabla no es un registro de información DORA completo ni aprobado por la autoridad. No se implementa ninguna
exportación del Art. 28 lista para la autoridad y validada por esquema.

## Registros de pruebas de resiliencia { #resilience-test-records }

El módulo puede registrar y enumerar metadatos de pruebas de resiliencia y resaltar registros cuya próxima fecha de vencimiento configurada
ha pasado. No ejecuta una prueba de resiliencia, no valida su evidencia, no establece el alcance de
TLPT ni certifica el resultado.

## Enrutamiento y presentación ante la autoridad — no implementado { #authority-routing-and-filing-not-implemented }

El repositorio no implementa el enrutamiento de autoridad DORA específico de la jurisdicción, formularios o esquemas oficiales,
transmisión autenticada, recibos de entrega, correcciones, gestión de rechazos ni aceptación por parte de la autoridad. Registrar
que se reportó un incidente no constituye evidencia de presentación.
