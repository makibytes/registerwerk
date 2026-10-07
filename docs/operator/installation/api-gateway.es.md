---
title: Puerta de enlace API (Kong)
---

# Puerta de enlace API (Kong) { #api-gateway-kong }

Kong 3.9 (OSS, sin base de datos) se sitúa únicamente delante del **tráfico de la API de la interfaz del cliente**. Se encarga de la
limitación de velocidad, el almacenamiento en caché de respuestas y las cabeceras de seguridad. **No** se sitúa delante de la interfaz de usuario
de ninguna de las dos aplicaciones; ambas se abren siempre directamente en el navegador, en su propio puerto (`:44200`, `:44201`), y
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

`gateway/kong.yml` es la única fuente de verdad. Se monta en solo lectura en `/etc/kong/kong.yml` y se carga al arrancar mediante `KONG_DECLARATIVE_CONFIG`. Kong funciona sin base de datos, así que no hay base de datos ni `deck sync` (deck escribe en una Admin API respaldada por una base de datos, y esta pila no publica ninguna). Para cambiar el enrutamiento o los plugins, edite el archivo, valídelo y vuelva a crear el contenedor:

```bash
docker compose run --rm kong kong config parse /etc/kong/kong.yml   # debe imprimir «parse successful»
docker compose up -d --force-recreate kong
```

En Kubernetes, edite `deploy/helm/registerwerk/files/kong.yml` (se renderiza en el ConfigMap `registerwerk-kong-config`) y ejecute `helm upgrade`; reinicie el despliegue de Kong si los pods no recogen el ConfigMap modificado.

## Complementos clave { #key-plugins }

Solo los complementos Kong OSS incluidos están activos de forma predeterminada (consulte `gateway/kong.yml`):

| Complemento | Propósito |
|---|---|
| `proxy-cache` | Almacena en caché las respuestas GET de ruta pública 200 durante 30-60 segundos |
| `request-transformer` | Elimina cualquier `X-Entity-Id`/`X-Entity-Roles` proporcionado por el cliente en rutas públicas, para que no se pueda introducir nada de contrabando antes de que el servidor vea la solicitud |
| `rate-limiting` | 300 solicitudes/minuto, 10.000/hora por IP de cliente (con Redis, compartido entre réplicas de Kong) |
| `bot-detection` | Bloquea agentes de usuario de rastreadores/escáneres comunes |
| `ip-restriction` | Restringe `/api/v1/admin/**` a los CIDR de red del operador, comparados con la IP real del cliente |
| `cors` | Cabeceras de origen cruzado para la interfaz Angular del cliente |
| `request-size-limiting` | Cuerpo de solicitud máximo de 20 MB |
| `response-transformer` | Agrega encabezados de seguridad estándar (HSTS, CSP, X-Frame-Options,…) |

`openid-connect` (la terminación del JWT en la puerta de enlace) es **exclusivo de Kong Enterprise/Konnect** y no
está activo en esta configuración OSS: hay un fragmento listo para fusionar en `gateway/plugins/oidc-entra.yml` para las implementaciones
que ejecutan Kong Enterprise. Sin él, la validación del JWT y la extracción de entidad/rol suceden
por completo en el backend de Spring, leyendo los claims del propio token: Kong nunca
inyecta aquí las cabeceras `X-Entity-Id`/`X-Entity-Roles`.

## Gestión de la IP del cliente

El rate limiting, la `ip-restriction` de administración y la limitación de inicios de sesión del backend dependen de la dirección real del cliente; un `X-Forwarded-For` aportado por el cliente nunca se cree:

- Los nginx **sobrescriben** `X-Forwarded-For` con el par TCP observado (nunca añaden). Detrás del ingress de Helm, nginx restaura antes la dirección real mediante `ingress.trustedCidrs`.
- Kong solo confía en `X-Forwarded-For` desde la red de nginx/ingress (`KONG_TRUSTED_IPS`, `KONG_REAL_IP_HEADER=X-Forwarded-For`, `KONG_REAL_IP_RECURSIVE=off`; en el chart `kong.env.trusted_ips`).
- El backend solo confía en la cabecera desde `REGISTERWERK_AUTH_TRUSTED_PROXIES` (Compose fija un valor por defecto para la demo local; el chart no tiene ninguno y no se renderiza hasta que nombre solo los pods de Kong y del nginx del operador, nunca un rango privado completo).
- La lista de permitidos de administración de Compose incluye `192.168.0.0/16` y `::1` para la demo local. En Helm se genera a partir de `kong.adminAllowCidrs` (obligatorio, sin valor por defecto).

`scripts/check-client-ip.sh` comprueba en una pila en ejecución que valores `X-Forwarded-For` falsificados no reinician el contador de límite. El ingress de API de Helm apunta a Kong, nunca al backend, y responde 404 a `/actuator/*` salvo health.

## Kong admin API { #kong-admin-api }

Kong funciona sin base de datos y **no incluye interfaz de administración** en esta pila (ni Konga ni Kong Manager). La Admin API escucha solo en `127.0.0.1:8001` **dentro del contenedor** (`KONG_ADMIN_LISTEN`) y no se publica en el host; no está autenticada y nunca debe exponerse. La imagen de Kong no incluye `curl`, así que use la CLI incorporada:

```bash
docker compose exec kong kong health
```

Para cambiar el enrutamiento o los plugins, edite `gateway/kong.yml` y vuelva a crear el servicio `kong` como se describe arriba — es la única fuente de verdad en el modo sin base de datos.
