---
title: Requisitos previos
---

# Requisitos previos { #prerequisites }

## Requisitos del servidor { #server-requirements }

| Componente | Mínimo | Recomendado |
|---|---|---|
| CPU | 2 núcleos | 4+ núcleos |
| RAM | 4 GB | 16 GB (graph-node necesita ~4 GB) |
| Disco | 50 GB SSD | NVMe de 200 GB |
| SO | Ubuntu 22.04+ | Ubuntu 24.04 LTS |

## Software { #software }

| Herramienta | Versión | Notas |
|---|---|---|
| Docker Engine | 25+ | |
| Docker Compose | v2.24+ | Complemento, no independiente |
| Java JDK | 25 | Eclipse Temurin recomendado |
| Node.js | 22 LTS | Para compilaciones de frontend |
| Foundry | Última estable | `curl -L foundry.paradigm.xyz \| bash` |
| PostgreSQL | 17 | Proporcionado a través de Docker; externo también compatible |

## Servicios externos { #external-services }

- **proveedor OAuth2 / OIDC**: Microsoft Entra ID o Keycloak autoadministrado
- **servidor SMTP**: para correos electrónicos de incorporación y notificación de KYC
- **almacenamiento compatible con S3**: para documentos KYC ≥5 MB (AWS S3, MinIO, Cloudflare R2)
- **puntos finales RPC de EVM**: por cadena (Infura, Alchemy, QuickNode o autohospedado)
- **Solana RPC** — con soporte Geyser/Yellowstone (Helius, Triton o validador autohospedado)

## Puertos de red { #network-ports }

| Servicio | Puerto | Acceso |
|---|---|---|
| Interfaz del operador | 44200 | Público: abierto directamente, nunca a través de Kong |
| Interfaz del cliente | 44201 | Público: abierto directamente; solo sus propias llamadas a la API se enrutan a través de Kong |
| Proxy de Kong | 48000 / 48443 | Público: tráfico HTTP/HTTPS de la API del cliente, sin base de datos, sin GUI de administración |
| API de administración de Kong | 48001 | Solo loopback: túnel `docker exec`/SSH, nunca exponer públicamente |
| Documentación (perfil opcional `docs`) | 48003 | Dirección de enlace configurable |
| Chaincache Sepolia/Base (opcional) | 48090 / 48091 | Solo loopback |
| Anvil desechable | 48545 | Dirección de enlace configurable; internamente sigue siendo `anvil:8545` |
| zama-relayer (opcional, `--profile confidential`) | 43005 | Solo interno |
| Backend (directo) | 48080 | Solo interno (lo llaman tanto el frontend del operador como, en producción, Kong) |
| GraphQL de graph-node | 8000 | Solo interno |
| Administración de graph-node | 8020 | Solo interno |
| PostgreSQL | 45432 | Solo loopback; internamente `postgres:5432` |
