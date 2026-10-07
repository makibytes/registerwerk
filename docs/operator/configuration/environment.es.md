---
title: Variables de entorno
---

# Variables de entorno { #environment-variables }

Toda la configuración se realiza a través de variables de entorno. Copie `.env.example` a `.env` y complete los valores.

## Base de datos { #database }

| Variables | Predeterminado | Descripción |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://postgres:5432/registerwerk` | URL de conexión JDBC |
| `DB_USER` | `registerwerk` | Usuario de base de datos |
| `DB_PASSWORD` | — | **Obligatorio** |
| `DB_APP_USER` / `DB_APP_PASSWORD` | `DB_USER` / `DB_PASSWORD` | Login de ejecución con el que se conecta la aplicación (`registerwerk_app` en las configuraciones de Compose y Helm incluidas). En modo producción debe diferir del login propietario, porque la aplicación no debe ser propietaria de las tablas de auditoría |
| `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` | — | Login de migración/propietario que usa solo Flyway (igual a `DB_USER`/`DB_PASSWORD` en Compose y Helm) |

## Autenticación { #authentication }

### Administrador integrado (modo sin IdP) { #built-in-admin-no-idp-mode }

| Variables | Predeterminado | Descripción |
|---|---|---|
| `ENTRA_ENABLED` | `false` | `false` → formulario de nombre de usuario/contraseña en el operador FE; `true` → Botón Microsoft |
| `DEFAULT_ADMIN_EMAIL` | — | Correo electrónico del usuario administrador inicializado (solo modo integrado) |
| `DEFAULT_ADMIN_PASSWORD` | — | Contraseña en texto sin formato codificada con BCrypt al crear el administrador; solo se usa si no existe ningún `REGISTRY_ADMIN`, nunca se vuelve a aplicar a una cuenta existente |
| `JWT_DEV_SECRET` | incorporado | Clave de firma HS256 utilizada en modo de desarrollo/demo; dejar sin configurar para local, anular en preparación |

### OAuth2 / OIDC (producción) { #oauth2-oidc-production }

| Variables | Descripción |
|---|---|
| `JWT_ISSUER_URI` | URL del emisor OIDC: déjelo en blanco para el modo de desarrollo HS256; configúrelo para producción (p. ej. `https://login.microsoftonline.com/<tenant>/v2.0`) |
| `ENTRA_CLIENT_ID` | ID de cliente del registro de aplicación de la API; se usa con el secreto para el acceso a Microsoft Graph solo de aplicación (estado de doble factor, consola de soporte). Kong no lo usa, porque no hace OIDC |
| `ENTRA_CLIENT_SECRET` | Secreto de cliente del registro de aplicación de la API (credencial de Graph solo de aplicación; obligatorio con `ENTRA_SUPPORT_ENABLED=true`) |

## Blockchain RPCs { #blockchain-rpcs }

| Variables | Cadena |
|---|---|
| `ETH_MAINNET_RPC` | Red principal de Ethereum |
| `ETH_SEPOLIA_RPC` | Ethereum Sepolia |
| `POLYGON_MAINNET_RPC` | Red principal de Polygon |
| `POLYGON_AMOY_RPC` | Polygon Amoy |
| `BASE_MAINNET_RPC` | Red principal de Base |
| `BASE_SEPOLIA_RPC` | Base Sepolia |
| `SOLANA_MAINNET_RPC` | Red principal de Solana |
| `SOLANA_DEVNET_RPC` | Solana Devnet |
| `REGISTRY_WALLET_PRIVATE_KEY` | Clave de firmante backend para operaciones blockchain |
| `REGISTRY_SOLANA_PRIVATE_KEY` | Clave de firmante de Solana opcional |

## Almacenamiento { #storage }

| Variables | Descripción |
|---|---|
| `S3_BUCKET` | Nombre del depósito S3 para documentos KYC |
| `S3_ENDPOINT` | URL del endpoint compatible con S3 |
| `S3_ACCESS_KEY` | Clave de acceso S3 |
| `S3_SECRET_KEY` | Clave secreta S3 |
| `S3_REGION` | Región S3 |

Los documentos de menos de 5 MB se almacenan en línea como BYTEA en PostgreSQL. Los documentos ≥5 MB se almacenan en S3.

## Correo electrónico { #email }

| Variables | Descripción |
|---|---|
| `MAIL_HOST` | Servidor SMTP |
| `MAIL_PORT` | Puerto SMTP (predeterminado 587) |
| `MAIL_USERNAME` | Nombre de usuario SMTP |
| `MAIL_PASSWORD` | Contraseña SMTP |

## Incorporación { #onboarding }

| Variables | Descripción |
|---|---|
| `CUSTOMER_FRONTEND_URL` | Base URL de la interfaz del cliente (para enlaces de correo electrónico) |
| `FRONTEND_BUILD_ENV` | Objetivo de compilación de frontend: `production` o `testnet` |

## Modo producción y puertas de lanzamiento

Defina `REGISTERWERK_PRODUCTION_MODE=true` en cada despliegue de producción. Convierte las comprobaciones de preparación de advertencias en rechazos de arranque y activa los controles exclusivos de producción. [Modo producción y puertas de lanzamiento](../security/production-mode.md) (solo en inglés) enumera cada puerta, la variable que la controla y qué se rechaza. Esa página documenta también las variables de aprobación de lanzamiento y de reconocimiento (`REGISTERWERK_LENDING_RELEASE_APPROVED`, `REGISTERWERK_REPO_DESK_RELEASE_APPROVED`, `REGISTERWERK_TRADING_LEGAL_OPINION_REF`, `REGISTERWERK_AUDIT_ALLOW_OWNER_RUNTIME_ROLE`, `REGISTERWERK_AUDIT_SIGNING_PROVIDER`, `REGISTERWERK_WEBHOOK_ALLOW_INSECURE_URLS`, `REGISTERWERK_WALLET_MASTER_KEY`, `LINK_BY_EMAIL_WITHOUT_VERIFICATION`, `SWAGGER_ENABLED` y otras).

## Más ajustes

El calendario del registro, las confirmaciones de cadena, la limitación de inicios de sesión, la negociación, la Travel Rule, los informes, el anclaje de auditoría y la custodia de claves de firma, con sus valores por defecto y lo que espera el modo de producción, están en la [referencia de ajustes operativos](environment-settings.md) (solo en inglés).
