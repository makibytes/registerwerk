---
title: Descripción general de la API REST
description: Estructura URL, autenticación, respuestas de error, paginación y convenciones API.
---

# Descripción general de la API REST { #rest-api-overview }

Toda la funcionalidad de Registerwerk se expone a través de REST API en `http://backend:8080`. La interfaz del operador se conecta directamente; la interfaz del cliente se conecta a través de Kong (`http://kong:8000`). Cada ruta mapeada figura en el [índice de rutas de la API](api-routes.md) generado (solo en inglés). Existen un documento OpenAPI 3 y una Swagger UI, pero están **desactivados por defecto** (véase [OpenAPI / Swagger UI](#openapi-swagger-ui)).

---

## Estructura URL { #url-structure }

| Patrón | Se requiere autenticación | Disponible para |
|---|---|---|
| `/api/v1/public/**` | No | Todos |
| `/api/v1/onboarding/token-info/**` | No | Flujo de incorporación de clientes |
| `/api/v1/onboarding/complete` | No | Flujo de incorporación de clientes |
| `/api/v1/**` | Se requiere JWT | Usuarios autenticados (dependientes de la función) |

---

## Autenticación { #authentication }

Todos los puntos finales protegidos requieren:

```
Authorization: Bearer <jwt>
```

**El backend valida cada token por sí mismo, en cada solicitud.** Kong no valida los JWT y no le dice al backend quién es la persona que llama; su complemento `openid-connect` es una función empresarial y no está activo en esta configuración OSS. Kong además *quita* los encabezados de identidad proporcionados por el cliente, por lo que no se puede pasar nada de contrabando antes del backend.

Los tokens de operador son emitidos por `POST /api/v1/public/auth/login` (HS256, `iss: registerwerk-local`). Los tokens de cliente los emite el proveedor OIDC cuando es `ENTRA_ENABLED=true` y, en caso contrario, el mismo punto final local. Un decodificador delegado enruta el encabezado JWS `alg`; ambas ramas están fijadas por el emisor y la rama OIDC está fijada por la audiencia. Consulte [Seguridad y autenticación](security.md).

---

## Formato de respuesta de error { #error-response-format }

Todos los errores siguen el registro `ErrorResponse`:

```json
{
  "status": 404,
  "message": "Asset with id 'abc...' not found",
  "timestamp": "2026-05-22T10:15:30Z",
  "path": "/api/v1/assets/abc..."
}
```

| Estado HTTP | Lanzado por | Causa |
|---|---|---|
| 400 | `IllegalArgumentException` | Entrada no válida (fallo de validación, valor de enumeración incorrecto) |
| 401 | `InvalidCredentialsException` | Contraseña incorrecta, JWT caducado |
| 403 | `AccessDeniedException` | Rol insuficiente, se requiere un paso adelante |
| 404 | `EntityNotFoundException` | El recurso no existe |
| 409 | `InvalidStateTransitionException` | Operación no permitida en el estado actual (por ejemplo, implementar activos ya implementados) |
| 500 | Excepción inesperada | Error interno del servidor (detalles no expuestos en el producto) |

!!! info "Mensajes de error en producción"
    `error.include-message` está configurado en `never` en el perfil `prod`. En desarrollo y prueba, es `always`. Esto evita que los seguimientos de la pila se filtren en las respuestas de producción.

---

## Paginación { #pagination }

Los endpoints de lista que paginan aceptan `page` (base cero) y `size`, por ejemplo:

```
GET /api/v1/assets?page=0&size=20&sort=createdAt,desc
```

La forma de la respuesta depende **de cada endpoint**: algunos devuelven un array JSON simple (el total va entonces en la cabecera de respuesta `X-Total-Count`, que la configuración CORS expone a los navegadores), otros devuelven el contenedor `PageResponse` `{ content, totalElements, totalPages, page, size }`. Compruebe el esquema del endpoint en el documento OpenAPI antes de fiarse de una u otra forma.

---

## Idempotency-Key e importes { #idempotency-key-and-amounts }

Los endpoints de administración/emisor que mueven fondos o cambian el estado exigen la cabecera `Idempotency-Key`: mint, burn, transferencias/aprobaciones forzosas, force-burn, cambios de congelación y de lista blanca, administración de tokens de Solana, operaciones de slot y de vault, acciones de agente ERC-3643, operaciones y conciliación de mercados de préstamo, cambios de medios de pago, importación de wallets, entrega/finalización de transferencias de registro y amortización de un activo. Un `POST`, `PUT`, `PATCH` o `DELETE` sin una clave válida se rechaza con `400` y el código `IDEMPOTENCY_KEY_REQUIRED` (o `IDEMPOTENCY_KEY_INVALID`) antes de ejecutar nada. Los demás endpoints siguen siendo opcionales.

- Envíe un valor único por acción del usuario (UUID; de 8 a 255 caracteres de `A-Za-z0-9._:-`) y **reutilice el mismo valor al repetir la misma solicitud** tras un timeout o un `5xx`. La repetición devuelve entonces el resultado original (`X-Idempotent-Replay: true`) o la misma transacción, en lugar de ejecutarse dos veces.
- La clave es propia de cada llamante: la entidad jurídica para tokens de cliente, el usuario que actúa para tokens de operador. La misma clave con otro método, ruta o cuerpo recibe `422`; una solicitud aún en curso recibe `409`.
- La clave se guarda además en la fila de outbox de la transacción on-chain, de modo que una repetición corresponde a la misma transacción firmada incluso tras caducar la respuesta en caché. Las respuestas `401`/`403` (incluidos los desafíos de step-up) y `5xx` no se almacenan en caché; repetir la solicitud tras un step-up con la misma clave es seguro.

**Los importes son cadenas decimales.** Envíe los importes de tokens (`amount`, `value`, `newCap`, `navPerShare`, ...) como cadenas JSON, por ejemplo `"1000000000000000000000"`. Un número de JavaScript pierde precisión por encima de 2^53. Durante una versión se sigue aceptando un número JSON si es exactamente representable (entero inferior a 2^53 o decimal de como máximo 15 cifras significativas) y se registra un aviso de obsolescencia; todo lo demás recibe `400` con `Invalid amount: ...`.

## Grupos de rutas { #route-groups }

El [índice de rutas de la API](api-routes.md) generado es la lista completa, derivada del código (método, ruta, expresión de rol, step-up). Las rutas base principales:

| Área | Ruta base |
|---|---|
| Activos y despliegues (mint/burn del emisor bajo `.../deployments/{depId}/issuer/`, operaciones forzosas del operador bajo `.../deployments/{depId}/admin/`) | `/api/v1/assets`, `/api/v1/deployments` |
| Entidades jurídicas y KYC (`/api/v1/entities/{entityId}/kyc/...`), cola de revisión KYC | `/api/v1/entities`, `/api/v1/kyc` |
| Filtrado de sanciones (`/api/v1/compliance/screening/...`, coincidencias bajo `/hits/{hitId}/accept`) y otras funciones de cumplimiento | `/api/v1/compliance` |
| Sperrvermerk (bloqueos de titular) | `/api/v1/holder-blocks` |
| Reportes regulatorios (MiFIR, DAC8) | `/api/v1/regulatory-reporting` |
| Incidentes, proveedores y pruebas de resiliencia DORA | `/api/v1/dora` |
| Negociación, repo desk, lending, operaciones societarias | `/api/v1/trading`, `/api/v1/repo-desk`, `/api/v1/lending`, `/api/v1/corporate-actions` |
| Cola de aprobaciones de doble control | `/api/v1/approvals` |
| Registro de auditoría, verificación de la cadena | `/api/v1/audit` |
| Administración del operador (usuarios, wallets, ...) | `/api/v1/admin` |
| Autoservicio de las empresas cliente | `/api/v1/company`, `/api/v1/me` |
| Público, sin autenticación (cadenas, capacidades de la plataforma, Travel Rule) | `/api/v1/public` |

Aprobar un KYC, por ejemplo, es `POST /api/v1/entities/{entityId}/kyc/approve`: quien inicia la operación es un `REGISTRY_ADMIN` o un `COMPLIANCE_OFFICER`, y la llamada exige autenticación reforzada y un segundo aprobador (véase la [matriz de step-up](../compliance/step-up-matrix.md)).

---

## OpenAPI / Swagger UI { #openapi-swagger-ui }

El documento OpenAPI y la Swagger UI los sirve **el backend**, no este servidor de documentación, y están **desactivados salvo que `SWAGGER_ENABLED=true`** (por defecto `false`, en todos los perfiles).

| URL (si está activado) | Descripción |
|---|---|
| [`{{ backend_url }}/swagger-ui.html`]({{ backend_url }}/swagger-ui.html) | Swagger UI interactiva (navegador) |
| [`{{ backend_url }}/api-docs`]({{ backend_url }}/api-docs) | JSON OpenAPI 3 (legible por máquina) |
| [`{{ backend_url }}/actuator/health`]({{ backend_url }}/actuator/health) | Comprobación de estado |
| [`{{ backend_url }}/actuator/info`]({{ backend_url }}/actuator/info) | Información de compilación |

!!! info "Este sitio de documentación frente a la API"
    Este sitio (puerto 48003) es una referencia estática de MkDocs — no hace de proxy del backend. Abra los enlaces anteriores directamente en un navegador mientras la pila esté en marcha (`docker compose up -d`).

!!! warning "Activar SWAGGER_ENABLED expone la especificación sin autenticación"
    Cuando está activado, `/swagger-ui.html`, `/swagger-ui/**` y `/api-docs/**` son públicos (`permitAll` en la configuración de seguridad): cualquiera que alcance el backend puede leer todo el catálogo de rutas y esquemas. Nada rechaza `SWAGGER_ENABLED=true` en modo producción. Déjelo desactivado en producción, o ponga el backend detrás de una lista de permitidos que excluya estas rutas.
