---
title: Abstracción de cuenta y transacciones patrocinadas
description: ERC-4337 / EIP-7702 cuentas inteligentes, gas patrocinado, claves de acceso y permisos sin gas.
---

# Abstracción de cuenta y transacciones patrocinadas { #account-abstraction-sponsored-transactions }

Registerwerk admite transacciones patrocinadas ERC-4337, cuentas delegadas EIP-7702, verificación
de monederos ERC-1271 y una cuenta de clave de acceso en cadena. Estas funciones son independientes
de la [interoperabilidad DeFi](./defi-interoperability.md).

## Fundamento: `WalletSignatureVerifier` { #foundation-walletsignatureverifier }

`WalletSignatureVerifier` (`orgidentity/api/WalletSignatureVerifier.java`, que sirve de base a
`orgidentity/internal/MemberWalletService` y `marketplace/internal/ManifestSigningService`)
verifica las firmas mediante **recuperación ECDSA** (EOA simples) **o** `isValidSignature` de
ERC-1271 (monederos de contrato inteligente), según el código en cadena de la dirección indicada.
Este es el requisito previo para todo lo que sigue: sin él, una cuenta inteligente nunca podría
vincularse como monedero de miembro ni firmar un manifiesto de mercado.

## EIP-7702: la rampa de acceso a la cuenta inteligente { #eip-7702-the-smart-account-on-ramp }

EIP-7702 (activo desde la actualización de Pectra) permite que un EOA existente delegue su código
a una implementación de cuenta inteligente **manteniendo exactamente la misma dirección**. Esta es
la rampa de acceso natural para Registerwerk en concreto, porque cada parte del modelo existente se
apoya en una dirección de monedero fija:

- `OrgRegistry._orgOf[wallet]` (`contracts/src/ecosystem/OrgRegistry.sol`): un monedero, una organización, por dirección.
- T-REX `IdentityRegistry.registerIdentity(address, ...)`: identidad/atestaciones registradas por dirección.
- `EwpgCompliance.isWhitelisted(address)`: lista blanca indexada por dirección.

Un cliente que actualiza su EOA existente a una cuenta inteligente delegada por 7702 no necesita
**ninguna migración** de lo anterior: la dirección no cambia, así que la pertenencia a la
organización, el registro de identidad y las entradas en la lista blanca siguen siendo válidos. El
único requisito nuevo es la ruta ERC-1271 de `WalletSignatureVerifier` (ya implementada), puesto
que el código de un EOA delegado por 7702 implementa `isValidSignature` como cualquier otro
monedero de contrato inteligente, incluido `EwpgPasskeyAccount` (más abajo), que es exactamente ese
tipo de implementación delegada.

`frontend-customer` centraliza el acceso al monedero en `WalletService` e implementa la ejecución
opcional EIP-7702/ERC-4337 en `SponsoredTxService`. El patrocinio exige
`environment.bundlerUrl`, una dirección de paymaster y un ID de política resuelto. La interfaz no
crea ni opera instancias de `EwpgPasskeyAccount`.

## `EwpgPaymaster` — transacciones patrocinadas { #ewpgpaymaster-sponsored-transactions }

`contracts/src/ecosystem/EwpgPaymaster.sol` es un `IPaymaster` de ERC-4337 (contra EntryPoint
v0.8, para soporte nativo de EIP-7702) que patrocina el gas de los clientes verificados de
Registerwerk:

- **Controlado por cumplimiento según quién llama, no según qué llama**: `validatePaymasterUserOp`
  comprueba `PermissionOracle.isActiveMember(userOp.sender)` y `hasClaimTopic(userOp.sender, KYC)`
  — nunca patrocina gas para un monedero no verificado. Como un EOA delegado por 7702 conserva su
  dirección original, `userOp.sender` *es* la dirección de monedero de miembro ya existente del
  cliente, de modo que esto se apoya directamente en el mismo oráculo que usa cualquier otro
  contrato del ecosistema. Analizar el `callData` de una cuenta inteligente arbitraria para
  restringir *qué contrato* se llama depende de la implementación de cada cuenta y queda fuera de
  alcance a propósito — véase el NatSpec del contrato.
- **El patrocinio se acota mediante un `policyId` opaco** (codificado en `paymasterAndData`),
  financiado a través de `fundSponsorship(policyId)` por cualquiera que esté dispuesto a
  patrocinar (el operador o la tesorería de un emisor), lo que reserva a la vez un presupuesto
  interno y realiza un depósito en el EntryPoint.
- **Un límite de gasto por monedero** (`setWalletBudgetCap`) acota cuánto de un presupuesto de
  política *compartido* puede consumir un único monedero, además del propio presupuesto agregado
  de la política.
- Respaldado por la entidad `deployment/api/GasSponsorshipPolicy` (backend): refleja el patrón ya
  existente de `MintControlRule` — una anulación por implementación, o un valor predeterminado a
  nivel de emisor que heredan las futuras implementaciones de ese emisor hasta que obtienen su
  propia anulación (`GasSponsorshipService.resolveEffectivePolicy`,
  `asset/web/GasSponsorshipController`). El `policyId` en cadena de una fila dada es
  `keccak256(id.toString())`. Esta capa de backend es solo configuración: todavía no impulsa un
  trabajo de sincronización en cadena que traslade los presupuestos al paymaster
  automáticamente; hoy, un operador o un emisor financia `EwpgPaymaster.fundSponsorship`
  directamente.
- Interfaz de operador: la página de detalle de activo de `frontend-operator` tiene una pestaña
  **Gas Sponsorship** por implementación (para fijar/eliminar una anulación específica de esa
  implementación), y la página de detalle de cliente tiene otra para emisores (para fijar el valor
  predeterminado a nivel de emisor que heredan las nuevas implementaciones) — ambas respaldadas por
  `core/api/gas-sponsorship.service.ts`, que muestra la política vigente en cada momento y si se
  trata de una anulación o de un valor predeterminado heredado.
- Script de implementación: `contracts/script/DeployLiquidityDapps.s.sol` implementa
  `EwpgPaymaster` (con el EntryPoint predeterminado `ERC4337Utils.ENTRYPOINT_V08`) junto con
  `EwpgRepoFacility` — se mantiene separado de `DeployExampleDapps.s.sol` porque ambos usan pragma
  `^0.8.36` y no pueden compartir una unidad de compilación con las importaciones de ese script
  que dependen de erc3643 (fijadas exactamente a `0.8.30`).
- Datos de demostración: `EcosystemDemoDataSeeder` siembra tres filas de `GasSponsorshipPolicy`:
  el valor predeterminado a nivel de emisor propio de Meridian Capital (patrocinador `ISSUER`), el
  valor predeterminado del emisor de Aurora Finance financiado en cambio por el operador
  (patrocinador `OPERATOR`, para mostrar el otro tipo de patrocinador), y una anulación a nivel de
  implementación en el bono verde insignia de Meridian (`OPERATOR`, que muestra la precedencia de
  la anulación sobre el valor predeterminado).
- Pruebas: `contracts/test/ecosystem/EwpgPaymaster.t.sol` (contra un `MockEntryPoint` mínimo —
  véase su NatSpec para entender por qué no hace falta una simulación completa de `handleOps` para
  probar la propia lógica contable del paymaster), `backend/.../unit/GasSponsorshipServiceTest.java`.

## `EwpgPasskeyAccount` — firmantes con clave de acceso para minoristas { #ewpgpasskeyaccount-passkey-signers-for-retail }

`contracts/src/ecosystem/EwpgPasskeyAccount.sol` es una cuenta inteligente mínima de ERC-4337
asegurada mediante una clave de acceso WebAuthn/secp256r1 en lugar de una clave ECDSA gestionada
con frase semilla, y compone tres piezas ya incluidas (vendored) a través de
`contracts/lib/openzeppelin-contracts` (sin ninguna dependencia nueva): `Account` de OZ
(`validateUserOp` de ERC-4337), `SignerWebAuthn` (verificación de firma con clave de acceso) y
`ERC7821` (ejecución por lotes mínima). También implementa ERC-1271, por lo que se vincula como
monedero de miembro de Registerwerk exactamente igual que cualquier otro monedero de contrato
inteligente.

Emparejado con `EwpgPaymaster`, el flujo de un inversor minorista desde la incorporación hasta la
primera suscripción no necesita frase semilla ni token de gas: autenticación biométrica con clave
de acceso más ejecución patrocinada. Nota: `contracts/foundry.toml` ahora habilita el optimizador
de Solidity (`optimizer = true`, `optimizer_runs = 200`, en línea con el valor predeterminado de la
propia biblioteca de OZ incluida) — el análisis de la firma WebAuthn provoca un "stack too deep"
sin él.

Las pruebas (`contracts/test/ecosystem/EwpgPasskeyAccount.t.sol`) construyen aserciones de
autenticación WebAuthn reales usando los cheatcodes P256 nativos de Foundry
(`vm.publicKeyP256`/`vm.signP256`), incluido un ejemplo resuelto de la única trampa no evidente:
`abi.encode(structValue)` añade una palabra de desplazamiento adicional de nivel superior para una
estructura que contiene campos dinámicos, algo que `WebAuthn.tryDecodeAuth` no espera — en su
lugar, codifique los campos de la estructura como argumentos independientes (véase el asistente
`_sign` de la prueba y su comentario en línea).

## Permisos sin gas { #gasless-permits }

`EwpgBondDesk.subscribeWithPermit` consume un `permit` de EIP-2612 firmado en lugar de exigir una
transacción `approve` previa y separada — reduce a la mitad el número de transacciones y encaja de
forma natural con el patrocinio de `EwpgPaymaster` (permit + ejecución patrocinada = experiencia
sin token de gas). `MockStablecoin` ahora implementa `ERC20Permit` para que el ejemplo/las pruebas
puedan ejercitar esto de principio a fin
(`test_subscribeWithPermit_succeedsWithoutPriorApproval` en
`contracts/test/examples/EwpgBondDesk.t.sol`). No todas las vías de pago reales admiten esto: USDC
implementa EIP-2612 de forma nativa; verifique el soporte de AllUnity Euro antes de conectar
`subscribeWithPermit` contra él en producción — la ruta simple `subscribe` sigue disponible en
cualquier caso.

## Formatos de firma { #signature-formats }

La vinculación del monedero y la firma de manifiestos usan `personal_sign`.
`WalletSignatureVerifier` acepta ese formato para EOA y monederos ERC-1271, pero no firmas de
datos tipados EIP-712.
