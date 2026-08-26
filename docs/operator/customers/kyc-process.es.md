---
title: Proceso KYC
---

Esta página describe el flujo de trabajo del operador para registros KYC y decisiones de listas de verificación que tienen en cuenta la jurisdicción. Estos controles aún no se aplican de manera uniforme en todas las rutas de emisión, implementación, recepción o transferencia.

## Alcance y modelo { #scope-and-model }

Registerwerk almacena:

- Estado KYC a nivel de entidad (`legal_entity.kyc_status`) para el ciclo de vida de incorporación genérico.
- Aprobaciones KYC a nivel de jurisdicción (`kyc_jurisdiction_approval`) para la elegibilidad de emisión bajo regímenes específicos.
- Documentos etiquetados por jurisdicción (`kyc_document.jurisdiction`) donde `null` significa evidencia universal que puede satisfacer múltiples jurisdicciones.

## Gestión de documentos { #document-handling }

- Los archivos de menos de 5 MB se almacenan en PostgreSQL.
- Los archivos de 5 MB en adelante se almacenan en almacenamiento de objetos compatible con S3.
- Las acciones de acceso y de cambio de estado se escriben en la pista de auditoría.

## Carga de evidencia KYC { #uploading-kyc-evidence }

```bash
curl -X POST http://localhost:48000/api/v1/entities/{entityId}/kyc/documents \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -F "file=@certificate.pdf" \
   -F "documentType=INCORPORATION_CERTIFICATE" \
   -F "jurisdiction=DE_EWPG"
```

Si se omite `jurisdiction`, el documento se trata como universal.

## Lista de verificación de cumplimiento antes de la aprobación { #compliance-checklist-before-approval }

Antes de la aprobación de la jurisdicción, el backend calcula el cumplimiento con el perfil configurado para esa jurisdicción.

```bash
GET /api/v1/entities/{entityId}/kyc/compliance/{jurisdiction}
```

La respuesta incluye:

- `missingCount`
- `expiredCount`
- `tooOldCount`
- `fullyCompliant` (un nombre de nivel de código que significa que no hay lagunas configuradas en la lista de verificación, no cumplimiento legal)

## Aprobación de KYC por jurisdicción { #approving-jurisdiction-kyc }

Modelo de autorización:

- `ROLE_COMPLIANCE_OFFICER`: puede aprobar solo si `fullyCompliant=true` para la lista de verificación configurada.
- `ROLE_REGISTRY_ADMIN`: puede aprobar casos con o sin lagunas en la lista de verificación configurada.
- La aprobación con lagunas en la lista de verificación requiere `overrideNote` y se rechaza para usuarios que no son administradores.

Aprobación sin lagunas en la lista de verificación configurada:

```bash
curl -X POST http://localhost:48000/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/approve \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -H "Content-Type: application/json" \
   -d '{"expiresAt":"2027-01-31"}'
```

Si existen lagunas en la lista de verificación, la aprobación queda bloqueada a menos que se proporcione una nota de anulación explícita:

```bash
curl -X POST http://localhost:48000/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/approve \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -H "Content-Type: application/json" \
   -d '{"overrideNote":"Approved by compliance officer after manual source-of-funds review."}'
```

Cuando se utiliza la anulación, la nota se almacena en `kyc_jurisdiction_approval.override_note` y los contadores de cumplimiento se incluyen en el evento de auditoría.

## Flujo de trabajo de rechazo { #rejection-workflow }

```bash
curl -X POST http://localhost:48000/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/reject \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -H "Content-Type: application/json" \
   -d '{"reason":"Missing certified beneficial ownership register extract."}'
```

## Atestaciones en cadena y controles de tokens { #on-chain-claims-and-token-controls }

La aprobación de la jurisdicción está disponible para los flujos de trabajo del backend, pero todavía no se aplica de manera uniforme una puerta central de operación con denegación por defecto (fail-closed). Los contratos ERC-3643 y las atestaciones de ONCHAINID aportan, donde estén configurados, restricciones independientes a nivel de contrato; no demuestran el cumplimiento completo de KYC ni la elegibilidad legal.

## Nota reglamentaria { #regulatory-note }

Este conjunto de funciones respalda la evidencia de los controles KYC y su auditabilidad. Por sí solo no exime del cumplimiento de las obligaciones de licencia, notificación de actividades sospechosas, Travel Rule ni supervisión local.

## Informes de anulación { #override-reporting }

Los auditores y administradores pueden enumerar las aprobaciones de anulación por jurisdicción y período:

```bash
GET /api/v1/audit/reports/kyc-overrides?jurisdiction=DE_EWPG&from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z
```
