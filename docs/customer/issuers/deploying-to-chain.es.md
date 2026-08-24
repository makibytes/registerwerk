---
title: Desplegar en la blockchain
---

# Desplegar en la blockchain

Una vez que el operador del registro ha aprobado su emisión, puede desplegar el contrato del token en la blockchain. Este paso es irreversible — la dirección del contrato pasa a formar parte permanente de la anotación registral.

## Requisitos previos

- El estado de la emisión es **APPROVED**
- Usted tiene la función **Issuer** o **Company Admin**
- Para emisiones ERC-3643: el operador ha desplegado previamente los contratos de fábrica en la cadena de destino

## Iniciar el despliegue

1. Vaya a **Issuances** y localice su emisión (estado: APPROVED)
2. Pulse **Deploy to Blockchain**
3. Aparece un cuadro de confirmación que resume los parámetros del despliegue:

| Parámetro | Valor |
|-----------|-------|
| Token standard | ERC-3643 |
| Network | Polygon Mainnet |
| ISIN | DE000EXAMPLE0 |
| Name | Example AG Bond 2025 |
| Symbol | EAGB25 |
| Total supply | 10.000.000 |

4. Pulse **Confirm Deployment**

## Qué ocurre durante el despliegue

El backend del registro envía por cuenta de usted una transacción de despliegue a la blockchain mediante un monedero desplegador controlado por el operador. No necesita firmar ninguna transacción ni mantener ETH/MATIC.

En una emisión **ERC-3643** se despliegan en secuencia los siguientes contratos:

1. **Contrato del token** — el token ERC-3643 principal
2. **Identity Registry** — asocia las direcciones de monedero de los inversores con su ONCHAINID
3. **Identity Registry Storage** — almacenamiento persistente del registro
4. **Claim Topics Registry** — enumera los temas de atestación KYC exigidos (p. ej. tema 1 = KYC, tema 2 = prevención del blanqueo)
5. **Trusted Issuers Registry** — enumera qué emisores de identidad son de confianza para emitir atestaciones
6. **Modular Compliance** — contenedor de los módulos de reglas de cumplimiento

Suele tardar de 30 a 120 segundos según la congestión de la red.

## Seguir el progreso del despliegue

La página de detalle de la emisión muestra un indicador de progreso en vivo durante el despliegue. Cada despliegue de contrato se lista con su hash de transacción, que enlaza al explorador de bloques.

Si algún paso falla (por una caída de red o gas insuficiente, por ejemplo), el despliegue se reintenta automáticamente hasta tres veces. Si fallan todos los intentos, la emisión vuelve al estado **APPROVED** y se le notificará por correo.

## Tras un despliegue satisfactorio

Cuando todos los contratos están desplegados, la emisión pasa al estado **ISSUED**. Podrá ver:

- **Dirección del contrato** — la dirección del contrato principal del token
- **Enlace al explorador de bloques** — verificar el contrato en Etherscan, Polygonscan, etc.
- **Transacción de despliegue** — la transacción que creó el token

!!! tip
    Comparta la dirección del contrato y el enlace al explorador con sus inversores para que puedan verificar sus tenencias de forma independiente.


## Siguientes pasos

- [Añadir inversores y admitir monederos](./managing-investors.md)
- Configurar módulos de cumplimiento (en las configuraciones ERC-3643 estándar lo hacen los operadores automáticamente)
- Anunciar la emisión a sus inversores
