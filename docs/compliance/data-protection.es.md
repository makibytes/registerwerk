---
title: Protección de Datos (DSGVO / GDPR)
description: Inventario de datos personales y flujos de trabajo parciales DSAR, con brechas de cobertura y cifrado actuales.
---

# Protección de Datos (DSGVO / GDPR) { #data-protection-dsgvo-gdpr }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Esta página registra las asignaciones de control de privacidad previstas y el comportamiento actual del repositorio. No es
    una evaluación de cumplimiento GDPR/DSGVO, ROPA, DPIA aprobada, una decisión de retención o una determinación de base legal.
    Las funciones del controlador/procesador, los propósitos, las bases legales, el inventario de datos, la retención,
    el manejo de derechos y las medidas de seguridad requieren una revisión específica de la implementación por parte del
    controlador, el DPO, los propietarios de seguridad y abogados calificados.

**Reglamento (UE) 2016/679** (GDPR o DSGVO en alemán) se aplica a todos los datos personales procesados por los operadores de Registerwerk. Como registro de valores que procesa nombres, fechas de nacimiento, identificaciones fiscales, números de pasaporte y datos financieros de personas físicas, Registerwerk es un controlador de datos (y a veces procesador) sujeto a todas las obligaciones de GDPR.

---

## Datos personales en Registerwerk { #personal-data-in-registerwerk }

La ubicación principal de los datos personales es la Entidad `NaturalPerson`. Esto incluye:

| Campo | Categoría GDPR | Propósito |
|---|---|---|
| `givenName`, `familyName` | Datos personales | Verificación de identidad KYC |
| `dateOfBirth` | Datos personales | Verificación de identidad KYC |
| `nationality`, `countryOfResidence` | Datos personales | Control de sanciones, presentación de informes |
| `taxId`, `taxIdCountry` | Datos personales sensibles | Informes DAC8/CARF |
| Campos `address` | Datos personales | Verificación KYC, correspondencia de documentos |
| `pepStatus` | Categoría especial (política) | Diligencia debida reforzada |
| Archivos de documentos (pasaportes, documentos de identidad) | Datos personales sensibles | Verificación de KYC: almacenada en S3 |

---

## Cifrado en reposo: no implementado para los campos `NaturalPerson` { #encryption-at-rest-not-implemented-for-naturalperson-fields }

`NaturalPerson` PII actualmente está asignado a columnas de base de datos ordinarias. El repositorio no implementa cifrado de columnas a nivel de aplicación, DEK por registro, ajuste KEK ni borrado criptográfico para estos campos. El cifrado del volumen de la base de datos y del almacén de objetos se puede configurar externamente, pero debe verificarse en cada implementación y no reemplaza los controles a nivel de aplicación cuando sean necesarios.

---

## Art. 30 — Registros de actividades de procesamiento (ROPA) { #art-30-records-of-processing-activities-ropa }

El repositorio contiene un borrador del documento ROPA y un inventario inicial de actividades de procesamiento. El repositorio no establece la integridad, las bases legales, los períodos de retención, la propiedad y la aprobación:

| Actividad | Base jurídica | Retención |
|---|---|---|
| Verificación de identidad KYC/KYB | Obligación legal (GwG, TVTG, AMF) | Por jurisdicción (5 a 10 años) |
| Control de sanciones | Obligación legal | Por jurisdicción |
| Inscripciones en el registro de valores | Obligación legal (eWpG, TVTG) | Por jurisdicción (5 a 10 años) |
| Informes de transacciones (MiFIR) | Obligación legal | Según las reglas de retención MiFIR |
| Declaración de impuestos DAC8 | Obligación legal | Según las normas del estado miembro |
| Comunicación de atención al cliente | Interés legítimo | 3 años después del último contacto |
| Registro de auditoría | Obligación legal | Por jurisdicción |

El borrador se almacena en `docs/compliance/ropa.md`. Una implementación debe asignar un propietario, completarlo y aprobarlo, registrar evidencia de revisión y establecer una cadencia de revisión.

---

## Art. 35 — Evaluación de impacto de la protección de datos (DPIA) { #art-35-data-protection-impact-assessment-dpia }

El repositorio contiene borradores DPIA por jurisdicción. Si se requiere un DPIA, y si un borrador está completo y aprobado, debe determinarse para la implementación:

- `docs/compliance/dpia-DE.md`: implementación de eWpG en Alemania
- `docs/compliance/dpia-LU.md`: implementación de CSSF en Luxemburgo
- `docs/compliance/dpia-FR.md`: implementación de AMF en Francia
- `docs/compliance/dpia-LI.md`: implementación de TVTG en Liechtenstein

Estos archivos son entradas de revisión, no evidencia de un DPIA aprobado.

---

## Art. 17 — Derecho de supresión ("derecho al olvido") { #art-17-right-to-erasure-right-to-be-forgotten }

GDPR Art. 17 otorga a los interesados el derecho a solicitar la eliminación de sus datos personales. Sin embargo, el art. 17(3)(b) establece una exención para los datos retenidos para cumplir con una obligación legal. Para Registerwerk:

- Las inscripciones del registro de valores **no se pueden eliminar** durante el período de retención (eWpG §15, TVTG Art. 10) — se aplica la exención de obligación legal
- Los documentos KYC deben conservarse durante la relación comercial más el período de retención
- El servicio de borrado actual marca como eliminados (tombstone) los campos de contacto/autenticación seleccionados de `AppUser` tras la revisión del operador; no borra todos los datos personales asociados con una entidad

Comportamiento actual:
1. Una solicitud de borrado crea un elemento de trabajo del operador.
2. La finalización reemplaza los valores de nombre/correo electrónico de `AppUser` seleccionados, borra el hash de la contraseña y deshabilita el usuario.
3. La cobertura de `NaturalPerson`, documentos KYC, tenencias, transacciones y otros datos vinculados está incompleta; no se destruye ningún DEK porque no se implementa el cifrado DEK por registro.
4. Se emiten eventos de solicitud/resolución, pero esto por sí solo no prueba el borrado completo o el manejo legal de la solicitud.

---

## Puntos finales de derechos del interesado { #data-subject-rights-endpoints }

| Derecho | Punto final |
|---|---|
| Art. 15/20 — Acceso/portabilidad | `GET /api/v1/me/dsar/export`: exportación parcial de entidad jurídica/estado KYC; no es una exportación completa de datos personales |
| Art. 16 — Rectificación | Aquí no se documenta ningún flujo de trabajo de rectificación completo de DSAR |
| Art. 17 — Borrado | `POST /api/v1/me/dsar/erasure`: registra una solicitud para revisión por parte del operador; las solicitudes completadas actualmente solo marcan como eliminados (tombstone) los campos `AppUser` seleccionados |

Los flujos de solicitud y resolución emiten eventos de auditoría. Queda por verificar la cobertura de extremo a extremo del DSAR y la integridad de la auditoría.

---

## Art. 32 — Seguridad del tratamiento { #art-32-security-of-processing }

Medidas técnicas implementadas:

| Medida | Implementación |
|---|---|
| Cifrado en tránsito | TLS 1.3 en todos los puntos finales (Kong + backend) |
| Cifrado en reposo | El cifrado del campo `NaturalPerson` no está implementado; el cifrado de base de datos/almacén de objetos a nivel de implementación se debe configurar y verificar por separado |
| Control de acceso | Basado en roles (`@PreAuthorize`) + autenticación reforzada (step-up) para lecturas confidenciales |
| Registro de auditoría | Cadena hash a prueba de manipulaciones para todas las operaciones |
| MFA | WebAuthn / TOTP para todas las cuentas de operador |
| Seudonimización | `NaturalPerson.id` (UUID) utilizado en referencias entre módulos en lugar del nombre |
| Respuesta a incidentes | Existen registros manuales de incidentes y monitoreo de plazos; no se implementa la automatización de notificaciones a autoridades/interesados |

---

## Art. 33/34 — Notificación de violación { #art-3334-breach-notification }

Si se produce una violación de datos personales:

- Art. 33: Notificar a la **autoridad supervisora** dentro de las 72 horas siguientes a su conocimiento
- Art. 34: Notificar a los **interesados afectados** sin demora indebida si la violación es de alto riesgo

No se implementa ninguna autoridad automática GDPR ni un flujo de trabajo de notificación de incumplimiento del sujeto de datos. Los operadores deben establecer, probar y evidenciar un proceso específico de implementación.
