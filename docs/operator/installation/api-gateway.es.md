---
title: Puerta de enlace API (Kong)
---

# Puerta de enlace API (Kong) { #api-gateway-kong }

Kong 3.8 (OSS, sin base de datos) se sitúa únicamente delante del **tráfico de la API de la interfaz del cliente**. Se encarga de la
limitación de velocidad, el almacenamiento en caché de respuestas y las cabeceras de seguridad. **No** se sitúa delante de la interfaz de usuario
de ninguna de las dos aplicaciones; ambas se abren siempre directamente en el navegador, en su propio puerto (`:4200`, `:4201`), y
la **interfaz del operador omite Kong por completo**, incluso para sus propias llamadas a la API (su nginx reenvía
`/api/` directamente a `backend:8080`). La validación del JWT y la extracción de entidad/rol siempre ocurren
en el propio backend de Spring, a partir de los claims del propio token — no mediante ninguna cabecera inyectada por Kong,
en la configuración OSS que distribuye este repositorio.

## Iniciando Kong { #starting-kong }

```bash
docker compose up -d kong
```

Kong se ejecuta en modo sin base de datos (declarativo): lee `gateway/kong.yml` directamente a través de
`KONG_DECLARATIVE_CONFIG` y no necesita ninguna base de datos propia.

## Configuración declarativa { #declarative-configuration }

Kong se configura a través de `gateway/kong.yml` en formato deck. Para aplicar cambios:

```bash
deck sync --config gateway/kong.yml
```

## Complementos clave { #key-plugins }

Solo los complementos Kong OSS incluidos están activos de forma predeterminada (consulte `gateway/kong.yml`):

| Complemento | Propósito |
|---|---|
| `proxy-cache` | Almacena en caché las respuestas GET de ruta pública 200 durante 30-60 segundos |
| `request-transformer` | Elimina cualquier `X-Entity-Id`/`X-Entity-Roles` proporcionado por el cliente en rutas públicas, para que no se pueda introducir nada de contrabando antes de que el servidor vea la solicitud |
| `rate-limiting` | 300 solicitudes/minuto, 10.000/hora por consumidor |
| `bot-detection` | Bloquea agentes de usuario de rastreadores/escáneres comunes |
| `ip-restriction` | Restringe `/api/v1/admin/**` a los CIDR de red del operador |
| `cors` | Cabeceras de origen cruzado para la interfaz Angular del cliente |
| `request-size-limiting` | Cuerpo de solicitud máximo de 20 MB |
| `response-transformer` | Agrega encabezados de seguridad estándar (HSTS, CSP, X-Frame-Options,…) |

`openid-connect` (la terminación del JWT en la puerta de enlace) es **exclusivo de Kong Enterprise/Konnect** y no
está activo en esta configuración OSS: hay un fragmento listo para fusionar en `gateway/plugins/oidc-entra.yml` para las implementaciones
que ejecutan Kong Enterprise. Sin él, la validación del JWT y la extracción de entidad/rol suceden
por completo en el backend de Spring, leyendo los claims del propio token: Kong nunca
inyecta aquí las cabeceras `X-Entity-Id`/`X-Entity-Roles`.

## Kong admin API { #kong-admin-api }

Kong se ejecuta sin base de datos y en esta pila no incluye **ninguna GUI de administración** (ni Konga ni Kong Manager; ambos fueron
eliminados o nunca se llegaron a conectar). El acceso a la API de administración está intencionadamente restringido a loopback:

```bash
# Bound to 127.0.0.1:8001 on the host — never expose this publicly, it's unauthenticated
docker compose exec kong kong health
curl http://127.0.0.1:8001/status
```

Para cambiar el enrutamiento o los complementos, edite `gateway/kong.yml` y reinicie el servicio `kong`; es la única fuente de verdad
en modo sin base de datos.
