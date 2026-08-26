---
title: Descripción general de la API REST
description: Estructura URL, autenticación, respuestas de error, paginación y convenciones API.
---

# Descripción general de la API REST { #rest-api-overview }

Toda la funcionalidad de Registerwerk se expone a través de REST API en `http://backend:8080`. La interfaz del operador se conecta directamente; la interfaz del cliente se conecta a través de Kong (`http://kong:8000`). El API está documentado con OpenAPI 3 (Swagger UI disponible en `/swagger-ui.html`).

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

Los puntos finales de la lista admiten la paginación basada en cursor con los parámetros `page` y `size`:

```
GET /api/v1/assets?page=0&size=20&sort=createdAt,desc
```

Las respuestas incluyen un encabezado `X-Total-Count` con el recuento total de registros (antes de la paginación). El cuerpo de la respuesta es siempre una matriz (nunca un objeto contenedor).

---

## Grupos clave de la API { #key-api-groups }

### Activos (`/api/v1/assets`) { #assets-apiv1assets }

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/api/v1/assets` | Listar todos los activos (paginados) |
| `POST` | `/api/v1/assets` | Crear nuevo activo |
| `GET` | `/api/v1/assets/{id}` | Obtener activo por ID |
| `POST` | `/api/v1/assets/{id}/deploy` | Implementar token en blockchain |
| `POST` | `/api/v1/assets/{id}/mint` | Acuñar tokens |
| `POST` | `/api/v1/assets/{id}/burn` | Destruir tokens (step-up + 4-eyes) |
| `POST` | `/api/v1/assets/{id}/force-transfer` | Transferencia forzosa (step-up + 4-eyes) |
| `POST` | `/api/v1/assets/{id}/freeze/{address}` | Congelar dirección (requiere HolderBlock) |

### Clientes (`/api/v1/customers`) { #customers-apiv1customers }

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/api/v1/customers` | Lista de entidades jurídicas |
| `POST` | `/api/v1/customers` | Crear entidad jurídica |
| `GET` | `/api/v1/customers/{id}` | Obtener entidad |
| `POST` | `/api/v1/customers/{id}/kyc/documents` | Subir documento KYC |
| `POST` | `/api/v1/customers/{id}/kyc/approve` | Aprobar KYC (COMPLIANCE_OFFICER + step-up) |
| `GET` | `/api/v1/customers/{id}/beneficial-owners` | Listar titulares reales |
| `POST` | `/api/v1/customers/{id}/beneficial-owners` | Agregar titular real |

### Cumplimiento (`/api/v1/compliance`) { #compliance-apiv1compliance }

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/api/v1/compliance/screening/entities/{id}/screen` | Iniciar cribado manual |
| `GET` | `/api/v1/compliance/screening/entities/{id}/runs` | Obtener historial de cribado |
| `POST` | `/api/v1/compliance/screening/hits/{hitId}/accept` | Aceptar/descartar un hit |
| `GET` | `/api/v1/holder-blocks` | Listar todos los HolderBlocks |
| `POST` | `/api/v1/holder-blocks` | Crear Sperrvermerk (step-up + 4-eyes) |
| `POST` | `/api/v1/holder-blocks/{id}/lift` | Levantar Sperrvermerk (step-up + 4-eyes) |

### Informes regulatorios (`/api/v1/regulatory-reporting`) { #regulatory-reporting-apiv1regulatory-reporting }

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/api/v1/regulatory-reporting/mifir` | Activar exportación MiFIR bajo demanda |
| `POST` | `/api/v1/regulatory-reporting/dac8` | Activar exportación DAC8 bajo demanda |
| `GET` | `/api/v1/regulatory-reporting/submissions` | Listar historial de envíos |

### DORA (`/api/v1/dora`) { #dora-apiv1dora }

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/api/v1/dora/incidents` | Listar incidentes TIC abiertos |
| `POST` | `/api/v1/dora/incidents` | Reportar un incidente TIC (art. 17) |
| `PATCH` | `/api/v1/dora/incidents/{id}/status` | Actualizar el estado del incidente/causa raíz |
| `POST` | `/api/v1/dora/incidents/{id}/report-to-authority` | Registrar informe de autoridad inicial/final (art. 19) |
| `GET` | `/api/v1/dora/providers` | Listar el registro de terceros proveedores de TIC (art. 28) |
| `GET` | `/api/v1/dora/providers/expiring` | Lista de proveedores con contratos que expirarán pronto |
| `GET` | `/api/v1/dora/resilience-tests` | Listar resultados de pruebas de resiliencia (art. 24/25) |
| `GET` | `/api/v1/dora/resilience-tests/overdue` | Lista de pruebas de resiliencia vencidas |
| `POST` | `/api/v1/dora/resilience-tests` | Registrar el resultado de una prueba de resiliencia |

---

## OpenAPI / Swagger UI { #openapi-swagger-ui }

La especificación OpenAPI y la interfaz de usuario interactiva son proporcionadas **por el backend** en el puerto 8080, no por este servidor de documentación.

| URL | Descripción |
|---|---|
| [`{{ backend_url }}/swagger-ui.html`]({{ backend_url }}/swagger-ui.html) | Interfaz de usuario interactiva Swagger (navegador) |
| [`{{ backend_url }}/api-docs`]({{ backend_url }}/api-docs) | OpenAPI 3 JSON (legible por máquina) |
| [`{{ backend_url }}/actuator/health`]({{ backend_url }}/actuator/health) | Comprobación de estado |
| [`{{ backend_url }}/actuator/info`]({{ backend_url }}/actuator/info) | Información de compilación |

!!! info "Este sitio de documentación frente a la API"
    Este sitio (puerto 48003) es una referencia estática de MkDocs: no actúa de proxy hacia el backend. Abra los enlaces anteriores directamente en un navegador mientras se ejecuta la pila (`docker compose up -d`).

!!! warning "Interfaz de usuario Swagger en producción"
    La interfaz de usuario de Swagger está deshabilitada en el perfil de Spring `prod`. En entornos de desarrollo y ensayo es accesible sin autenticación. En producción, debe estar habilitado y protegido explícitamente detrás de una lista de direcciones IP permitidas o autenticación básica.
