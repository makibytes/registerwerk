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
curl http://localhost:8080/api/v1/admin/chains/11155111 \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  | jq '.factoryAddress'
```

## Paso 2: Configure el registro como Emisor confiable { #step-2-set-up-the-registry-as-trusted-issuer }

El monedero del operador backend del registro debe estar registrado en `TrustedIssuersRegistry` para que pueda emitir atestaciones KYC/AML. Esto se hace una vez por implementación de fábrica.

```bash
cast send $TRUSTED_ISSUERS_REGISTRY \
  "addTrustedIssuer(address,uint256[])" \
  $REGISTRY_OPERATOR_ADDRESS "[1,2]" \
  --rpc-url $RPC_URL \
  --private-key $DEPLOYER_PRIVATE_KEY
```

Parámetros:
- Primer argumento: dirección del operador de registro (monedero del implementador)
- Segundo argumento: conjunto de ID de temas de atestación que este emisor está autorizado a firmar (1=KYC, 2=AML)

Verificar:

```bash
cast call $TRUSTED_ISSUERS_REGISTRY \
  "isTrustedIssuer(address)(bool)" \
  $REGISTRY_OPERATOR_ADDRESS \
  --rpc-url $RPC_URL
# Expected: true
```

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

1. Construye una atestación con ID de tema, dirección del emisor y un hash del registro de verificación KYC
2. Firma la atestación con la clave privada del operador
3. Invoca `addClaim` en el contrato ONCHAINID del inversor

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
