---
title: DPIA — Liechtenstein
description: Borrador de evaluación de impacto relativa a la protección de datos para la jurisdicción LI_TVTG — requiere la aprobación del DPO y de asesoría legal antes de la puesta en producción.
---

# Datenschutz-Folgenabschätzung (DSGVO Art. 35) — Liechtenstein / TVTG

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Esto es un borrador del repositorio, no un DPIA aprobado. El responsable del tratamiento de la
    implementación y el DPO deben establecer el alcance, la necesidad, la proporcionalidad, los
    riesgos, las mitigaciones, los requisitos de consulta, la titularidad, la aprobación y la
    evidencia de revisión antes de basarse en él.
# Evaluación de impacto relativa a la protección de datos — jurisdicción LI_TVTG

**Sistema:** Registerwerk  
**Jurisdicción:** LI — FMA / TVTG (Token- und VT-Dienstleister-Gesetz) / SPG (Sorgfaltspflichtgesetz)  
**DPO:** [Por completar]  
**Fecha:** 2026-05-21  
**Estado:** DRAFT — requiere la aprobación del DPO y de asesoría legal antes de la puesta en producción

---

## 1. Base jurídica

| Tratamiento | Base jurídica | Artículo DSGVO |
|---|---|---|
| KYC / diligencia debida | Obligación legal — SPG Art. 3-5; TVTG §29-31 | Art. 6(1)(c) |
| Registro de valores VT (TVTG) | Obligación legal — TVTG §3 (modelo de token-contenedor) | Art. 6(1)(c) |
| Filtrado de sanciones / PEP | Obligación legal — SPG Art. 6; EU 2023/1113 (adaptación TVTG) | Art. 6(1)(c) |
| Documento de información sobre el token | Obligación legal — TVTG §9 | Art. 6(1)(b) |

---

## 2. Evaluación de riesgos

| Riesgo | Probabilidad | Gravedad | Riesgo residual | Medida |
|---|---|---|---|---|
| Acceso no autorizado a los datos KYC | Baja | Alta | Bajo | RBAC; AES-256; TLS 1.3; registro de auditoría |
| Vulnerabilidad de seguridad del smart contract | Baja | Alta | Bajo | Auditoría de Trail of Bits / OpenZeppelin (obligación TVTG) |
| Falsificación del registro | Muy baja | Crítica | Bajo | Cadena de hash SHA-256; disparador WORM; ancla diaria |
| Transferencia transfronteriza de datos | Baja | Media | Bajo | AWS eu-central-1 (EEE); CCT (cláusulas contractuales tipo) |

---

## 3. Requisitos específicos de Liechtenstein

- **TVTG §9 — Documento de información sobre el token:** Campo obligatorio en el tipo de documento KYC `TOKEN_WHITEPAPER`; firmado digitalmente mediante PAdES.
- **Auditoría del smart contract:** La TVTG exige una auditoría de seguridad independiente. El tipo de documento `SMART_CONTRACT_AUDIT` está configurado como campo obligatorio en `JurisdictionRequirementConfig.buildLiTvtg()`.
- **Obligación de notificación a la FMA:** Los prestadores de servicios TT (TT-Dienstleister) conforme al TVTG §12 deben notificarse a la FMA. Registro en la tabla `third_party_provider` (V18).
- **Diligencia debida SPG:** Declaración de titular real (WB) (titulares reales ≥ 25 %) mediante la entidad `BeneficialOwner` (V12); conforme a la SPG.
- **Conservación:** 10 años (TVTG §33); 5 años para los documentos de prevención del blanqueo (SPG Art. 7).
- **Derechos de los interesados:** El DSGVO se aplica directamente en Liechtenstein (EEE). Acceso: `GET /api/v1/me/dsar/export`; supresión: `POST /api/v1/me/dsar/erasure`.

---

## 4. Protección de datos y Travel Rule (adaptación TVTG del TFR)

Liechtenstein ha adoptado, como miembro del EEE, el Reglamento de Transferencias de Fondos de la UE (TFR, Reglamento (UE) 2023/1113). Umbral: 1.000 EUR (adaptación TVTG). TravelRuleService está configurado para LI_TVTG.

---

## 5. Aprobación

| Rol | Nombre | Fecha |
|---|---|---|
| Delegado de Protección de Datos | | |
| Responsable de cumplimiento FMA | | |
| Director General | | |
