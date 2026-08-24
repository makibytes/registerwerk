---
title: ERC-20 — Token fungible
description: Implementación del estándar ERC-20 para tokens de valores fungibles simples, de utilidad y de acciones.
---

# ERC-20 — Token fungible { #erc-20-fungible-token }

ERC-20 es el estándar de token fungible fundamental para las cadenas EVM. Cada unidad es idéntica e intercambiable. Registerwerk implementa tokens ERC-20 para instrumentos de capital, instrumentos de deuda simples y tokens de utilidad donde no se requiere la activación de KYC a nivel de contrato (en su lugar, el cumplimiento se aplica en la capa de registro).

---

## Cuándo usar ERC-20 { #when-to-use-erc-20 }

- **Tokens de acciones**: acciones de una empresa que no cotiza en bolsa donde las restricciones de transferencia se gestionan fuera de la cadena
- **Bonos simples**: cuando el emisor no necesita la aplicación de restricciones de transferencias en cadena
- **Tokens de utilidad**: para créditos internos de la plataforma o tokens de incentivo
- **Emisiones de prueba**: ERC-20 es la ruta de implementación más sencilla para los nuevos emisores que aprenden la plataforma

Para valores regulados que requieren activación KYC en cadena, considere [ERC-3643](erc3643.md). Para bonos con múltiples tramos, considere [ERC-3525](erc3525.md).

---

## Extensiones Registerwerk ERC-20 { #registerwerk-erc-20-extensions }

Registerwerk implementa un contrato `EwpgERC20` personalizado que amplía el estándar ERC-20 con:

| Ampliación | Propósito |
|---|---|
| `mintWithCap` | Respeta el `MintControlRule.maxSupply` configurado por el operador |
| `pause` / `unpause` | Disyuntor de emergencia para el operador de registro |
| `freeze(address)` | Congelación de la capa de registro (se asigna a `HolderBlock` en DB) |
| `setIsin(string)` | Almacena el ISIN en cadena para referencias cruzadas |
| `setRegistryRef(string)` | Almacena el ID del activo de Registerwerk para fines de auditoría |

---

## Flujo de implementación { #deployment-flow }

1. El operador selecciona `TokenStandard.ERC20` al crear un `Asset`
2. Después de la aprobación de KYC y (opcionalmente) la autenticación intensificada, llama a `POST /api/v1/assets/{id}/deploy`
3. `Erc20DeploymentService` construye y transmite la transacción de implementación
4. Al confirmar la recepción, se crea `AssetDeployment` con `contractAddress` y `deploymentTxHash`
5. `Asset.status` cambia a `ISSUED`

---

## Operaciones de administración en cadena { #on-chain-admin-operations }

| Operación | Punto final | Requiere |
|---|---|---|
| Acuñar tokens | `POST /api/v1/assets/{id}/mint` | REGISTRY_ADMIN + step-up (si se gestiona el límite de suministro) |
| Destruir tokens | `POST /api/v1/assets/{id}/burn` | REGISTRY_ADMIN + step-up + 4-eyes |
| Transferencia forzosa | `POST /api/v1/assets/{id}/force-transfer` | REGISTRY_ADMIN + step-up + 4-eyes |
| Congelar dirección | `POST /api/v1/assets/{id}/freeze/{address}` | REGISTRY_ADMIN + HolderBlock activo |
| Pausar contrato | `POST /api/v1/assets/{id}/pause` | REGISTRY_ADMIN + step-up |

---

## Variante confidencial { #confidential-variant }

`CONF_ERC20` implementa una variante confidencial de [Zama fhEVM](confidential.md) en redes Fhenix o Inco, donde los saldos y los importes de las transferencias se cifran mediante cifrado totalmente homomórfico. Utilice esto cuando el emisor requiera privacidad de las posiciones de los inversores.
