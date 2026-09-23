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
**ninguna migración** de lo anterior: la dirección no cambia, por lo que la pertenencia a la org, el
registro de identidad y las entradas de la lista blanca siguen siendo válidos. El único requisito
nuevo es la vía ERC-1271 de `WalletSignatureVerifier` (ya implementada), ya que el código de un EOA
delegado por 7702 implementa `isValidSignature` como cualquier otro monedero de contrato
inteligente. El delegado que usa el portal de clientes es `Simple7702Account` de viem.
`EwpgPasskeyAccount` (más abajo) **no** es un delegado 7702: su clave de acceso y su guardián son
estado de cada despliegue, por lo que un EOA que delegara en él no tendría clave de acceso (toda
firma se rechaza) y compartiría un único guardián con todos los demás EOA delegantes.

`frontend-customer` centraliza el acceso al monedero en `WalletService` e implementa la ejecución
opcional EIP-7702/ERC-4337 en `SponsoredTxService`. El patrocinio exige `environment.bundlerUrl` y,
para cada UserOperation, un **vale** (voucher) del backend (siguiente sección). Si el backend
rechaza el vale, el servicio lanza `SponsorshipUnavailableError` con el motivo;
`sendWithSponsorshipFallback` envía entonces la misma llamada como transacción normal pagada por el
usuario e informa de ello. Nunca recurre a la alternativa en silencio. La interfaz no crea ni opera
instancias de `EwpgPasskeyAccount`.

## `EwpgPaymaster` — transacciones patrocinadas { #ewpgpaymaster-sponsored-transactions }

`contracts/src/ecosystem/EwpgPaymaster.sol` es un **paymaster verificador** ERC-4337 (contra
EntryPoint v0.8, para soporte nativo de EIP-7702) que patrocina el gas de los clientes verificados
de Registerwerk.

**Vales.** Una UserOperation solo se patrocina si lleva un vale firmado por el firmante de vales
registrado para la política:

```
paymasterData = policyId (32) ‖ validUntil (6) ‖ validAfter (6) ‖ maxFeePerGasCap (16) ‖ signature (65)
```

La firma es una firma EIP-191 (`personal_sign`) sobre `EwpgPaymaster.getHash(...)`. Ese digest
cubre todos los campos de la UserOperation (sender, nonce, `keccak(initCode)`, `keccak(callData)`,
los límites de gas de la cuenta, los límites de gas del paymaster para verificación y postOp,
`preVerificationGas` y `gasFees`), además de `block.chainid`, la dirección del paymaster, el id de
la política, la ventana de validez y el tope del precio del gas, bajo la etiqueta de dominio
`keccak256("EwpgPaymasterVoucher(v1)")`. Excluye deliberadamente los bytes de la firma: están
dentro de `paymasterAndData`, que forma parte de `userOpHash`, así que un vale no puede firmar
`userOpHash`. Un firmante incorrecto devuelve `SIG_VALIDATION_FAILED` (el EntryPoint informa
`AA34`) y un vale caducado devuelve `AA32`. La validación también revierte si la política está
inactiva o no registrada, si `maxFeePerGas` supera el tope firmado, si `paymasterPostOpGasLimit` es
inferior a 50.000 de gas o si el sender no es un miembro activo con KYC (defensa en profundidad; el
backend también lo comprueba).

**Emisor de vales (backend).** `POST /api/v1/gas-sponsorship/vouchers` (JWT de cliente,
`asset/web/GasSponsorshipVoucherController`, `asset/internal/GasSponsorshipVoucherService`) recibe
el id del despliegue y la UserOperation preparada. Antes de firmar comprueba:

- la política efectiva del despliegue está **activa** en la base de datos. Desactivar una política
  detiene los vales de inmediato, incluso antes de cambiar el indicador on-chain.
- el sender es un **monedero de miembro activo de la entidad jurídica del llamante** en esa cadena.
- el sender **tiene el activo**: dispone de una inscripción activa en el registro (no una fila de
  nominee pool) de la entidad del llamante para el activo del despliegue. Los primeros suscriptores
  sin inscripción no se patrocinan y pagan su propio gas.
- **alcance** (por defecto hasta que producto decida otra cosa): cada llamada del lote
  `execute`/`executeBatch` apunta al contrato del token del despliegue sin valor, y `initCode` está
  vacío o es solo el marcador EIP-7702. Se rechazan los despliegues mediante factory.
- **gas**: `maxFeePerGas` ≤ `registerwerk.paymaster.max-fee-per-gas-cap-wei` (el tope se firma en
  el vale), la suma de los límites de gas ≤ `max-total-gas` y el gas de postOp ≥ 50.000.
- el **tope mensual** de la política (`monthlyCapEth`). Cada vale emitido cuenta con su coste en el
  peor caso (`Σ límites de gas × maxFeePerGas`, el prefund del EntryPoint) y se registra en
  `gas_sponsorship_voucher`, de modo que el tope es efectivo antes de que se liquide ninguna
  operación. Un vale cuenta una sola vez por `(política, sender, nonce de la UserOperation)`:
  volver a pedirlo para el mismo nonce sustituye el vale anterior. Una entidad jurídica puede usar
  como máximo `registerwerk.paymaster.entity-monthly-cap-share` (por defecto 10 %) del tope al
  mes, para que una sola organización no agote el presupuesto de un emisor para los demás
  titulares.

Cada vale es válido durante `voucher-validity-seconds` (por defecto 300) y emite el evento de
auditoría `GAS_SPONSORSHIP_VOUCHER_ISSUED`. La clave de firma de desarrollo es
`registerwerk.paymaster.voucher-signer-key` (`REGISTERWERK_PAYMASTER_VOUCHER_SIGNER_KEY`). Está
envuelta en la abstracción `EvmSigner` del módulo de monederos; en producción pasa a KMS/HSM.
**Nunca** debe ser el monedero de firma de claims (emisor de confianza): una clave de vales puede
gastar presupuesto de patrocinio. Vacía, desactiva el patrocinio. Las direcciones del paymaster se
configuran por cadena en `registerwerk.paymaster.addresses` (`PAYMASTER_<CHAIN>_<NETWORK>`). El
`policyId` on-chain de una fila `GasSponsorshipPolicy` es `keccak256(id.toString())`.

**Contabilidad del presupuesto.**

- `registerPolicy(policyId, signer, orgCap)` registra al llamante como **financiador** (funder) de
  la política, el firmante de vales y un tope por org distinto de cero. Puede financiar la política
  en la misma llamada.
- `fundSponsorship(policyId)` recarga. Solo el financiador puede llamarla, y solo él puede rotar el
  firmante (`setPolicySigner`), porque un firmante puede gastar la política.
- La validación **reserva** el `maxCost` de la operación del saldo de la política, de modo que
  varias operaciones de un mismo bundle no pueden validarse todas contra el mismo saldo. También
  comprueba el tope de la org del sender frente a gastado + reservado + `maxCost`. El tope se asocia
  a `orgOf(sender)`, así que monederos nuevos de la misma org no lo multiplican.
- `postOp` nunca revierte. Contabiliza `min(maxCost, actualGasCost + (postOpGasLimit + 10.000) ×
  feePerGas)` y devuelve el resto de la reserva. EntryPoint v0.7/v0.8 entregan a `postOp` el coste
  *antes* de sumar el gas propio de postOp y la penalización por gas no usado; contabilizar solo
  `actualGasCost` haría que los libros superasen con el tiempo el depósito real. El importe
  contabilizado es una cota superior; la pequeña diferencia queda en el depósito como excedente.
- `depositSurplus()` = depósito en el EntryPoint − (Σ saldos + Σ reservas). Nunca debe ser
  negativo. Supervísese como `paymaster_deposit_minus_booked_wei` con alerta por debajo de 0.

**Propiedad y controles (on-chain).**

- `setPolicyActive(policyId, bool)` es el interruptor de emergencia on-chain. Pueden llamarlo el
  financiador o un titular de `paymaster.configure`.
- `withdrawPolicy(policyId, amount)` devuelve presupuesto no reservado del depósito del EntryPoint.
  El financiador o un titular de `paymaster.configure` pueden activarla, pero **siempre paga al
  financiador registrado**. Nadie puede redirigirla.
- `addStake(unstakeDelaySec)` (requiere `paymaster.configure`), `unlockStake()` y
  `withdrawStake()` gestionan el stake en el EntryPoint. El primer staker queda registrado como
  `stakeFunder` y el stake siempre se devuelve a esa dirección. Solo `stakeFunder` puede llamar a
  `unlockStake()`: sin stake, los bundlers públicos descartan el paymaster, por lo que otro titular
  de `paymaster.configure` no puede iniciar el unstake.

**Interfaz del operador.** La página de detalle de activo de `frontend-operator` tiene una pestaña
**Gas Sponsorship** por despliegue (definir/eliminar una excepción propia del despliegue). La
página de detalle de cliente tiene una para emisores (definir el valor por defecto del emisor que
heredan los nuevos despliegues). Ambas usan `core/api/gas-sponsorship.service.ts`. La pestaña del
activo muestra además el estado on-chain de la política: indicador de actividad, saldo disponible
y reservado, tope por org, financiador y firmante de vales
(`GET /assets/{id}/deployments/{depId}/gas-sponsorship/onchain`). Avisa cuando una política está
desactivada en la base de datos pero sigue activa on-chain. `GET /gas-sponsorship/voucher-signer`
devuelve la dirección que cada política debe registrar como firmante.

- Script de despliegue: `contracts/script/DeployLiquidityDapps.s.sol` despliega `EwpgPaymaster` con
  el EntryPoint `ERC4337Utils.ENTRYPOINT_V08` junto a `EwpgRepoFacility`.
- Datos de demostración: `EcosystemDemoDataSeeder` crea tres filas `GasSponsorshipPolicy`: el valor
  por defecto propio de Meridian Capital (patrocinador `ISSUER`), el valor por defecto de Aurora
  Finance financiado en cambio por el operador (patrocinador `OPERATOR`, para mostrar el otro tipo)
  y una excepción a nivel de despliegue en el Green Bond insignia de Meridian (`OPERATOR`, que
  muestra la precedencia de la excepción sobre el valor por defecto).
- Pruebas: `contracts/test/ecosystem/EwpgPaymaster.t.sol` ejecuta cada ruta patrocinada a través de
  `handleOps` del **EntryPoint v0.8.0 real** (incluido solo para pruebas en
  `contracts/test/aa-v08/`), con pruebas de regresión para los escenarios de vaciado citados en el
  despliegue más abajo. `backend/.../asset/internal/GasSponsorshipVoucherServiceTest.java` y
  `unit/GasSponsorshipVoucherDigestTest.java` fijan el digest de Java al de Solidity con un vector de
  prueba compartido.

### Stake, despliegue y retirada del paymaster anterior { #paymaster-operations }

La validación escribe almacenamiento (reservas) y lee otros contratos (`PermissionOracle`), por lo
que, según ERC-7562, los bundlers públicos solo aceptan el paymaster si tiene **stake**. Por cadena:

| Cadena | Stake sugerido | Retardo de unstake |
|---|---|---|
| Ethereum mainnet | ≥ 1 ETH | ≥ 1 día (86.400 s) |
| L2 (Base, Arbitrum, Optimism, Polygon) | mínimo del bundler (normalmente 0,1–1 del token nativo) | ≥ 1 día |
| Testnets | mínimo del bundler | ≥ 1 día |

Consulte el mínimo publicado por el proveedor del bundler antes de hacer stake. Un stake con un
retardo inferior al que exige el bundler se trata como sin stake.

Despliegue:

1. El paymaster desplegado antes de este cambio (HEAD `b810acb` y anteriores) es inmutable e
   inseguro: cualquier miembro podía gastar cualquier política, el precio del gas no tenía límite y
   un bundle podía gastar de más. **Deje de financiarlo ya.** No tiene función de retirada, así que
   ninguna opción de financiación apunta ya a él.
2. Despliegue el nuevo `EwpgPaymaster`. Llame a `addStake` desde el monedero del operador y
   configure `registerwerk.paymaster.addresses.<chain>` y la clave del firmante de vales.
3. Cada financiador llama a `registerPolicy(keccak256(policyRowId), voucherSigner, orgCap)` con el
   presupuesto.
4. Registre por cadena el ETH que quede en el paymaster antiguo (`EntryPoint.balanceOf(old)`) como
   **saldo varado**. Solo puede consumirse mediante operaciones patrocinadas, lo que no debe hacerse
   dados los defectos anteriores.

Limitación conocida: `registerPolicy` asigna un id de política al primero que lo registra. Quien se
adelante al registro (front-running) puede bloquear ese id, aunque no puede tomar fondos. El
financiador registra entonces la política con un nuevo id de fila.

## `EwpgPasskeyAccount` — firmantes con clave de acceso para minoristas { #ewpgpasskeyaccount-passkey-signers-for-retail }

`contracts/src/ecosystem/EwpgPasskeyAccount.sol` es una cuenta inteligente ERC-4337 mínima protegida
por una clave de acceso WebAuthn/secp256r1 en lugar de una clave ECDSA gestionada con frase semilla.
Combina tres piezas ya incluidas mediante `contracts/lib/openzeppelin-contracts` (sin nuevas
dependencias): `Account` de OZ (`validateUserOp` de ERC-4337), `SignerWebAuthn` (verificación de
firmas con clave de acceso) y `ERC7821` (ejecución por lotes mínima). También implementa ERC-1271,
de modo que se vincula como monedero de miembro de Registerwerk igual que cualquier otro monedero de
contrato inteligente. Se despliega como cuenta propia para cada cliente y **no es un delegado
EIP-7702**: sin clave de acceso en el almacenamiento propio de la cuenta, toda verificación de firma
falla.

El guardián es un argumento explícito del constructor, nunca el desplegador por accidente. Las
llamadas pueden clasificarse como rutinarias, de administración o de recuperación según destino y
selector. Los lotes de EntryPoint/ERC-7821 rechazan las operaciones de administración y
recuperación, de modo que una clave de sesión comprometida o una UserOperation patrocinada no pueden
ejecutarlas. `guardianExecute` es una **anulación custodial completa**, no una vía solo protectora:
el guardián puede hacer cualquier llamada desde la cuenta sin timelock ni cofirma de la clave de
acceso, y fija él mismo la tabla de roles. Si se desea un guardián en manos del registro con control
unilateral sobre cuentas minoristas es una decisión de custodia abierta (licencia y divulgación).
Hasta que se decida, trate la clave del guardián como custodia de los activos de la cuenta.

Junto con `EwpgPaymaster`, el recorrido de un inversor minorista desde el alta hasta su primera
suscripción no requiere frase semilla ni token de gas: autenticación biométrica con clave de acceso
más ejecución patrocinada. Nota: `contracts/foundry.toml` activa ahora el optimizador de Solidity
(`optimizer = true`, `optimizer_runs = 200`, igual que el valor por defecto de la biblioteca OZ
incluida); sin él, el análisis de firmas WebAuthn produce «stack too deep».

Las pruebas (`contracts/test/ecosystem/EwpgPasskeyAccount.t.sol`) construyen aserciones reales de
autenticación WebAuthn con los cheatcodes P256 nativos de Foundry (`vm.publicKeyP256`/`vm.signP256`),
incluido un ejemplo resuelto del único escollo no evidente: `abi.encode(structValue)` añade una
palabra de desplazamiento adicional de primer nivel para un struct con campos dinámicos, que
`WebAuthn.tryDecodeAuth` no espera; codifique los campos del struct como argumentos separados (véase
el helper `_sign` de la prueba y su comentario). `test_eip7702DelegateHasNoSignerAndFailsClosed`
muestra que un EOA que delega en una instancia no tiene firmante y no puede ser controlado por quien
desplegó la instancia.

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
