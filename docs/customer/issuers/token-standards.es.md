---
title: Elegir un estándar de token
---

# Elegir un estándar de token

El registro eWpG admite cinco estándares de token. Esta página le ayuda a entender las diferencias y a escoger el adecuado para su emisión.

## ERC-20 — Token fungible

ERC-20 es el estándar de token más ampliamente admitido en las cadenas compatibles con Ethereum. Todos los tokens de una misma clase son idénticos e intercambiables.

**Ventajas**
- Admitido prácticamente por todos los monederos, mercados de intercambio y protocolos DeFi
- Sencillo de desplegar y gestionar
- Bajo coste de gas en las transmisiones

**Inconvenientes**
- Sin aplicación de cumplimiento incorporada — cualquiera puede recibir el token
- Sin soporte nativo para importes parciales en valores fraccionados

**Idóneo para**: valores fungibles cuyo cumplimiento se gestiona íntegramente fuera de la cadena, o despliegues internos de prueba.

---

## ERC-721 — Token no fungible (NFT)

Los tokens ERC-721 son únicos — cada token tiene un identificador y un propietario distintos. Eso los hace adecuados para valores que representan un activo único o una unidad determinada.

**Ventajas**
- Cada token es identificable individualmente (útil para títulos de deuda con condiciones propias)
- Metadatos ricos mediante `tokenURI`
- Fuerte soporte de monederos y mercados

**Inconvenientes**
- No apto para grandes cantidades de unidades fungibles (una transacción por token)
- Mayor coste de gas por transmisión que ERC-20

**Idóneo para**: valores únicos, bonos individuales o productos estructurados en los que cada unidad tiene condiciones propias.

---

## ERC-1155 — Estándar multitoken

ERC-1155 permite que un solo contrato gestione varios tipos de token a la vez — tanto fungibles como no fungibles.

**Ventajas**
- Operaciones por lotes eficientes: transmitir varios tipos de token en una transacción
- Puede representar valores fungibles y no fungibles en un mismo contrato
- Menor coste de gas en operaciones por lotes que varios contratos ERC-20/721

**Inconvenientes**
- Menos admitido por los monederos minoristas que ERC-20 o ERC-721
- Sin aplicación de cumplimiento incorporada

**Idóneo para**: emisores que gestionan varios tramos o series de valores y quieren reducir la complejidad contractual.

---

## ERC-3643 (T-REX) — Recomendado para valores regulados

ERC-3643, también conocido como T-REX (Token for Regulated EXchanges), es un estándar abierto diseñado específicamente para tokens de valores regulados. Es el **estándar recomendado** para la mayoría de las emisiones bajo la eWpG.

**Ventajas**
- Cumplimiento on-chain: las transmisiones se bloquean automáticamente si alguna de las partes no supera las comprobaciones
- La identidad del inversor se verifica mediante ONCHAINID, un estándar de identidad descentralizada
- Módulos de cumplimiento granulares (saldo máximo, número máximo de inversores, restricciones por país, etc.)
- Separación de las funciones de agente (agentes de identidad, de transmisión, de cumplimiento)
- Plenamente compatible con los protocolos DeFi que admiten la interfaz ERC-20

**Inconvenientes**
- Configuración inicial más compleja (exige desplegar varios contratos)
- Los inversores deben tener un ONCHAINID y atestaciones KYC/prevención del blanqueo válidas antes de recibir tokens
- Coste de gas por transmisión ligeramente superior por las comprobaciones de cumplimiento

**Idóneo para**: cualquier emisión de valor regulado en la que las restricciones a la transmisión deban aplicarse automáticamente on-chain.

Véase el desarrollo completo en [ERC-3643 explicado](../../token-standards/erc3643.md).

---

## ERC-3643 confidencial — Tokens regulados que preservan la privacidad

El ERC-3643 confidencial extiende el estándar T-REX con cifrado totalmente homomórfico (FHE), aportado por el fhEVM de Zama. Los saldos y los importes transmitidos están cifrados on-chain — solo las partes autorizadas pueden descifrarlos.

**Ventajas**
- Los saldos de los inversores quedan ocultos al público sin dejar de ser auditables por las partes autorizadas
- El cumplimiento se sigue aplicando plenamente (el contrato inteligente puede verificarlo sobre datos cifrados)
- Adecuado para casos institucionales en los que el tamaño de las posiciones debe permanecer confidencial

**Inconvenientes**
- Disponible solo en las redes Fhenix e Inco
- Mayor coste de gas por el cálculo FHE
- Soporte de monederos y herramientas más limitado que en el ERC-3643 estándar
- Los inversores necesitan herramientas de monedero compatibles con FHE para interactuar

**Idóneo para**: valores institucionales en los que la confidencialidad de las tenencias es un requisito regulatorio o comercial.

Véase [Los tokens confidenciales explicados](../../token-standards/confidential.md).

---

## Guía de decisión

```
Is on-chain compliance enforcement required?
  YES → Are balances required to be confidential?
            YES → Confidential ERC-3643
            NO  → ERC-3643 (T-REX)
  NO  → Are tokens unique/non-fungible?
            YES → ERC-721
            NO  → Do you need multiple token types in one contract?
                      YES → ERC-1155
                      NO  → ERC-20
```
