---
title: Registro de actividades de tratamiento
description: Borrador de registro de actividades de tratamiento conforme al Art. 30 del RGPD.
---

# Verzeichnis von Verarbeitungstätigkeiten (DSGVO Art. 30) { #verzeichnis-von-verarbeitungstätigkeiten-dsgvo-art-30 }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Este documento de repositorio es un borrador de inventario, no un registro aprobado o completo del Artículo 30.
    El controlador/procesador de la implementación debe establecer el alcance, los propósitos, las bases legales, los destinatarios, las transferencias,
    la retención, las medidas de seguridad, la propiedad, la aprobación y la evidencia de revisión.

# Registros de actividades de procesamiento (GDPR Art. 30) { #records-of-processing-activities-gdpr-art-30 }

**Controlador:** [Nombre del operador para completar]
**DPO:** [Contacto para completar]
**Última actualización:** 2026-05-21
**Versión:** 1.0

---

## 1. Incorporación de clientes y KYC { #1-customer-onboarding-kyc }

| Campo | Valor |
|---|---|
| **Propósito** | Verificación de identidad del cliente e incorporación para la emisión electrónica de valores (GwG §10, eWpG §3) |
| **Base jurídica** | Obligación legal (DSGVO Art. 6(1)(c)) — GwG §10, eWpG |
| **Categorías de datos** | Razón social, LEI, número de registro, fecha de constitución, documentos KYC (extracto de registro, declaración UBO, documentos de identidad, resoluciones de directorio), estado KYC |
| **Personas físicas** | Directores, UBO: nombre, fecha de nacimiento, nacionalidad, dirección, tipo/número de documento de identidad, PEP/estado de sanciones |
| **Destinatarios** | BaFin (DE), CSSF (LU), AMF (FR), FMA (LI): solo bajo petición regulatoria |
| **Transferencias a terceros países** | Ninguno planeado; AWS S3 (eu-central-1) para almacenamiento de documentos — Cláusulas contractuales tipo |
| **Retención** | 10 años después de la finalización de la relación (eWpG §15(3)); 5 años para registros KYC (GwG §8) |
| **Medidas de seguridad** | AES-256-GCM en reposo; TLS 1.3 en tránsito; acceso basado en roles (COMPLIANCE_OFFICER, REGISTRY_ADMIN); registro de auditoría |

## 2. Registro Electrónico de Valores { #2-electronic-securities-registry }

| Campo | Valor |
|---|---|
| **Propósito** | Mantenimiento del registro electrónico de valores según eWpG (Registerführung) |
| **Base jurídica** | Obligación legal (Art. 6(1)(c)) — eWpG §7, §15, §16, §17 |
| **Categorías de datos** | Titular del activo: dirección de la billetera, monto nominal, fecha de adquisición, estado de la lista blanca; historial de transacciones |
| **Personas físicas** | Identidad del titular para personas físicas: nombre, fecha de nacimiento, nacionalidad, identificación fiscal (vía HolderIdentity) |
| **Destinatarios** | BaFin (divulgaciones ordenadas por un tribunal); emisor (según eWpG §15) |
| **Retención** | 10 años después del reembolso/cancelación (eWpG §15(3)) |
| **Medidas de seguridad** | Registro de auditoría inmutable encadenado mediante hash; disparador WORM; ancla diaria; detección de deriva de cadena |

## 3. Sanciones y detección PEP { #3-sanctions-pep-screening }

| Campo | Valor |
|---|---|
| **Propósito** | Detección en curso de AML/CTF según GwG §10 Abs. 1 n.º 5 |
| **Base jurídica** | Obligación legal (Art. 6(1)(c)) — GwG §10, MiCAR Art. 60 |
| **Categorías de datos** | Nombre de la entidad, LEI, número de registro: comparado con OFAC SDN, EU CFSP, UN 1267, UK HMT, CH-SECO |
| **Procesadores** | OpenSanctions (datos abiertos, GDPR-neutral); Refinitiv World-Check (se requiere DPA) |
| **Retención** | 5 años (GwG §8) |
| **Medidas de seguridad** | Los resultados del filtrado se almacenan en una base de datos cifrada; doble control para aceptar un hit |

## 4. Comercio y procesamiento de transacciones { #4-trading-transaction-processing }

| Campo | Valor |
|---|---|
| **Propósito** | Ejecución de operaciones con valores en centros de negociación (Assetera, Archax, Talos, simulado) |
| **Base jurídica** | Necesidad contractual (Art. 6(1)(b)); obligación legal para la presentación de informes MiFIR (Art. 6(1)(c)) |
| **Categorías de datos** | ID de comerciante, ID de entidad, listados comerciales, registros de ejecución, direcciones de billetera |
| **Destinatarios** | BaFin/AMF — MiFIR RTS 22 informes de transacciones |
| **Retención** | 7 años (MiFIR Art. 25(1)); 5 años (GwG) |
| **Medidas de seguridad** | Acceso basado en roles (TRADER); registro de auditoría por operación |

## 5. Registro de auditoría { #5-audit-logging }

| Campo | Valor |
|---|---|
| **Propósito** | Pista de auditoría de seguridad y cumplimiento; Requisito de integridad eWpRV §6 |
| **Base jurídica** | Obligación legal (Art. 6(1)(c)) — eWpG §15, eWpRV §6, DORA art. 9 |
| **Categorías de datos** | ID de actor, rol de actor, tipo de evento, ID/tipo de sujeto, carga útil (puede incluir nombres de entidades) |
| **Retención** | 10 años (eWpG §15(3)); solo se puede añadir, no se puede eliminar |
| **Medidas de seguridad** | Cadena de hash SHA-256; disparador WORM en la base de datos; anclaje diario a blockchain pública; rol de base de datos restringido |

## 6. Gestión de usuarios del operador { #6-operator-user-management }

| Campo | Valor |
|---|---|
| **Propósito** | Autenticación y autorización del personal de registro |
| **Base jurídica** | Interés legítimo (Art. 6(1)(f)) — Seguridad informática, control de acceso |
| **Categorías de datos** | Correo electrónico, contraseña hash, roles, último inicio de sesión, tokens de acción |
| **Retención** | Duración del empleo + 2 años |
| **Medidas de seguridad** | Hashing de contraseñas de BCrypt; JWT (de corta duración, 8 h); MFA para operaciones sensibles |

## 7. Informes regulatorios (MiFIR, DAC8, Steuerbescheinigung) { #7-regulatory-reporting-mifir-dac8-steuerbescheinigung }

| Campo | Valor |
|---|---|
| **Propósito** | Notificación obligatoria de transacciones a las autoridades competentes |
| **Base jurídica** | Obligación legal (Art. 6(1)(c)) — MiFIR art. 26, DAC8, EStG §43 |
| **Categorías de datos** | Nombre del inversor, identificación fiscal, participaciones, transacciones, IBAN (para Steuerbescheinigung) |
| **Destinatarios** | BaFin (DE), AMF (FR), CSSF (LU), FMA (LI), BZSt (DAC8/CARF), DGFiP (FR), ACD (LU) |
| **Retención** | 7 años (MiFIR); 10 años (eWpG) |
| **Medidas de seguridad** | PDF firmados PAdES-B-LT; SFTP a portales de autoridad; recibos de envío |

---

## Derechos del interesado { #data-subject-rights }

| Derecho | Implementación |
|---|---|
| Art. 15 Acceso | `GET /api/v1/me/dsar/export` |
| Art. 17 Borrado | `POST /api/v1/me/dsar/erasure` — PII marcada como eliminada (tombstone); cadena de hash de auditoría preservada (obligación legal del art. 17(3)(b)) |
| Art. 20 Portabilidad | `GET /api/v1/me/dsar/export` devuelve JSON |
| Art. 21 Oposición | No aplicable (base de obligación legal) |
| Art. 22 Decisión automatizada | Sin decisiones automatizadas; todas las aprobaciones KYC son revisadas por humanos |
