---
title: Qué es Registerwerk
description: Una explicación sencilla de qué hace la plataforma, qué no hace y qué puede esperar de ella.
---

# Qué es Registerwerk

**Es un registro.** Una anotación de quién posee qué valores, llevada por un operador, con esos valores representados además como tokens en una blockchain.

Esa es toda la idea. Lo demás es consecuencia.

---

## El problema que resuelve

Un valor solía ser un documento. Poseerlo significaba tenerlo físicamente, o que un depositario lo tuviera por usted. Venderlo significaba entregarlo.

Funcionaba, y era caro: cámaras acorazadas, mensajeros, conciliaciones y días entre acordar una operación y completarla.

Los valores electrónicos suprimen el documento. La propiedad pasa a ser una inscripción registral. En Alemania, la **eWpG**, en vigor desde junio de 2021, lo hace jurídicamente posible: un valor puede existir como inscripción en un registro en lugar de como título.

Registerwerk implementa un registro así y añade una segunda capa — las mismas tenencias representadas como tokens en una blockchain, de modo que las transmisiones puedan ejecutarse y verificarse de forma independiente sin que ninguna parte tenga que fiarse de los apuntes de la otra.

---

## Los dos registros

Es la única idea estructural que merece la pena entender, porque de ella se derivan casi todas las sorpresas.

<div class="grid" markdown>

!!! abstract "El registro"
    Una base de datos, en manos del operador. Nombra al titular, el importe, las restricciones.

    **La anotación con relevancia jurídica.**

!!! abstract "El token"
    Un saldo en un contrato inteligente sobre una blockchain. Público y verificable de forma independiente.

    **La anotación que ejecuta.**

</div>

Un software vigila la cadena y mantiene el registro acompasado. Casi siempre coinciden. Cuando no, manda el registro y la diferencia la resuelve una persona.

[:octicons-arrow-right-24: Tenencia y custodia](lifecycle/holding.md) entra en ello como es debido.

---

## Qué puede hacer

| | |
|---|---|
| **Emitir** | Crear un valor, conseguir su aprobación, desplegarlo, admitir inversores y administrarlo toda su vida. |
| **Mantener** | Poseer valores, ver sus posiciones, recibir extractos y pagos. |
| **Negociar** | Vender antes del vencimiento, o comprar a otros titulares. |
| **Tomar prestado** | Pignorar tenencias como garantía y obtener un préstamo contra ellas, donde esté habilitado. |
| **Publicar** | Construir aplicaciones sobre el marco de permisos del ecosistema y listarlas. |
| **Auditar** | Leer todo el registro sin poder cambiar nada. |

[:octicons-arrow-right-24: Encuentre su espacio de trabajo](workspaces/index.md)

---

## Dónde pueden vivir los valores

El registro admite varias blockchains, elegidas para cada emisión. Cada una tiene red principal y red de prueba.

| Familia | |
|---|---|
| **EVM** | Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism |
| **EVM confidencial** | Fhenix, Inco — importes cifrados on-chain |
| **Solana** | SPL y SPL-2022 |
| **Canton** | Un libro privado en el que las contrapartes solo ven sus propias transacciones |
| **Otras** | StarkNet, Stellar |

Cuál se elige importa más de lo que parece: determina quién puede ver sus transacciones, cuánto cuesta una transmisión, con qué rapidez se liquida y qué estándares de token están disponibles. [Blockchains admitidas](../blockchains/index.md) las compara.

---

## Qué no hace

Ser claro sobre esto es más útil que una lista de funciones.

!!! warning "Registerwerk es una implementación de referencia"
    Software que funciona, que modela cómo puede construirse un registro de valores electrónicos — para que el diseño pueda examinarse, criticarse y reutilizarse.

    **Usarlo no hace a nadie cumplidor de la eWpG ni de ninguna otra ley.** No confiere autorización regulatoria y no da a un token eficacia jurídica de valor. Eso depende de la licencia del operador, del instrumento, de la oferta, de las partes y de la instalación.

    Puede encontrar material antiguo que afirma que los tokens emitidos aquí son «jurídicamente equivalentes a los bonos al portador y las acciones tradicionales». **Esa afirmación es falsa** y se ha retirado. Que un instrumento tenga eficacia jurídica lo determinan la ley y la forma en que realmente se emitió — nunca el software que lo anotó.

Más concretamente, no es:

- **Un servicio de valoración.** El registro anota importes nominales, no precios de mercado.
- **Un custodio de sus claves.** La clave privada de su monedero la tiene usted. Nadie puede recuperarla.
- **Un centro de negociación.** Se conecta a centros de negociación; no gestiona un mercado.
- **Un sistema de pagos.** Admite varias vías de pago; el dinero se mueve por ellas, no aquí.
- **Un garante.** Si un emisor incumple, la plataforma lo anota. No resarce a los titulares.

---

## El trasfondo regulatorio, en breve

La **eWpG** (*Gesetz über elektronische Wertpapiere*) permite valores electrónicos sin documento físico y exige su anotación en un registro de valores. Los preceptos que encontrará más a menudo:

| | |
|---|---|
| **§16** | Qué contiene el registro y qué significa una inscripción. |
| **§17(2)** | Contenido adicional exigido para las inscripciones individuales. |
| **§19(2)** | Los extractos registrales debidos a los titulares consumidores. |
| **§24** | La rectificación del registro. |

Registerwerk modela además Luxemburgo (CSSF), Francia (AMF) y Liechtenstein (TVTG), y roza la prevención del blanqueo, la Travel Rule, el reporte MiFIR, DAC8/CARF, DORA, MiCAR y el RGPD.

[:octicons-arrow-right-24: Marcos jurídicos](../legal/index.md)

!!! note "Toda emisión en producción la aprueba antes el operador"
    El operador contrasta las emisiones con sus propios criterios de admisión antes de que se despliegue nada. Es un control operativo, no un dictamen jurídico sobre su instrumento.

---

## Adónde ir ahora

<div class="grid cards" markdown>

-   **Entender el negocio**

    ---

    [La vida de un valor](lifecycle/index.md) — un bono, de la idea a la amortización. Cuarenta minutos, sin conocimientos previos.

-   **Ponerse en marcha**

    ---

    [Obtener su cuenta](onboarding.md) → [Verificarse](kyc.md) → [Conectar un monedero](investors/wallet-setup.md)

-   **Hacer su trabajo**

    ---

    [Inversor](workspaces/investor.md) · [Trader](workspaces/trader.md) · [Emisor](workspaces/issuer.md) · [Auditor](workspaces/auditor.md)

-   **Consultar algo**

    ---

    [Glosario](glossary.md) · [Preguntas y respuestas](faq.md)

</div>
