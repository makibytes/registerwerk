---
title: Referencia de la API
---

# Referencia de la API

Registerwerk expone una API REST para todas las operaciones del registro. Esta página es el punto de entrada del operador; las convenciones (errores, paginación, idempotencia, importes) están en la [visión general de la API REST](../platform/api.md), y cada ruta figura en el [índice de rutas de la API](../platform/api-routes.md) generado (solo en inglés).

## Documentación interactiva

El documento OpenAPI y la Swagger UI están **desactivados por defecto**. Defina `SWAGGER_ENABLED=true` para que el backend los sirva:

```
http://localhost:48080/swagger-ui.html
http://localhost:48080/api-docs
```

!!! warning "La especificación no está autenticada cuando está activada"
    Con `SWAGGER_ENABLED=true`, estas rutas son `permitAll`. Nada rechaza el ajuste en modo producción. Manténgalo desactivado en los despliegues expuestos a Internet.

## Autenticación

Todos los endpoints de la API, salvo `/api/v1/public/**` (y los endpoints de token de onboarding), exigen un JWT Bearer:

```bash
curl http://localhost:48080/api/v1/entities \
  -H "Authorization: Bearer <jwt>"
```

Los tokens de operador provienen de `POST /api/v1/public/auth/login` (el portal del operador lo usa directamente). Con `ENTRA_ENABLED=true`, los tokens de cliente provienen de Entra; en caso contrario, del mismo endpoint local. Véase [Seguridad y autenticación](../platform/security.md) y, para el lado del cliente, [Inicio de sesión](../customer/authentication.md). El backend valida él mismo cada token; Kong no lo hace.

## Dónde encontrar un endpoint

| Necesidad | Dónde |
|---|---|
| Cada ruta, su expresión de rol y su requisito de step-up | [Índice de rutas de la API](../platform/api-routes.md) |
| Qué rutas exigen autenticación reforzada o un segundo aprobador, y qué operaciones vinculan el cuerpo de la petición | [Matriz de step-up](../compliance/step-up-matrix.md) |
| Esquemas de petición y de respuesta | Documento OpenAPI (`SWAGGER_ENABLED=true`) |
| Formato de error, paginación, `Idempotency-Key`, importes decimales | [Visión general de la API REST](../platform/api.md) |

## Respuestas de error

Los errores usan el record `ErrorResponse` (`status`, `message`, `timestamp`, `path`); la correspondencia de estados (400, 401, 403, 404, 409, 500) está documentada en la [visión general de la API REST](../platform/api.md#error-response-format). Algunos endpoints añaden un `code` legible por máquina, por ejemplo `IDEMPOTENCY_KEY_REQUIRED`, `IMPERSONATION_READ_ONLY` o `IMPERSONATION_ACTION_DENIED`.

## Limitación de tasa

Las llamadas a la API de cliente pasan por Kong, que limita cada IP de cliente a 300 peticiones por minuto y 10 000 por hora, contadas en Redis para que el límite se comparta entre las réplicas de Kong (véase [Gateway de API](installation/api-gateway.md)). Las llamadas desde el portal del operador no pasan por Kong y no están sujetas a este límite. Las respuestas incluyen cabeceras de límite de tasa (`X-RateLimit-Limit-Minute`, `X-RateLimit-Remaining-Minute`); un límite superado se responde con `429`.
