---
title: Referencia de la API
---

# Referencia de la API { #api-reference }

El Registro eWpG proporciona una API REST para todas las operaciones de registro. Esta página proporciona una descripción general de la estructura de la API, la autenticación y enlaces a la documentación interactiva en vivo.

## Documentación interactiva { #interactive-documentation }

La interfaz de usuario de Swagger está disponible en:

```
http://localhost:48080/swagger-ui.html
```

Para producción:

```
https://api.registerwerk.example.com/swagger-ui.html
```

La especificación completa de OpenAPI 3 (JSON) está disponible en:

```
http://localhost:48080/v3/api-docs
```

## Autenticación { #authentication }

Todos los puntos finales de la API (excepto `/api/v1/public/**`) requieren un token de portador JWT:

```bash
curl https://api.registerwerk.example.com/api/v1/issuances \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIs..."
```

Consulte [Autenticación](../customer/authentication.md) para saber cómo obtener un token.

## Grupos de la API { #api-groups }

### Puntos finales públicos (`/api/v1/public/`) { #public-endpoints-apiv1public }

No se requiere autenticación.

| Método | Ruta | Descripción |
|--------|------|-------------|
| `GET` | `/api/v1/public/chains` | Listar todas las cadenas habilitadas |
| `GET` | `/api/v1/public/health` | Comprobación de estado básica |

### Puntos finales del cliente (`/api/v1/`) { #customer-endpoints-apiv1 }

Requiere autenticación. Las respuestas tienen como ámbito la entidad autenticada.

| Método | Ruta | Descripción |
|--------|------|-------------|
| `GET` | `/api/v1/issuances` | Listar emisiones para su entidad |
| `POST` | `/api/v1/issuances` | Crear una nueva emisión |
| `GET` | `/api/v1/issuances/{id}` | Obtener detalles de emisión |
| `PUT` | `/api/v1/issuances/{id}` | Actualizar emisión (solo DRAFT) |
| `POST` | `/api/v1/issuances/{id}/submit` | Enviar para aprobación |
| `POST` | `/api/v1/issuances/{id}/deploy` | Implementar en blockchain |
| `POST` | `/api/v1/issuances/{id}/suspend` | Suspender token |
| `POST` | `/api/v1/issuances/{id}/redeem` | Marcar como canjeado |
| `GET` | `/api/v1/issuances/{id}/investors` | Listar inversores |
| `POST` | `/api/v1/issuances/{id}/investors` | Añadir inversor |
| `DELETE` | `/api/v1/issuances/{id}/investors/{investorId}` | Eliminar inversor |
| `POST` | `/api/v1/issuances/{id}/investors/{investorId}/whitelist` | Incluir monedero en lista blanca on-chain |
| `GET` | `/api/v1/investments` | Listar tenencias de tokens (inversor) |
| `GET` | `/api/v1/transfers` | Listar transferencias para su entidad |
| `GET` | `/api/v1/audit-log` | Registro de auditoría (ámbito de su entidad) |
| `GET` | `/api/v1/profile` | Su perfil de entidad |
| `POST` | `/api/v1/wallets` | Registrar un monedero |
| `DELETE` | `/api/v1/wallets/{address}` | Eliminar un monedero |

### Puntos finales de administración (`/api/v1/admin/`) { #admin-endpoints-apiv1admin }

Requiere el rol `REGISTRY_ADMIN`.

| Método | Ruta | Descripción |
|--------|------|-------------|
| `GET` | `/api/v1/admin/entities` | Listar todas las entidades |
| `POST` | `/api/v1/admin/entities` | Crear entidad + enviar invitación |
| `PATCH` | `/api/v1/admin/entities/{id}/status` | Actualizar estado de entidad |
| `GET` | `/api/v1/admin/kyc` | Listar revisiones KYC pendientes |
| `POST` | `/api/v1/admin/kyc/{id}/approve` | Aprobar KYC |
| `POST` | `/api/v1/admin/kyc/{id}/reject` | Rechazar KYC |
| `POST` | `/api/v1/admin/issuances/{id}/approve` | Aprobar emisión |
| `POST` | `/api/v1/admin/issuances/{id}/reject` | Rechazar emisión |
| `GET` | `/api/v1/admin/chains` | Listar todas las cadenas |
| `POST` | `/api/v1/admin/chains` | Añadir una cadena |
| `PATCH` | `/api/v1/admin/chains/{chainId}` | Actualizar configuración de cadena |
| `POST` | `/api/v1/admin/chains/refresh` | Recargar clientes de cadena |
| `GET` | `/api/v1/admin/audit-log` | Registro de auditoría completo (todas las entidades) |

## Respuestas de error { #error-responses }

Todos los errores siguen un formato estándar:

```json
{
  "timestamp": "2025-04-06T12:00:00Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "code": "ISSUANCE_INVALID_STATE",
  "message": "Cannot submit issuance in state ISSUED",
  "path": "/api/v1/issuances/abc123/submit"
}
```

Códigos de error comunes:

| Código | HTTP | Descripción |
|------|------|-------------|
| `UNAUTHORIZED` | 401 | JWT ausente o no válido |
| `FORBIDDEN` | 403 | Rol insuficiente para esta operación |
| `NOT_FOUND` | 404 | El recurso no existe |
| `ISSUANCE_INVALID_STATE` | 422 | No se permite la transición de estado |
| `BLOCKCHAIN_ERROR` | 502 | Error en la llamada a la cadena RPC |
| `INDEXER_UNAVAILABLE` | 503 | graph-node no accesible |

## Limitación de velocidad { #rate-limiting }

Las llamadas a la API tienen limitación de velocidad en la puerta de enlace de Kong:

- 300 solicitudes/minuto por consumidor autenticado
- 10 solicitudes/minuto para los puntos finales de autenticación

Los encabezados de límite de velocidad se incluyen en las respuestas:

```
X-RateLimit-Limit-Minute: 300
X-RateLimit-Remaining-Minute: 287
```

# Referencia de la API { #api-reference_1 }

La especificación OpenAPI completa está disponible en:

```
http://localhost:48080/v3/api-docs
http://localhost:48080/swagger-ui.html
```

## Puntos finales clave { #key-endpoints }

### Entidades { #entities }
| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/api/v1/entities` | Listar todas las entidades |
| `POST` | `/api/v1/entities` | Crear entidad |
| `GET` | `/api/v1/entities/{id}` | Obtener entidad |
| `PUT` | `/api/v1/entities/{id}` | Actualizar entidad |
| `GET` | `/api/v1/entities/{id}/kyc/documents` | Listar documentos KYC |
| `POST` | `/api/v1/entities/{id}/kyc/documents` | Cargar documento KYC |

### Activos { #assets }
| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/api/v1/assets` | Listar todos los activos |
| `POST` | `/api/v1/assets` | Crear activo |
| `GET` | `/api/v1/assets/{id}` | Obtener activo |
| `POST` | `/api/v1/assets/{id}/deployments` | Implementar en cadena |
| `GET` | `/api/v1/assets/{id}/deployments/{depId}/history` | Historial de transferencias |
| `GET` | `/api/v1/assets/{id}/holders` | Listar titulares |

### ERC-3643 { #erc-3643 }
| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/api/v1/assets/{id}/deployments/{depId}/erc3643` | Obtener la suite T-REX |
| `POST` | `/api/v1/assets/{id}/deployments/{depId}/erc3643/compliance-modules` | Agregar módulo |
| `POST` | `/api/v1/assets/{id}/deployments/{depId}/erc3643/trusted-issuers` | Agregar emisor |

### ONCHAINID { #onchainid }
| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/api/v1/identities` | Listar identidades |
| `POST` | `/api/v1/identities` | Crear ONCHAINID |
| `POST` | `/api/v1/identities/{id}/claims` | Emitir atestación KYC |
| `DELETE` | `/api/v1/identities/{id}/claims/{claimId}` | Revocar atestación |

### Admin { #admin }
| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/api/v1/admin/chains` | Listar configuraciones de cadena |
| `POST` | `/api/v1/admin/chains` | Añadir cadena |
| `PUT` | `/api/v1/admin/chains/{id}` | Actualizar cadena |
| `POST` | `/api/v1/admin/chains/refresh` | Recargar clientes Web3j |
| `GET` | `/api/v1/audit` | Consultar registro de auditoría |

### Público (sin autenticación) { #public-no-auth }
| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/api/v1/public/assets/by-address/{address}` | Buscar token |
| `GET` | `/api/v1/public/chains` | Listar cadenas activas |
| `GET` | `/api/v1/onboarding/token-info/{token}` | Validar token de incorporación |
| `POST` | `/api/v1/onboarding/complete` | Completar incorporación con token |
