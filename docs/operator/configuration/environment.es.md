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

## Autenticación { #authentication }

### Administrador integrado (modo sin IdP) { #built-in-admin-no-idp-mode }

| Variables | Predeterminado | Descripción |
|---|---|---|
| `ENTRA_ENABLED` | `false` | `false` → formulario de nombre de usuario/contraseña en el operador FE; `true` → Botón Microsoft |
| `DEFAULT_ADMIN_EMAIL` | — | Correo electrónico del usuario administrador inicializado (solo modo integrado) |
| `DEFAULT_ADMIN_PASSWORD` | — | Contraseña de texto sin formato codificada con BCrypt al inicio; rotar cambiando y reiniciando |
| `JWT_DEV_SECRET` | incorporado | Clave de firma HS256 utilizada en modo de desarrollo/demo; dejar sin configurar para local, anular en preparación |

### OAuth2 / OIDC (producción) { #oauth2-oidc-production }

| Variables | Descripción |
|---|---|
| `JWT_ISSUER_URI` | URL del emisor OIDC: déjelo en blanco para el modo de desarrollo HS256; configúrelo para producción (p. ej. `https://login.microsoftonline.com/<tenant>/v2.0`) |
| `ENTRA_CLIENT_ID` | ID de cliente OIDC utilizado por el complemento Kong |
| `ENTRA_CLIENT_SECRET` | Secreto de cliente OIDC utilizado por el complemento Kong |
| `ENTRA_ISSUER` | Emisor OIDC configurado en el complemento Kong |

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
