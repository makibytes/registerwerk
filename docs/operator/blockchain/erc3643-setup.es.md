---
title: Configuración de ERC-3643
---

# Configuración de ERC-3643 (T-REX) { #erc-3643-t-rex-setup }

Esta guía recorre la configuración completa de la infraestructura ERC-3643 T-REX, desde la implementación del contrato hasta la emisión de atestaciones KYC a inversores.

## Qué se implementa { #what-gets-deployed }

Para cada emisión de ERC-3643, la fábrica implementa seis contratos:

| Contrato | Rol |
|----------|------|
| `Token` | El token ERC-3643 (contrato principal, interfaz compatible con ERC-20) |
| `IdentityRegistry` | Asigna carteras de inversores a su ONCHAINID |
| `IdentityRegistryStorage` | Almacenamiento actualizable para el registro de identidad |
| `ClaimTopicsRegistry` | Define los ID de tema de atestación requeridos (por ejemplo, KYC=1, AML=2) |
| `TrustedIssuersRegistry` | Define qué emisores de identidad pueden firmar atestaciones |
| `ModularCompliance` | Contenedor para módulos de reglas de cumplimiento conectables |

Los seis son implementados atómicamente por `EwpgTREXFactory` a través de `AssetTokenFactory`.

## Paso 1: implementar la suite de fábrica { #step-1-deploy-the-factory-suite }

Asegúrese de que `AssetTokenFactory` y `EwpgTREXFactory` se implementan según [Implementación de contratos](./deploying-contracts.md). Confirme que la dirección de fábrica esté configurada en `.env` y que el backend la haya cargado:

```bash
curl http://localhost:48080/api/v1/admin/chains/11155111 \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  | jq '.factoryAddress'
```

## Paso 2: Desplegar el ClaimIssuer del registro y registrarlo como emisor confiable { #step-2-deploy-the-registry-claimissuer-and-trust-it }

El backend emite las atestaciones KYC/AML mediante un **contrato** ONCHAINID `ClaimIssuer`, uno por cadena, cuya clave MANAGEMENT es el firmante del registro del backend. El monedero (wallet) firmante no puede ser el emisor: `addClaim` de ONCHAINID llama a `isClaimValid` sobre el emisor, lo que revierte para un monedero simple, por lo que esas atestaciones nunca llegan a la cadena.

```bash
cd contracts
REGISTRY_WALLET_PRIVATE_KEY=$REGISTRY_SIGNER_KEY \
  forge script script/DeployClaimIssuer.s.sol --rpc-url $RPC_URL --broadcast
# Logs "ClaimIssuer : 0x…". The MANAGEMENT key is the broadcasting wallet, i.e. the
# backend's registry signer (default); CLAIM_ISSUER_MANAGEMENT_KEY is an optional override.
```

!!! warning "Clave de gestión = clave de firma «caliente» por defecto"
    Por defecto, el firmante del registro firma las atestaciones y controla a la vez el conjunto de claves del ClaimIssuer (`addKey`/`removeKey`) y sus actualizaciones; el backend necesita derechos MANAGEMENT para llamar a `revokeClaimBySignature`. Trate al firmante del registro como una clave de alto valor (respaldada por KMS/HSM en producción). `CLAIM_ISSUER_MANAGEMENT_KEY` puede indicar en su lugar una clave separada (fría o multifirma); esa clave debe entonces ejecutar `addKey(keccak256(abi.encode(registrySigner)), 3, 1)` para que el firmante pueda firmar atestaciones, y la revocación por parte del backend revierte mientras el firmante no tenga también una clave MANAGEMENT (purpose 1), por lo que las atestaciones deben revocarse desde la clave de gestión.

Establezca `CLAIM_ISSUER_<CHAIN>` (por ejemplo `CLAIM_ISSUER_ETH_TESTNET`, vinculado a `registerwerk.contracts.claim-issuer.<chain>`) y reinicie el backend. Sin este valor, el backend rechaza la emisión de atestaciones y el despliegue de suites T-REX en esa cadena (denegación por defecto), en lugar de difundir transacciones que revertirían. Antes de cada `addClaim` comprueba además que el firmante tiene una clave en el ClaimIssuer.

Las nuevas suites desplegadas por el backend confían en este ClaimIssuer para los temas 1 (KYC) y 2 (AML). Para una suite desplegada **antes** de este cambio, regístrelo una vez (API del operador, solo para el propietario del `TrustedIssuersRegistry` de la suite):

```bash
curl -X POST http://localhost:48080/api/v1/assets/$ASSET_ID/erc3643/$DEPLOYMENT_ID/trusted-issuers \
  -H "Authorization: Bearer $OPERATOR_JWT" -H "Content-Type: application/json" \
  -d "{\"issuerAddress\": \"$CLAIM_ISSUER\", \"claimTopics\": [1,2]}"
```

Verificar:

```bash
cast call $TRUSTED_ISSUERS_REGISTRY \
  "isTrustedIssuer(address)(bool)" $CLAIM_ISSUER --rpc-url $RPC_URL
# Expected: true
```

Para las dApps del ecosistema controladas mediante `PermissionOracle`, registre el mismo ClaimIssuer en el `EcosystemTrustedIssuersRegistry` a través de la administración de emisores confiables del operador del registro.

!!! note
    Revocar una atestación la elimina de la identidad **y** llama a `revokeClaimBySignature` en el ClaimIssuer. El segundo paso impide que alguien vuelva a añadir más tarde la firma publicada. Ambos pasos requieren que el firmante del registro tenga la clave MANAGEMENT del ClaimIssuer.

## Paso 3: Configurar temas de atestación { #step-3-configure-claim-topics }

El `ClaimTopicsRegistry` enumera todos los temas de atestación requeridos para la elegibilidad de transferencia:

```bash
cast send $CLAIM_TOPICS_REGISTRY "addClaimTopic(uint256)" 1 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY

cast send $CLAIM_TOPICS_REGISTRY "addClaimTopic(uint256)" 2 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

| ID de tema | Significado |
|----------|---------|
| 1 | KYC — verificación de identidad |
| 2 | AML — filtrado de prevención del blanqueo de capitales |

El backend aprovisiona automáticamente estos temas al crear una nueva emisión T-REX.

## Paso 4: registrar los contratos ONCHAINID del inversionista { #step-4-register-investor-onchainid-contracts }

Cuando un inversionista está incorporado, el backend implementa un contrato ONCHAINID para ellos y lo registra en el Registro de identidad. Esto sucede automáticamente cuando incluye a un inversionista en la lista blanca a través de la interfaz del operador.

Cada registro necesita un país en formato numérico ISO 3166-1. El diálogo del operador lo
rellena de antemano con el país de registro KYC de la entidad jurídica, y la API rechaza un país
ausente o el país `0`. En cuanto se bloquea algún país para un token, `EwpgComplianceModule`
rechaza las transferencias a un monedero (wallet) cuyo país registrado sea `0`. La tabla del
registro de identidad marca esos monederos como **Country missing** (falta el país). Corríjalos con
`updateCountry(address,uint16)` en el Identity Registry.

Para verificar que el ONCHAINID de un inversionista esté registrado:

```bash
cast call $IDENTITY_REGISTRY \
  "contains(address)(bool)" \
  $INVESTOR_WALLET_ADDRESS \
  --rpc-url $RPC_URL
# Expected: true
```

Para buscar la dirección ONCHAINID de un monedero:

```bash
cast call $IDENTITY_REGISTRY \
  "identity(address)(address)" \
  $INVESTOR_WALLET_ADDRESS \
  --rpc-url $RPC_URL
```

## Paso 5: Emitir atestaciones KYC/AML { #step-5-issuing-kycaml-claims }

Después de la aprobación de KYC en el frontend del operador, el backend emite automáticamente atestaciones en el ONCHAINID del inversor:

1. Construye los datos de la atestación `abi.encode(topic, scheme=1, claimIssuer, expiresAt, "")`
2. Firma `keccak256(abi.encode(identity, topic, data))` (con prefijo EIP-191) con el firmante del registro
3. Invoca `addClaim(topic, 1, claimIssuer, signature, data, "")` en el contrato ONCHAINID del inversor, con el contrato ClaimIssuer de la cadena como emisor

Las atestaciones incluyen una fecha de vencimiento (predeterminada: 365 días). El backend programa correos electrónicos recordatorios de vencimiento y puede volver a emitir atestaciones al momento de la renovación.

Para verificar manualmente las atestaciones en un ONCHAINID:

```bash
cast call $INVESTOR_ONCHAINID \
  "getClaimIdsByTopic(uint256)(bytes32[])" 1 \
  --rpc-url $RPC_URL
# Returns array of claim IDs for topic 1 (KYC)
```

## Paso 6: Módulos de cumplimiento { #step-6-compliance-modules }

Configure los módulos de cumplimiento por emisión desde la interfaz del operador en **Emisiones → [emisión] → Módulos de cumplimiento**.

### Módulo MaxBalance { #maxbalance-module }

Limita el saldo máximo de tokens que cualquier inversionista puede mantener.

Configurar a través de la interfaz del operador o directamente:

```bash
cast send $MAX_BALANCE_MODULE \
  "setMaxBalance(address,uint256)" $TOKEN_ADDRESS 100000 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

### Módulo MaxInvestors { #maxinvestors-module }

Limita el número total de poseedores de tokens distintos (útil para los límites de exención de la Regulación D):

```bash
cast send $MAX_INVESTORS_MODULE \
  "setMaxInvestors(address,uint256)" $TOKEN_ADDRESS 499 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

### Módulo CountryRestrict { #countryrestrict-module }

Bloquea inversores de códigos de país numéricos ISO 3166-1 especificados:

```bash
# Block US (840) and CN (156)
cast send $COUNTRY_RESTRICT_MODULE \
  "batchRestrictCountries(address,uint16[])" \
  $TOKEN_ADDRESS "[840,156]" \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

### EwpgComplianceModule (módulo propio de Registerwerk) { #ewpgcompliancemodule-registerwerks-own-module }

`EwpgComplianceModule` combina número máximo de inversores, saldo máximo por inversor, países
bloqueados, periodo de espera entre transferencias (cooldown) y la exención para pools de
nominees. Todos sus ajustes se guardan por contrato `ModularCompliance`. Sus setters reciben esa
dirección de cumplimiento como primer argumento, por ejemplo
`setMaxInvestors(address compliance, uint256)`.

- **Quién puede configurarlo.** Solo el propietario del contrato de cumplimiento puede llamar a
  un setter, o el propio contrato de cumplimiento mediante `callModuleFunction`. Cualquier otro
  llamante falla con `CallerNotComplianceAdmin`. T-REX transfiere la propiedad en dos pasos, así
  que tras desplegar una suite el monedero del registro es solo propietario *pendiente*. El
  backend llama a `acceptOwnership()` en el contrato de cumplimiento durante el despliegue. Si ese
  paso falló, lo repite antes del siguiente cambio de un módulo de cumplimiento.
- **Añadirlo desde el backend.** El backend vincula el módulo, envía los setters y vuelve a leer
  el resultado con `getConfig(address)` e `isCountryBlocked(address,uint16)`. Solo escribe la
  fila en la base de datos cuando todos los valores están on-chain. Si la configuración falla,
  desvincula el módulo e informa del error.
- **«Inversor» significa ONCHAINID.** Los saldos y el número de inversores se suman por
  identidad, de modo que varios monederos vinculados a un mismo ONCHAINID comparten un límite de
  saldo y cuentan como un solo inversor. Las transferencias entre dos monederos de la misma
  identidad siempre están permitidas.
- **País desconocido.** Mientras haya al menos un país bloqueado, se rechaza a un destinatario
  sin país registrado (`0`).

#### Migrar una suite en producción al módulo corregido { #migrating-a-live-suite-to-the-fixed-module }

Los contratos desplegados antes de esta corrección usan el módulo antiguo. En él **cualquiera**
puede cambiar los ajustes, y los límites se aplican por monedero en lugar de por identidad. El
módulo no es actualizable (upgradeable), por lo que cada suite en producción debe cambiar a un
módulo desplegado de nuevo.

1. Desplegar el nuevo `EwpgComplianceModule`.
2. Como propietario del contrato de cumplimiento (llame primero a `acceptOwnership()` si todavía
   es solo propietario pendiente), ejecutar `addModule(newModule)` en el `ModularCompliance` de la
   suite.
3. Configurar el nuevo módulo con los valores guardados en la base de datos
   (`erc3643_compliance_module`): `setMaxInvestors`, `setMaxBalance`, `setTransferCooldown`,
   `blockCountry` y `setNomineePool`. No copie valores del estado on-chain del módulo antiguo,
   porque cualquiera puede haberlos cambiado.
4. Cargar los titulares existentes:
   `syncHolders(compliance, wallets)` con cada monedero de `erc3643_identity_registry` para la
   suite (y cualquier otro monedero de titular que conozca el indexador). La llamada es
   idempotente, así que puede ejecutarse por lotes y repetirse sin riesgo.
5. Contrastar `getConfig(compliance)` con la base de datos, incluido el número de inversores
   frente al número de ONCHAINID distintos con saldo positivo.
6. Ejecutar `removeModule(oldModule)` en el contrato de cumplimiento.

!!! warning "Un módulo antiguo no se puede vincular desde el backend"
    Tras vincular un módulo, el backend vuelve a leer la configuración con `getConfig(address)`.
    Un módulo desplegado antes de esta corrección no tiene `getConfig`, por lo que añadirlo desde
    el backend siempre se revierte. No lo reintente: vuelva a desplegar `EwpgComplianceModule` y
    vincule la nueva instancia.

Hasta que una suite esté migrada, genere una alerta ante cualquier diferencia entre
`isCountryBlocked` / los límites del módulo antiguo y la base de datos. Comunique además a los
operadores, como tarea `updateCountry`, cada entrada del registro de identidad con país `0`,
comprobada on-chain con `investorCountry(wallet)`.

## Paso 7: Roles de agente { #step-7-agent-roles }

El monedero backend del registro debe contener roles de agente en cada token implementado para realizar operaciones de administración. El script de implementación los otorga automáticamente.

| Rol | Permite |
|------|--------|
| Agente de Registro de Identidad | `registerIdentity`, `updateIdentity`, `deleteIdentity` |
| Agente de Token | `mint`, `burn`, `freezePartialTokens`, `forcedTransfer` |
| Agente de Cumplimiento | `addModule`, `removeModule`, `callModuleFunction` |

Para otorgar roles de agente manualmente (si es necesario):

```bash
cast send $IDENTITY_REGISTRY \
  "addAgent(address)" $BACKEND_OPERATOR_ADDRESS \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY

cast send $TOKEN \
  "addAgent(address)" $BACKEND_OPERATOR_ADDRESS \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```
