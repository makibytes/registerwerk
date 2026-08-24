---
title: Estándares de token
description: Implementaciones de estándares de token representadas en Registerwerk, con comparación, casos de uso y estado de realización.
---

# Estándares de token

Registerwerk contiene implementaciones, integraciones o marcadores de posición para estándares de token en cadenas EVM, Solana, StarkNet, Stellar y Canton. Figurar en esta tabla no acredita madurez para producción ni idoneidad para un instrumento financiero.

---

## Comparación de referencia

| Estándar | Cadena | Tipo | Caso de uso | Variante confidencial | Estado |
|---|---|---|---|---|---|
| [ERC-20](erc20.md) | EVM | Fungible | Capital, utilidad | CONF_ERC20 | Implementación presente; madurez no verificada |
| [ERC-721](erc721.md) | EVM | No fungible | Certificados únicos, bonos NFT | — | Implementación presente; madurez no verificada |
| [ERC-1155](erc1155.md) | EVM | Multitoken | Emisión por lotes | — | Implementación presente; madurez no verificada |
| [ERC-3525](erc3525.md) | EVM | Semifungible | Bonos con tramos, series de fondos | STARKNET_ERC3525 | Implementación presente; madurez no verificada |
| [ERC-3643](erc3643.md) | EVM | Fungible + identidad | Valores regulados, acceso restringido | CONF_ERC3643 | Implementación presente; madurez no verificada |
| [ERC-4626](erc4626.md) | EVM | Bóveda (síncrona) | Fondos monetarios, VL diario | — | Implementación presente; modelo económico/madurez no verificados |
| [ERC-7540](erc7540.md) | EVM | Bóveda (asíncrona) | Fondos institucionales, T+1/T+2 | — | Implementación presente; modelo económico/madurez no verificados |
| [DAML BOND FIXED](canton-daml.md) | Canton | Bono | Ciclo de vida a tipo fijo | — | Plantilla Daml propia; perfil verificado, conformidad live necesaria |
| [DAML BOND FLOATING](canton-daml.md) | Canton | Bono | Ciclo de vida a tipo variable | — | Plantilla Daml propia; perfil verificado, conformidad live necesaria |
| [DAML BOND ZERO](canton-daml.md) | Canton | Bono | Ciclo de vida cupón cero | — | Plantilla Daml propia; perfil verificado, conformidad live necesaria |
| [CANTON_TOKEN](canton-daml.md) | Canton | Genérico | Activo CIP-0056 | — | Reservado; no desplegable hasta disponer de un adaptador específico del registro |
| [SPL](../blockchains/solana.md) | Solana | Fungible | Tokens nativos de Solana | — | Integración presente; madurez no verificada |
| [SPL_2022](spl-2022.md) | Solana | Fungible + ext. | Tokens de Solana extendidos | — | Integración presente; madurez no verificada |
| [SPL_2022_BOND](spl-2022.md) | Solana | Fungible + intereses | Bonos con intereses en Solana | — | Integración presente; madurez no verificada |
| [SPL_2022_CONFIDENTIAL](spl-2022.md) | Solana | Confidencial | Token confidencial de Solana | — | Integración presente; madurez no verificada |
| [STARKNET_ERC20](../blockchains/starknet-stellar.md) | StarkNet | Fungible | Equivalente en Cairo de ERC-20 | — | ⚠️ Marcador de posición |
| [STARKNET_ERC3525](../blockchains/starknet-stellar.md) | StarkNet | Semifungible | Equivalente en Cairo de ERC-3525 | — | ⚠️ Marcador de posición |
| [STELLAR_ASSET](../blockchains/starknet-stellar.md) | Stellar | Activo | Activo emitido de forma nativa en Stellar | — | ⚠️ Marcador de posición |
| [CONF_ERC20](confidential.md) | Fhenix / Inco | Fungible confidencial | Capital que preserva la privacidad | — | Implementación presente; madurez no verificada |
| [CONF_ERC3643](confidential.md) | Fhenix / Inco | Regulado confidencial | Valor regulado que preserva la privacidad | — | Implementación presente; madurez no verificada |

---

## Cómo la enumeración `TokenStandard` gobierna el despliegue

La enumeración `TokenStandard` del módulo `deployment` es el conmutador central que determina qué servicio de despliegue se invoca:

```java
// BlockchainTokenDeploymentService — simplified routing
return switch (asset.tokenStandard()) {
    case ERC20         -> erc20DeploymentService.deploy(asset);
    case ERC721        -> erc721DeploymentService.deploy(asset);
    case ERC3525       -> erc3525DeploymentService.deploy(asset);
    case ERC3643       -> erc3643DeploymentService.deploy(asset);
    case ERC4626       -> erc4626DeploymentService.deploy(asset);
    case ERC7540       -> erc7540DeploymentService.deploy(asset);
    case DAML_BOND_FIXED, DAML_BOND_FLOATING, DAML_BOND_ZERO ->
                          cantonBondDeploymentService.deploy(asset);
    case SPL, SPL_2022, SPL_2022_BOND, SPL_2022_CONFIDENTIAL ->
                          solanaTokenService.deploy(asset);
    // ...
};
```

El estándar elegido determina además qué operaciones de administración están disponibles en el portal del operador y a qué tipos de evento se suscribe el indexador.

---

## Elegir el estándar adecuado

| Escenario | Estándar recomendado | Motivo |
|---|---|---|
| Token simple de capital / utilidad en EVM | ERC-20 | El soporte de monederos más amplio |
| Valor sujeto a KYC en EVM | ERC-3643 | Módulos de identidad y cumplimiento incorporados |
| Bono con varios tramos / series | ERC-3525 | Modelo semifungible nativo de ranura + valor |
| Participación de fondo con VL diario en EVM | ERC-4626 | Interfaz de bóveda normalizada |
| Fondo institucional con reembolso T+1/T+2 | ERC-7540 | Modelo asíncrono de solicitud y reclamación |
| Bono a tipo fijo en libro Daml privado | DAML_BOND_FIXED | Ciclo de vida on-ledger; pago integrado por separado |
| Valor que preserva la privacidad en EVM | CONF_ERC3643 | Zama fhEVM confidencial + regulado |
| Bono con intereses en Solana | SPL_2022_BOND | Extensión Token-2022 con devengo de intereses |
