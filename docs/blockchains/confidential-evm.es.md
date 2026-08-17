---
title: EVM confidencial (Zama fhEVM)
description: Qué cadenas gestionan realmente los contratos confidenciales de Registerwerk y qué infraestructura necesitan.
---

# EVM confidencial (Zama fhEVM) { #confidential-evm-zama-fhevm }

Los contratos confidenciales de Registerwerk (`ConfidentialERC20`, `ConfidentialERC3643`) se construyen
contra el fhEVM de **Zama**, específicamente la API `TFHE.sol`/Gateway incluida (vendored) en
`contracts/lib/fhevm` (el submódulo `zama-ai/fhevm-solidity`) en el lado del contrato, y el paquete real
`@zama-fhe/relayer-sdk` tanto en el backend (sidecar `zama-relayer`) como en el navegador
(`frontend-customer`/`frontend-operator`).

---

## Alcance de cadenas configurado { #configured-chain-scope }

| Cadena | Comportamiento de Registerwerk |
|---|---|
| Ethereum | Se acepta el despliegue confidencial cuando está configurada la factoría específica de la red. |
| Base | Se acepta el despliegue confidencial cuando está configurada la factoría específica de la red. |
| Otros valores de `Chain` | Se rechaza el despliegue confidencial. |

`AssetDeploymentService.FHEVM_CHAINS` es la lista permitida autoritativa. Fhenix e Inco siguen
siendo entradas EVM ordinarias del enum `Chain`, pero no son destinos válidos para despliegues confidenciales.

---

## Configuración de la infraestructura { #configuring-the-infrastructure }

Se inyecta cada dirección de contrato de host FHEVM, nunca se codifica por cadena:

```java
// ConfidentialERC20.FhevmInfra — passed to the constructor via EwpgConfidentialFactory
struct FhevmInfra {
    address aclAddress;
    address tfheExecutorAddress;
    address fhePaymentAddress;
    address kmsVerifierAddress;
    address gatewayAddress;
}
```

1. Implemente `EwpgConfidentialFactory` (o reutilice uno) en la cadena de destino, llamando a `setFhevmInfra`
con las direcciones Zama reales de esa cadena.
2. Configure `registerwerk.contracts.confidential-factory.<chain-identifier>` en la dirección de fábrica.
3. Para `CONF_ERC3643`, configure `registerwerk.contracts.confidential-identity-registry.<chain-identifier>`
en un T-REX `IdentityRegistry` real y aprovisionado: obligatorio; la fábrica revierte la implementación si
no está configurada en lugar de implementarla silenciosamente con un registro de identidad sin dirección.
4. Configure `registerwerk.contracts.confidential-operator-viewer.<chain-identifier>` y
`.confidential-auditor-viewer.<chain-identifier>` en las direcciones de visor de solo descifrado
dedicadas del operador y del auditor; consulte el modelo de visor ACL a continuación. Estos se pasan como
`initialViewers` en la implementación, por lo que cada token confidencial en esa cadena los otorga desde el bloque
uno.

---

## Quién puede descifrar: el modelo de ACL de visor { #who-can-decrypt-the-viewer-acl-model }

Véase [Tokens confidenciales](../token-standards/confidential.md#who-can-decrypt-what-the-viewer-acl-model)
para obtener una explicación completa. En resumen: cada titular puede descifrar únicamente su propio identificador de saldo; un pequeño conjunto de "visores" de operador/auditor/emisor
puede descifrar cualquier identificador. Esto se encuentra completamente en `isViewer`/`addViewer`/`removeViewer` de
`ConfidentialERC20`: no hay contratos separados por inversor.

---

## Descifrado: tres caminos, todos reales { #decryption-three-paths-all-real }

- **Descifrado de usuario** (un titular que revela su propio saldo o un visor que revela cualquier saldo):
completamente del lado del cliente. El monedero conectado firma la carga útil `UserDecryptRequestVerification` EIP-712
del KMS y la propia instancia `@zama-fhe/relayer-sdk` del navegador completa `userDecrypt` directamente
contra el relayer de Zama; consulte el `FheClientService` de `frontend-customer`/`frontend-operator`. El backend
nunca ve el valor de texto sin formato en esta ruta.
- **Descifrado por operador sin cabeza** (informes/conciliación, sin navegador en el bucle): el sidecar
`zama-relayer` del backend contiene una clave dedicada solo para descifrado (`OPERATOR_DECRYPT_PRIVATE_KEY` —
deliberadamente NO un monedero de firma de transacciones en cadena) y autofirma la misma solicitud EIP-712,
luego completa `userDecrypt` en un viaje de ida y vuelta. Consulte
`ConfidentialBalanceReconciliationService` y `ZamaRelayerClient.requestOperatorDecrypt`.
- **Descifrado público/oracle** (`ConfidentialERC20.requestSupplyDisclosure`): el contrato en sí
solicita que la puerta de enlace descifre un valor (por ejemplo, suministro total) y recibe el texto sin cifrar a través de una devolución de llamada firmada.
La implementación del repositorio y las pruebas de Foundry están presentes, pero la integración del coprocesador en vivo
y la preparación para la producción aún no están verificadas.

`zama-relayer` (raíz del repositorio `zama-relayer/`) es el sidecar propio de Registerwerk que envuelve la compilación para Node real de
`@zama-fhe/relayer-sdk`; existe solo porque Zama no publica ningún cliente Java/JVM;
cada flujo iniciado por el navegador descrito arriba habla con Zama directamente y nunca toca este sidecar. Actívelo
con `docker compose --profile confidential up`; consulte los comentarios de la propia fuente de `zama-relayer` y la sección "Tokens confidenciales" de
`.env.example` para conocer la configuración.

Consulte [Tokens confidenciales](../token-standards/confidential.md) para obtener la matriz de estado completa y
[SPL-2022 Transferencia confidencial](../token-standards/spl-2022.md) para el equivalente
Solana no relacionado, basado en ElGamal: los dos son fáciles de confundir pero usan criptografía diferente y no tienen ningún código
en común.
