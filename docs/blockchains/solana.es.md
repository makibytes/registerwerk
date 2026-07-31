---
title: Solana
description: Soporte de blockchain de Solana: programas de tokens SPL y SPL-2022 para valores nativos de Solana.
---

# Solana { #solana }

Solana ofrece un alto rendimiento (más de 50 000 TPS), una finalidad en menos de un segundo y costos de transacción muy bajos. Registerwerk admite tokens de seguridad nativos de Solana a través del programa clásico **SPL Token** y el programa extendido **Token-2022** (SPL-2022).

---

## Redes compatibles { #supported-networks }

| Red | Enumeración de red | Punto final | Uso |
|---|---|---|---|
| Solana mainnet-beta | `MAINNET` | `https://api.mainnet-beta.solana.com` | Producción |
| Solana devnet | `TESTNET` | `https://api.devnet.solana.com` | Desarrollo/pruebas |

---

## Biblioteca cliente: Solanaj { #client-library-solanaj }

Registerwerk utiliza **Solanaj** (biblioteca cliente Java para Solana) a través de `SolanaClientFactory`. Operaciones clave:

| Operación | Solanaj API | Utilizado en |
|---|---|---|
| Crear cuenta mint | `MintLayout.encode()` + `SystemProgram.createAccount()` | `SolanaTokenService.deploy()` |
| Acuñar tokens | Instrucción del programa de token `mintTo` | `SolanaTokenService.mint()` |
| Transferir | Instrucción del programa de token `transfer` | `SolanaTokenService.transfer()` |
| Establecer autoridad | Instrucción del programa de token `setAuthority` | Operaciones de administración |
| Obtener saldo | `rpcClient.getTokenAccountBalance()` | Indexador, saldo de monedero |

---

## Modelo de cuenta de token { #token-account-model }

El modelo de token de Solana difiere significativamente de EVM:

- Una **cuenta mint** define el token (equivalente a la dirección de un contrato ERC-20)
- Cada titular necesita una **cuenta de token** separada (cuenta de token asociada, ATA) para mantener el token
- El flujo de implementación de Registerwerk crea automáticamente ATA para los monederos del operador
- Los ATA de los inversores se crean en la primera recepción

`AssetDeployment.contractAddress` almacena la **dirección mint** de Solana (clave pública codificada en base58).

---

## Extensiones SPL-2022 { #spl-2022-extensions }

Para una cobertura detallada de las extensiones de Token-2022 (InterestBearing, ConfidentialTransfer, TransferHook, PermanentDelegate), consulte [SPL-2022](../token-standards/spl-2022.md).

---

## Indexador { #indexer }

El indexador de Solana escucha las transacciones en cuentas mint rastreadas mediante suscripciones WebSocket (a través de las API mejoradas de Helius o Shyft). En cada transacción confirmada:

1. Analice el registro de transacciones para obtener instrucciones de transferencia de tokens
2. Asigne desde/hacia cuentas de Solana a registros `LegalEntity`
3. Escriba un registro `token_transfer` (esquema coherente con el indexador EVM)
4. Actualice `AssetHolder.nominalAmount`

El `IndexerMonitorService` comprueba la liveness del indexador de Solana cada 5 minutos. Si no se recibe ningún evento durante más de 30 minutos en un activo activo, se abre un incidente `DORA_AVAILABILITY`.

---

## El monedero del operador en Solana { #operator-wallet-on-solana }

El monedero de Solana de Registerwerk es un par de claves estándar **ed25519**. La clave privada se almacena cifrada en la bóveda del monedero del operador (el mismo sobre KMS/KEK que los almacenes de claves EVM). El monedero del operador es la autoridad de acuñación y congelación de todos los tokens SPL-2022.

!!! warning "Saldo de SOL para el alquiler"
    Las cuentas de Solana requieren **alquiler** (saldo mínimo SOL) para permanecer abiertas. Las cuentas de token abiertas por el servicio de implementación requieren un pequeño depósito SOL. El `WalletBalanceService` monitorea el saldo SOL del operador y avisa cuando cae por debajo de 0,5 SOL.
