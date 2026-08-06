---
title: DPIA — Francia
description: Borrador de evaluación de impacto relativa a la protección de datos para la jurisdicción FR_AMF — requiere la aprobación del DPO y de asesoría legal antes de la puesta en producción.
---

# Analyse d'Impact relative à la Protection des Données (RGPD Art. 35)

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Esto es un borrador del repositorio, no un DPIA aprobado. El responsable del tratamiento de la
    implementación y el DPO deben establecer el alcance, la necesidad, la proporcionalidad, los
    riesgos, las mitigaciones, los requisitos de consulta, la titularidad, la aprobación y la
    evidencia de revisión antes de basarse en él.
# Evaluación de impacto relativa a la protección de datos — jurisdicción FR_AMF

**Sistema:** Registerwerk  
**Jurisdicción:** FR — AMF / ACPR / Code monétaire et financier / Loi PACTE  
**DPO:** [Por completar]  
**Fecha:** 2026-05-21  
**Estado:** DRAFT — requiere la aprobación del DPO y de asesoría legal antes de la puesta en producción

---

## 1. Marco jurídico

| Tratamiento | Base jurídica | Artículo RGPD |
|---|---|---|
| KYC / LCB-FT | Obligación legal — CMF Art. L561-5, Loi PACTE | Art. 6(1)(c) |
| Registro de valores tokenizados | Obligación legal — AMF DOC-2022-15 | Art. 6(1)(c) |
| Filtrado de sanciones / PEP | Obligación legal — R. 2016/847, EU 2023/1113 | Art. 6(1)(c) |
| Informes MiFIR | Obligación legal — UE 600/2014 Art. 26 | Art. 6(1)(c) |
| Declaración de titulares reales | Obligación legal — Loi PACTE Art. 52 | Art. 6(1)(c) |

---

## 2. Evaluación de riesgos

| Riesgo | Probabilidad | Gravedad | Riesgo residual | Medida |
|---|---|---|---|---|
| Acceso no autorizado a los datos KYC | Baja | Alta | Bajo | RBAC; AES-256; TLS 1.3; registro de auditoría |
| Incumplimiento de TRACFIN (declaración de sospecha) | Baja | Alta | Bajo | Flujo TRACFIN a través de AMF/ACPR; rol COMPLIANCE_OFFICER |
| Vulneración del registro (falsificación) | Muy baja | Crítica | Bajo | Cadena de hash SHA-256; disparador WORM |
| Transferencia fuera del EEE | Baja | Media | Bajo | AWS eu-central-1; CCT (cláusulas contractuales tipo) |

---

## 3. Requisitos específicos de Francia

- **Extracto Kbis ≤ 3 meses:** Recogido mediante el tipo de documento `COMMERCIAL_REGISTER_EXTRACT`; la antigüedad se verifica en `DocumentRequirement.maxAge`.
- **Declaración de titulares reales:** Modelo `BeneficialOwner` (V12) conforme a la Loi PACTE, umbral del 25 %.
- **TRACFIN:** Declaración de sospecha (SAR) mediante `POST /api/v1/admin/ict-incidents` (DORA) con category=AML_SAR. El documento se envía manualmente al portal de TRACFIN (ACPR).
- **Conservación:** 5 años (LCB-FT); 10 años para el registro (equivalencia con el eWpG).
- **Derechos de las personas:** CNIL — acceso mediante `GET /api/v1/me/dsar/export`; supresión mediante `POST /api/v1/me/dsar/erasure`.

---

## 4. Consulta a la CNIL

La CNIL recomienda la consulta a la autoridad competente para los tratamientos de datos a gran escala relativos a los valores negociables tokenizados. Este AIPD deberá someterse a la CNIL antes de la puesta en producción.

---

## 5. Validación

| Rol | Nombre | Fecha |
|---|---|---|
| DPO | | |
| Responsable de cumplimiento AMF | | |
| Director General | | |
