---
title: La vida de un valor
description: Un bono, seguido desde la idea inicial hasta su amortización, con cada función de Registerwerk explicada donde realmente se usa.
---

# La vida de un valor

La mayoría de las documentaciones explican funciones. Esta sección cuenta una *historia* y deja que las funciones aparezcan donde les corresponde.

La historia es la de un bono. Lo seguimos desde el momento en que alguien quiere pedir dinero prestado, a través del papeleo, hasta una blockchain, a manos de los inversores, por un centro de negociación, dentro de un mercado de financiación como garantía y, por último, fuera de existencia cuando la deuda queda saldada.

**Quien lea esta sección entera entenderá el negocio del que se ocupa Registerwerk.** Unos cuarenta minutos.

---

## Nordwind Energie

!!! example "El ejemplo que nos acompaña"

    **Nordwind Energie GmbH** construye parques eólicos en Schleswig-Holstein. Necesita **50 millones de euros** para financiar un nuevo emplazamiento, y no quiere acudir a un banco.

    Así que decide pedir el dinero directamente a los inversores emitiendo un **bono**: la promesa de devolver el importe en una fecha fija, con intereses por el camino.

    Las condiciones previstas:

    | | |
    |---|---|
    | Importe | 50.000.000 € |
    | Denominación | 1.000 € por título, es decir, 50.000 títulos |
    | Interés | 4,5 % anual, pagado semestralmente |
    | Vencimiento | 5 años |
    | Amortización | valor nominal íntegro en la fecha de vencimiento |

    Ese es todo el producto financiero. Todo lo demás es la maquinaria que hace esa promesa efectiva, negociable y exigible — y que demuestra a un supervisor que todo se hizo correctamente.

??? note "Para lectores sin formación financiera: qué es realmente un bono"

    Un bono es un préstamo cortado en partes iguales para que muchos prestamistas puedan tomar una cada uno.

    Nordwind quiere 50 millones. En lugar de encontrar un único prestamista dispuesto a aportarlos todos, divide el préstamo en 50.000 partes de 1.000 €. Un inversor compra tantas como quiera. Cada parte da derecho a su porción de intereses y, al final, a 1.000 €.

    Tres palabras que encontrará constantemente:

    - **Valor nominal** (o *a la par*): el importe escrito en el título — aquí 1.000 €. Es lo que se devuelve al final, con independencia de lo que alguien pagara por él entre medias.
    - **Cupón**: el tipo de interés, aquí 4,5 % anual. El nombre viene de cuando los bonos eran de papel y se recortaba físicamente un cupón del título para cobrar cada pago.
    - **Vencimiento**: la fecha en que el préstamo termina y se devuelve el valor nominal.

    El punto decisivo y contraintuitivo: **el precio de un bono y su valor nominal son dos números distintos, y el precio se mueve.** Si los tipos suben después de la emisión, un bono que paga el 4,5 % resulta menos atractivo y solo se venderá con descuento — quizá 960 € por un título de 1.000 €. El valor nominal no ha cambiado. Lo que ha cambiado es lo que alguien está dispuesto a pagar por el derecho a cobrarlo.

---

## Las seis etapas

<div class="grid cards" markdown>

-   **1. [Diseño y aprobación](design.md)**

    ---

    Nordwind describe el bono en el portal, elige cómo existirá en una blockchain y lo presenta. El operador lo revisa y lo aprueba. Todavía no hay nada on-chain.

-   **2. [Emisión primaria](primary-issuance.md)**

    ---

    Se despliega el contrato, se admite a los inversores y los 50.000 títulos nacen en sus manos. El dinero va en un sentido y los valores en el otro.

-   **3. [Tenencia y custodia](holding.md)**

    ---

    Los inversores poseen algo. ¿Dónde está realmente, quién consta como titular y qué ocurre cuando el registro y la blockchain no coinciden?

-   **4. [Mercado secundario](secondary-market.md)**

    ---

    Un inversor quiere salir antes del vencimiento. Otro quiere entrar. Cómo se encuentran ambos y cómo se asegura el intercambio.

-   **5. [Repo y financiación](repo-lending.md)**

    ---

    Un inversor necesita liquidez pero quiere conservar el bono. Lo pignora y pide prestado contra él — el mecanismo más antiguo de los mercados financieros, reconstruido on-chain.

-   **6. [Operaciones societarias y amortización](redemption.md)**

    ---

    Intereses dos veces al año durante cinco años. Después el préstamo termina, el dinero vuelve y el valor se destruye.

</div>

---

## Los dos errores que conviene evitar

Dos ideas equivocadas causan la mayor parte de la confusión de los recién llegados. Nombrarlas ahora ahorra muchas relecturas.

**«El token *es* el valor.»** No lo es. El token es la forma en que el valor se transmite y se acredita en una blockchain. El valor es el derecho de crédito frente a Nordwind. El registro es la constancia de quién lo ostenta. Si mañana se apagaran todas las blockchains del mundo, a los inversores se les seguirían debiendo 50 millones de euros — solo que les costaría mucho más probar a quién corresponde qué. El token es el mecanismo, no la cosa.

**«En una blockchain cualquiera puede enviar cualquier cosa a cualquiera.»** Cierto para una criptomoneda. Rotundamente falso aquí. Un valor regulado solo puede estar en manos de quien está autorizado a tenerlo, y esa restricción debe sobrevivir al contacto con una blockchain pública en la que cualquiera puede invocar cualquier función. Resolver ese problema es la mayor parte de lo que hace que los tokens sobre valores sean más difíciles que los tokens corrientes, y es el tema de [Diseño y aprobación](design.md).

---

[Empiece por la etapa 1: Diseño y aprobación :octicons-arrow-right-24:](design.md){ .md-button .md-button--primary }
