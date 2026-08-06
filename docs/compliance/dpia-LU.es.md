---
title: DPIA — Luxemburgo
description: Borrador de evaluación de impacto relativa a la protección de datos para la jurisdicción LU_CSSF — requiere la aprobación del DPO y de asesoría legal antes de la puesta en producción.
---

# Evaluación de impacto relativa a la protección de datos — jurisdicción LU_CSSF

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Esto es un borrador del repositorio, no un DPIA aprobado. El responsable del tratamiento de la
    implementación y el DPO deben establecer el alcance, la necesidad, la proporcionalidad, los
    riesgos, las mitigaciones, los requisitos de consulta, la titularidad, la aprobación y la
    evidencia de revisión antes de basarse en él.
# Évaluation d'Impact sur la Protection des Données (RGPD Art. 35)

**Sistema:** Registerwerk  
**Jurisdicción:** LU — CSSF / Loi du 5 août 2005 / AML Law 2004  
**DPO:** [Por completar]  
**Fecha:** 2026-05-21  
**Estado:** DRAFT — requiere la aprobación del DPO y de asesoría legal antes de la puesta en producción

---

## 1. Base jurídica para el tratamiento de alto riesgo

| Tratamiento | Base jurídica | Artículo RGPD |
|---|---|---|
| KYC / diligencia debida del cliente | Obligación legal — Ley AML de 2004 Art. 3, Circular CSSF 19/732 | Art. 6(1)(c) |
| Mantenimiento del registro de valores | Obligación legal — Circular CSSF 22/811 (instrumentos basados en DLT) | Art. 6(1)(c) |
| Filtrado de sanciones / PEP | Obligación legal — Ley AML de 2004 Art. 3(4), Reglamento UE 2580/2001 | Art. 6(1)(c) |
| Informes de transacciones MiFIR | Obligación legal — Reglamento UE 600/2014 (MiFIR) Art. 26 | Art. 6(1)(c) |
| Informes CASP de MiCAR | Obligación legal — Reglamento UE 2023/1114 Art. 60 | Art. 6(1)(c) |

---

## 2. Evaluación de riesgos

| Riesgo | Probabilidad | Gravedad | Riesgo residual | Control |
|---|---|---|---|---|
| Divulgación de datos personales de KYC a partes no autorizadas | Baja | Alta | Bajo | RBAC; AES-256; TLS 1.3; pista de auditoría |
| Vulneración de datos del RBE (Registre des Bénéficiaires Effectifs) | Baja | Alta | Bajo | Rol COMPLIANCE_OFFICER restringido; doble control |
| Transferencia de datos fuera del EEE | Baja | Media | Bajo | AWS eu-central-1; CCT |
| Fallo en la lista de sanciones | Baja | Alta | Bajo | Actualización diaria de OpenSanctions; aceptación por doble control |
| Manipulación del registro | Muy baja | Crítica | Bajo | Cadena de hash SHA-256; disparador WORM; ancla diaria |
| Retrasos en las solicitudes de acceso del interesado (SLA de 30 días) | Baja | Baja | Bajo | Endpoints DSAR implementados |

**Riesgo general:** Medio — mitigado por medidas técnicas y organizativas.

---

## 3. Requisitos específicos de Luxemburgo

- **Registre des Bénéficiaires Effectifs (RBE):** Extracto del titular real (UBO) almacenado y actualizado conforme a la Ley AML de 2004, Art. 3.
- **Circular CSSF 19/732:** Cuestionario AML recopilado por emisor; almacenado como tipo de documento KYC `AML_QUESTIONNAIRE`.
- **Circular CSSF 22/811:** El repositorio contiene componentes de registro orientados a DLT, pero no existe una determinación del registrador específica del instrumento ni evidencia de notificación a la CSSF. Ambos son bloqueantes para la puesta en producción.
- **Conservación:** 5 años tras el fin de la relación conforme a la Ley AML de 2004, Art. 4 (KYC); 10 años para el registro (política de equivalencia eWpG/CSSF).
- **Derechos del interesado:** El RGPD se aplica directamente en Luxemburgo. Endpoint DSAR: `GET /api/v1/me/dsar/export`; supresión: `POST /api/v1/me/dsar/erasure`.

---

## 4. Consideraciones transfronterizas

Las entidades luxemburguesas pueden mantener valores emitidos bajo las jurisdicciones DE_EWPG o FR_AMF. Los flujos de datos transfronterizos entre jurisdicciones de operadores utilizan:
- TLS 1.3 en tránsito
- AWS eu-central-1 (EEE) para el almacenamiento
- Cláusulas contractuales tipo (CCT) para cualquier subencargado ajeno al EEE

---

## 5. Aprobación

| Rol | Nombre | Fecha |
|---|---|---|
| DPO | | |
| Responsable de cumplimiento CSSF | | |
| Director General | | |
