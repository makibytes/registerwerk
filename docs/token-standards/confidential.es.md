---
title: Tokens confidenciales (Zama fhEVM)
description: Tokens ERC-20 y ERC-3643 que preservan la privacidad mediante el cifrado totalmente homomórfico de Zama — el ciclo de vida completo de cifrado/descifrado, de extremo a extremo.
---

# Tokens confidenciales (Zama fhEVM) { #confidential-tokens-zama-fhevm }

Los tokens confidenciales utilizan **cifrado totalmente homomórfico (FHE)** para proteger de la
vista pública los saldos de tokens y los importes de las transferencias, preservando a la vez las
capacidades de cumplimiento y auditoría que exigen los reguladores.

!!! warning "Registerwerk ES el cliente"
    Revisiones anteriores de esta página describían el ciclo de vida de cifrado/descifrado como "el
    problema de otro" — el trabajo del navegador, un servicio complementario que usted mismo debía
    aportar. Ese planteamiento era erróneo: las únicas partes autorizadas a descifrar saldos
    confidenciales — emisores, inversores, el operador del registro y un auditor — actúan todas *a
    través de* Registerwerk. Por eso construir la integración completa de
    `@zama-fhe/relayer-sdk` es responsabilidad propia de Registerwerk, y ya está construida:
    contratos, un sidecar de relayer incluido en el repositorio, servicios de backend e integración
    en el navegador en ambos frontends. Véase la [matriz de estado](#status) más abajo para saber
    exactamente qué es real y qué todavía necesita una red activa para ejercitarse.


---

## Estándares confidenciales admitidos { #supported-confidential-standards }

| Estándar | Basado en | Estado cifrado |
|---|---|---|
| `CONF_ERC20` | Token fungible confidencial ERC-7984 | Saldos/asignaciones `euint64` |
| `CONF_ERC3643` | ERC-3643 (T-REX) + ERC-7984 | Saldos `euint64` + identidad/cumplimiento en texto plano |

Contratos: `contracts/src/confidential/ConfidentialERC20.sol` / `ConfidentialERC3643.sol`,
implementados mediante `contracts/src/factory/EwpgConfidentialFactory.sol`.

---

## Qué cadenas ejecutan esto realmente { #which-chains-actually-run-this }

El coprocesador fhEVM de Zama se ejecuta en **Ethereum y Base** (según el propio anuncio de
producto "fhEVM Coprocessor" de Zama) — más **Sepolia hoy** como testnet totalmente configurada
(las direcciones reales de ACL/Executor/Payment/KMSVerifier/Gateway están incluidas (vendored) en
`contracts/lib/fhevm/config/`, y esas mismas direcciones reales de Sepolia se incluyen en
`@zama-fhe/relayer-sdk` como `SepoliaConfig`). Las direcciones propias de Zama para la **mainnet**
de Ethereum aún se estaban finalizando en el momento de escribir esto (objetivo: tercer trimestre
de 2026) y son actualizables por gobernanza incluso una vez activas.

**Fhenix e Inco NO son cadenas de Zama fhEVM.** Ejecutan sus propias pilas FHE, independientes e
incompatibles con la de Zama. `ConfidentialERC20`/`ConfidentialERC3643` están construidos
específicamente contra la API `TFHE.sol`/Gateway de Zama y no funcionarán en ninguna de las dos.

**T-REX Chain**: T-REX Network anunció en marzo de 2026 que Zama se está convirtiendo en la capa
de confidencialidad del T-REX Ledger — directamente relevante para `CONF_ERC3643`, que ya combina
la identidad/cumplimiento de T-REX con los saldos FHE de Zama. T-REX Chain todavía no está
representada como su propio valor de la enumeración `Chain` en este backend, y (públicamente) aún
no ha compartido sus propias direcciones de infraestructura FHEVM. Confírmelas antes de confiar en
esta combinación en producción.

Las direcciones de infraestructura FHEVM nunca se codifican de forma fija por red en los
contratos — se inyectan en el momento de la construcción/configuración de la fábrica
(`ConfidentialERC20.FhevmInfra`, `EwpgConfidentialFactory.setFhevmInfra`), precisamente para que
una red nueva (mainnet, T-REX Chain) pueda incorporarse configurando direcciones reales, sin
necesidad de volver a desplegar el contrato.

---

## Quién puede descifrar qué — el modelo de ACL de visor { #who-can-decrypt-what-the-viewer-acl-model }

Las concesiones de ACL de Zama son aditivas y por identificador de texto cifrado (handle): una vez
que una dirección recibe `allow` sobre un handle, esa concesión es permanente para ese handle en
concreto (no existe revocación — véase el comentario de documentación de
`ConfidentialERC20.removeViewer`). Registerwerk usa esa primitiva para lograr exactamente el
aislamiento que exige la plataforma, dentro de un **único contrato confidencial por activo** (no un
contrato por inversor — véase más abajo):

- **A cada titular se le conceden derechos de descifrado únicamente sobre su PROPIO handle de
  saldo**, cada vez que este muta (acuñación/transferencia/destrucción). Un inversor nunca puede
  descifrar el saldo de otro inversor, porque nunca recibe `allow` sobre ese otro handle.
- **Un pequeño conjunto de "visores"** — el operador del registro, un auditor y (añadido tras el
  despliegue mediante `addViewer`) el propio monedero del emisor, si así se desea — recibe
  derechos de descifrado sobre **todos** los handles (saldo y suministro total), satisfaciendo así
  que "el operador necesita poder descifrar todos los importes de todos los inversores, y el rol
  de auditor necesita poder descifrar los importes".
- Los visores se aprovisionan como `initialViewers` en el momento del despliegue
  (`EwpgConfidentialFactory.deployConfidentialErc20/deployConfidentialErc3643`, tomados de
  `registerwerk.contracts.confidential-operator-viewer.*` / `.confidential-auditor-viewer.*`), o
  se añaden/eliminan más tarde mediante `TokenAdminService.confidentialAddViewer`/
  `confidentialRemoveViewer` (`POST .../admin/confidential-add-viewer` / `-remove-viewer`).

Por qué un solo contrato con una ACL, y no un contrato por inversor: la misma garantía de
aislamiento, al coste normal de despliegue/gas, sin la complejidad de reconciliar el suministro
por inversor.

---

## Qué hacen realmente los contratos { #what-the-contracts-actually-do }

- `confidentialTransfer` / `confidentialTransferFrom` / `confidentialApprove` — transferencia/
  asignación cifrada según ERC-7984, con semántica de fallo silencioso basada en `TFHE.select`
  cuando el saldo es insuficiente (coincide con la convención de ERC-7984, no es un fallo).
- `confidentialMint` / `confidentialBurn` — restringidos a propietario/agente, y conceden al
  conjunto de visores (arriba) acceso sobre cada handle mutado. En `ConfidentialERC3643`,
  `confidentialBurn` es también la primitiva de cancelación obligatoria (eWpG §26 Einziehung)
  para importes cifrados.
- `ConfidentialERC3643` aplica además, antes de cualquier transferencia, la verificación de
  identidad T-REX, la congelación, la pausa y un módulo `IConfidentialCompliance` conectable.
- `requestSupplyDisclosure` / `callbackSupplyDisclosure` — la ruta de **descifrado público/oracle**:
  el propio contrato pide al Gateway de Zama que descifre el suministro total y recibe el texto en
  claro mediante un callback firmado, para una divulgación activada por el regulador — distinto de
  que un titular/visor descifre su propio saldo o el de otro a través del relayer (más abajo).

---

## El ciclo de vida de cifrado/descifrado — quién hace qué { #status }

| Actor | Acción | Cómo | Estado |
|---|---|---|---|
| Inversor | Revelar su propio saldo | Navegador: `FheClientService.userDecrypt` (el monedero conectado firma la solicitud EIP-712 del KMS, descifra directamente contra el relayer de Zama) | ✅ Real — `frontend-customer` |
| Inversor | Transferencia confidencial | Navegador: `FheClientService.encrypt64` del lado del cliente, luego el monedero envía `confidentialTransfer` | ✅ Real — `frontend-customer` |
| Emisor | Acuñación confidencial | El backend cifra del lado del servidor (sin navegador en este flujo) mediante el sidecar `zama-relayer`, y luego envía | ✅ Real — `TokenAdminService.confidentialMint`, `POST .../issuer/mint-confidential` |
| Emisor | Revelar el saldo de cualquier titular | Navegador, como visor registrado (misma ruta `FheClientService.userDecrypt`) | ✅ Real — panel de saldos confidenciales del emisor en `frontend-customer` |
| Operador | Descifrado sin interfaz (headless) para informes/conciliación | Clave de descifrado dedicada del operador en el backend, vía `zama-relayer`, sin monedero | ✅ Real — `ConfidentialBalanceReconciliationService`, `GET .../confidential-reconciliation` |
| Operador / Auditor | Revelar + reconciliar mediante su propio monedero | Navegador: pestaña Confidential Balances de `frontend-operator` (`ConfidentialViewerPanelComponent`) | ✅ Real |
| Operador | Destrucción forzada confidencial (§26 Einziehung) | El backend cifra del lado del servidor mediante `zama-relayer`, y luego envía | ✅ Real — `TokenAdminService.confidentialForceBurn`, `POST .../force-burn-confidential` |
| Regulador | Divulgación pública/oracle del suministro total | En cadena: `requestSupplyDisclosure`/`callbackSupplyDisclosure` | ✅ Real, probado con Foundry |
| Congelación/pausa/transferencia forzosa confidencial de ERC-3643 vía la API del operador | — | `Erc3643Controller` apunta al ABI en texto plano de `EwpgERC3643`; llamarlo contra `ConfidentialERC3643` envía calldata no coincidente | ❌ No conectado — hoy solo la destrucción forzada tiene una ruta específica para confidenciales |
| Vía de pago confidencial (importes de stablecoin cifrados en la pata de efectivo de la DvP) | — | — | ❌ No construido |

**Lo que realmente no está verificado aquí**: este entorno de pruebas no tiene Docker/Kong activos
ni ninguna cuenta de Sepolia con fondos con la que enviar transacciones reales, así que el viaje de
ida y vuelta completo en cadena (enviar → minar → descifrar) no se ha ejecutado de extremo a
extremo en este entorno. Lo que **sí** se ha verificado contra la infraestructura real y en vivo de
Sepolia de Zama durante el desarrollo: el endpoint `/v1/encrypt-input` de `zama-relayer` produjo un
handle de texto cifrado genuino y una prueba de entrada ZK a partir de una conexión `createInstance`
real contra el relayer real de Zama (`https://relayer.testnet.zama.org`) y una RPC pública de
Sepolia — no una simulación. Todos los componentes aquí descritos están construidos, probados con
pruebas unitarias/Foundry y (donde se ha comprobado) verificados contra la red real a nivel de cada
llamada individual; solo falta que la transacción completa de varios pasos se ejecute de principio
a fin, para lo cual haría falta una cuenta con fondos y un activo desplegado.

---

## Desplegar un activo confidencial { #deploying-a-confidential-asset }

1. Despliegue `EwpgConfidentialFactory` en una cadena con las direcciones reales de Zama FHEVM
   configuradas (hoy, Sepolia), o configure una fábrica ya existente mediante `setFhevmInfra`.
2. Para `CONF_ERC3643`, aprovisione un `IdentityRegistry` de T-REX compartido para activos
   confidenciales en esa cadena y fije
   `registerwerk.contracts.confidential-identity-registry.<chain>` — desplegar con un registro de
   identidad sin configurar/en dirección cero falla de forma explícita (`EwpgConfidentialFactory`
   revierte).
3. Fije `registerwerk.contracts.confidential-factory.<chain>` a la dirección de la fábrica
   desplegada, y `registerwerk.contracts.confidential-operator-viewer.<chain>` /
   `.confidential-auditor-viewer.<chain>` a las direcciones de visor dedicadas de solo descifrado
   del operador/auditor (véase [EVM confidencial](../blockchains/confidential-evm.md)).
4. Despliegue `zama-relayer` (`docker compose --profile confidential up`) con
   `OPERATOR_DECRYPT_PRIVATE_KEY` fijada a la clave privada correspondiente a la dirección de
   operador-visor anterior, y apunte el backend a ella mediante
   `registerwerk.zama.relayer-url`.
5. Emita el activo como `CONF_ERC20`/`CONF_ERC3643` — el despliegue está restringido a cadenas
   reales del coprocesador de Zama (`Chain.ETHEREUM`, `Chain.BASE`), no a Fhenix/Inco.

Véase [EVM confidencial](../blockchains/confidential-evm.md) para el detalle de configuración por
cadena, y [Operador: Tokens confidenciales](../operator/blockchain/confidential-tokens.md) para el
flujo de trabajo diario del operador.
