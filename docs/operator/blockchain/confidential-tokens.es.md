---
title: Tokens confidenciales (Zama fhEVM)
---

# Configuración de token confidencial (Zama fhEVM) { #confidential-token-setup-zama-fhevm }

Esta guía cubre la implementación y administración de tokens confidenciales ERC-20/ERC-3643 utilizando Zama
fhEVM.

## Requisitos previos { #prerequisites }

1. Una cadena con **infraestructura real de Zama fhEVM**: Ethereum Sepolia hoy (las direcciones documentadas
están incluidas como dependencia vendorizada en `contracts/lib/fhevm/config/` y se incluyen en `@zama-fhe/relayer-sdk` como
`SepoliaConfig`), o la red principal Ethereum/Base una vez que Zama publica las direcciones finales allí.
La implementación confidencial está restringida a `Chain.ETHEREUM`/`Chain.BASE` — **no** Fhenix/Inco.
2. `EwpgConfidentialFactory` implementado y configurado con las direcciones FHEVM reales de esa cadena
(`setFhevmInfra`); consulte `docs/blockchains/confidential-evm.md` en el repositorio.
3. Solo para `CONF_ERC3643`: un `IdentityRegistry` T-REX real aprovisionado para activos confidenciales en esa cadena, configurado a través de `registerwerk.contracts.confidential-identity-registry.<chain>`. La implementación falla estrepitosamente si no está configurado.
4. Las direcciones de visor dedicadas solo para descifrado del operador y del auditor configuradas a través de
`registerwerk.contracts.confidential-operator-viewer.<chain>` /
`.confidential-auditor-viewer.<chain>`: se convierten en visores en cada token confidencial
implementado en esa cadena desde el bloque uno.
5. `zama-relayer` ejecutándose (`docker compose --profile confidential up`) con
`OPERATOR_DECRYPT_PRIVATE_KEY` configurado con la clave privada que coincide con la dirección del operador-visor
anterior, y el `registerwerk.zama.relayer-url` del backend apuntando a ella.

## Implementación { #deploying }

Flujo de implementación de activos estándar, igual que cualquier otro estándar:

```bash
curl -X POST http://localhost:8080/api/v1/assets/{assetId}/deploy \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  -d '{ "chain": "ETHEREUM", "network": "TESTNET" }'
```

El backend enruta `CONF_ERC20`/`CONF_ERC3643` a `ConfidentialErc20Service`/
`ConfidentialErc3643Service`, que llama a `EwpgConfidentialFactory.deployConfidentialErc20`/
`deployConfidentialErc3643`: transacciones Web3j reales, pasando las direcciones de visor del operador/auditor
configuradas como `initialViewers`.

## Acciones del operador disponibles hoy { #operator-actions-available-today }

| Acción | Punto final | Notas |
|---|---|---|
| Acuñación confidencial (emisión del emisor/operador) | `POST /api/v1/assets/{id}/deployments/{depId}/issuer/mint-confidential` | Cifra la cantidad del lado del servidor a través del sidecar `zama-relayer`: no se necesita navegador ni monedero |
| Quema forzada confidencial (§26 Einziehung) | `POST .../admin/force-burn-confidential` | Misma ruta de cifrado del lado del servidor; ya está controlado por agente/propietario: ese control ES la autoridad de quema forzada |
| Añadir un visor confidencial | `POST .../admin/confidential-add-viewer` | Otorga derechos de descifrado sobre el saldo de cada titular en el futuro, p. ej. agregar un auditor o el propio monedero del emisor después de la implementación |
| Eliminar un visor confidencial | `POST .../admin/confidential-remove-viewer` | Detiene concesiones futuras — NO revoca retroactivamente los identificadores históricos ya descifrables (el ACL de Zama no tiene primitiva de revocación) |
| Conciliación de registro versus en cadena | `GET /api/v1/assets/{id}/confidential-reconciliation` | Automatizado (headless): descifra el saldo en cadena de cada titular a través de la clave de descifrado del operador del backend y lo compara con el `nominalAmount` en texto plano del registro. Rol `REGISTRY_ADMIN` o `AUDIT`. |
| Revelar + conciliar a través de su propio monedero | Portal del operador → Activo → pestaña **Saldos confidenciales** | Conecte un monedero de visor en el navegador y descifre directamente contra el retransmisor de Zama: una verificación cruzada independiente de la reconciliación automatizada anterior |
| Divulgación de suministro público/oráculo | `ConfidentialERC20.requestSupplyDisclosure()` (llamada en cadena; ningún punto final API del operador la completa todavía) | Para una divulgación agregada activada por el regulador, no el saldo de un titular específico |

Congelación/pausa/transferencia forzada en `CONF_ERC3643` **aún no están conectadas** a través de la API del operador —
el controlador de administración ERC-3643 existente apunta a la ABI del contrato `EwpgERC3643` en texto plano, que
no coincide con las firmas de cantidad cifrada de `ConfidentialERC3643`.

## El sidecar del retransmisor { #the-relayer-sidecar }

`zama-relayer` (raíz del repositorio `zama-relayer/`) es el propio servicio de Registerwerk que envuelve el real
`@zama-fhe/relayer-sdk`: construido y enviado en este monorepo, no es algo que necesite escribir.
Zama no publica ningún cliente Java/JVM, que es la única razón por la que existe este sidecar; cada acción confidencial iniciada por el navegador
(un inversor/emisor/auditor que revela un saldo, una transferencia confidencial
de un inversor) habla con el retransmisor de Zama directamente desde el navegador y nunca toca este sidecar.
Habilítelo con:

```bash
docker compose --profile confidential up
```

Consulte su sección `.env.example` ("Tokens confidenciales (Zama fhEVM)") para conocer las variables de entorno:
`ZAMA_CONFIG_PRESET=sepolia`, `ZAMA_OPERATOR_DECRYPT_PRIVATE_KEY` y
`REGISTERWERK_ZAMA_RELAYER_URL` en el lado backend.

## Saldo de inversor/emisor/auditor descifrado { #investorissuerauditor-balance-decryption }

Revelar un saldo confidencial (o cifrar un monto de transferencia confidencial) es una operación **del lado del cliente**
en ambas interfaces: el monedero conectado firma una solicitud EIP-712 y la propia instancia
`@zama-fhe/relayer-sdk` del navegador se comunica directamente con el retransmisor de Zama — ver `FheClientService` en
`frontend-customer` (autorevelación del inversor + transferencia confidencial; emisor revela a todos los tenedores) y
`frontend-operator` (operador/auditor `ConfidentialViewerPanelComponent`). Nada de esto enruta
a través de este backend.
