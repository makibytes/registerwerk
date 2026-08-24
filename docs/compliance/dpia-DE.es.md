---
title: DPIA — Alemania
description: Borrador de evaluación de impacto relativa a la protección de datos para la jurisdicción DE_EWPG — requiere la aprobación del DPO y de asesoría legal antes de la puesta en producción.
---

# Datenschutz-Folgenabschätzung (DSGVO Art. 35)

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Esto es un borrador del repositorio, no un DPIA aprobado. El responsable del tratamiento de la
    implementación y el DPO deben establecer el alcance, la necesidad, la proporcionalidad, los
    riesgos, las mitigaciones, los requisitos de consulta, la titularidad, la aprobación y la
    evidencia de revisión antes de basarse en él.
# Evaluación de impacto relativa a la protección de datos — jurisdicción DE_EWPG

**Sistema:** Registerwerk  
**Jurisdicción:** DE — eWpG / BaFin / GwG  
**DPO:** [Por completar]  
**Fecha:** 2026-05-21  
**Estado:** DRAFT — requiere la aprobación del DPO y de asesoría legal antes de la puesta en producción

---

## 1. Necesidad y proporcionalidad

**Tratamiento:** Registerführung (gestión del registro) de valores electrónicos tokenizados conforme al eWpG.

**Necesidad:** Exigido legalmente. El eWpG §7 exige el registro central. El GwG §10 exige el KYC. El eWpG §15 exige una conservación de 10 años. El tratamiento no puede reducirse por debajo de estos mínimos legales.

**Proporcionalidad:** Los datos recopilados son el mínimo exigido por el eWpG y el GwG. Los datos personales de personas físicas se limitan a administradores y titulares reales (umbral del GwG §3 ≥25 %). Los datos personales del inversor solo se recopilan cuando el inversor es una persona física.

---

## 2. Evaluación de riesgos

| Riesgo | Probabilidad | Gravedad | Riesgo residual | Control |
|---|---|---|---|---|
| Divulgación no autorizada de datos KYC | Medio | Alto | Bajo | Acceso basado en roles; AES-256 en reposo; TLS 1.3; registro de auditoría |
| Manipulación del registro de auditoría | Bajo | Crítico | Bajo | Cadena hash SHA-256 + disparador WORM + ancla pública diaria |
| Compromiso de la clave del monedero | Bajo | Crítico | Bajo | Cifrado de sobre (envelope) con KMS; sin endpoint exportRaw; acceso registrado |
| Fallo en el filtrado de sanciones | Bajo | Alto | Bajo | Reevaluación diaria; lista dual (OpenSanctions + Refinitiv); aceptación por doble control |
| Violación de datos (hacker) | Bajo | Alto | Medio | Aislamiento de red; WAF (detección de bots de Kong + restricción de IP); prueba de penetración anual |
| Eliminación ilegal de inscripciones registrales | Muy bajo | Crítico | Bajo | Disparador WORM; registro de auditoría inmutable; separación de roles de base de datos |
| Transferencia transfronteriza sin garantías | Bajo | Medio | Bajo | AWS eu-central-1; cláusulas contractuales tipo |
| Retrasos en las solicitudes de acceso del interesado | Bajo | Bajo | Bajo | Endpoints DSAR en /api/v1/me/dsar/ |

**Nivel de riesgo general:** Medio — mitigado por los controles descritos en el ROPA.

---

## 3. Actividades de procesamiento de alto riesgo

| Actividad | Activador del art. 35 | Resultado del DPIA |
|---|---|---|
| Datos de titulares reales (PEP, estado de sanciones) | Categorías especiales potenciales (proxy de opinión política) | Justificado por el art. 6(1)(c), obligación legal; art. 9(2)(g), interés público sustancial |
| Registro de auditoría — no puede eliminarse | Se aplica la excepción del art. 17(3)(b) | Justificado: el eWpG §15(3) exige una conservación obligatoria de 10 años; documentado en el aviso de consentimiento |
| Identidad del inversor (personas físicas) | Tratamiento a gran escala | Minimizado: solo dirección de monedero + importe nominal, salvo que el inversor sea persona física |

---

## 4. Medidas para abordar los riesgos

1. **Minimización de datos:** Solo se recopilan los datos exigidos por el eWpG/GwG.
2. **Cifrado:** AES-256-GCM para documentos + sobre KMS para las claves de los monederos.
3. **Control de acceso:** Rol `COMPLIANCE_OFFICER` para KYC; `REGISTRY_ADMIN` con MFA para operaciones sensibles.
4. **Aplicación de la retención:** `KycMonitoringJob` impone la caducidad; borrado automatizado a solicitud del interesado en `POST /api/v1/me/dsar/erasure` (marcado como eliminado — tombstone — de los datos personales; se conserva el hash de auditoría).
5. **Respuesta a incidentes:** Clasificación de incidentes DORA en `ict_incident`; notificación de incumplimiento dentro de las 72 horas conforme al DSGVO art. 33.
6. **Derechos del interesado:** Endpoints DSAR implementados; SLA de respuesta de 30 días.
7. **Consulta al DPO:** Este DPIA requiere revisión del DPO antes de que comience el tratamiento.

---

## 5. Consulta con el DPO

**Nombre del DPO:** [Por completar]  
**Fecha de aprobación del DPO:** [Por completar]  
**Opinión del DPO:** [Por completar]

---

## 6. Aprobación

| Rol | Nombre | Fecha |
|---|---|---|
| DPO | | |
| Asesor Legal | | |
| CTO | | |
| Director General | | |

*Este DPIA debe revisarse anualmente y ante cualquier cambio significativo en las actividades de tratamiento.*
