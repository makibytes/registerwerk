---
title: Inversor
description: Para quienes mantienen valores — ver qué posee, cuánto vale y qué se le debe.
---

# Inversor

**Posee valores y quiere llevar el control.** No negocia activamente, no emite nada y no busca apalancamiento. Compró algo y quiere saber cómo va.

Es el espacio de trabajo más pequeño, y lo es a propósito.

---

## Antes de que nada funcione

Tres condiciones deben cumplirse para que un valor pueda llegarle. Si algo no funciona, casi siempre es una de ellas.

<div class="grid cards" markdown>

-   **1. Su organización está dada de alta**

    ---

    Su empresa existe en el registro como entidad jurídica con estado activo.

    [:octicons-arrow-right-24: Obtener su cuenta](../onboarding.md)

-   **2. Su KYC está aprobado**

    ---

    El operador ha verificado su organización. No solo presentado — **aprobado**, y no caducado.

    [:octicons-arrow-right-24: Verificación](../kyc.md)

-   **3. Ha registrado un monedero**

    ---

    Una dirección a la que puedan enviarse los valores. Sin ella no hay dónde entregar.

    [:octicons-arrow-right-24: Conectar un monedero](../investors/wallet-setup.md)

</div>

!!! warning "El orden importa"
    Para un instrumento regulado como un valor [ERC-3643](../../token-standards/erc3643.md), su monedero debe estar admitido en el identity registry de ese instrumento *antes* de que puedan transmitirle nada. Una transmisión a un monedero no registrado no queda pendiente: falla on-chain.

    Si un emisor dice haberle enviado valores y no ha llegado nada, esto es lo primero que hay que comprobar.

---

## Su día a día

### Dashboard

Qué ha cambiado desde la última vez: sus posiciones, la actividad reciente, todo lo que requiere atención — un KYC que caduca, una operación pendiente, una posición bloqueada.

### Positions

Todo lo que mantiene, en todos los activos y todas las cadenas.

| Columna | Cómo leerla |
|---|---|
| **Asset** | Qué valor. |
| **Nominal amount** | El valor nominal que mantiene. |
| **Wallet** | Cuál de sus direcciones lo mantiene. |
| **Entry type** | Inscripción colectiva o individual — [qué significa](../lifecycle/primary-issuance.md#que-contiene-una-inscripcion-registral). |
| **Status** | Activa o bloqueada. |

!!! note "El nominal no es el valor de mercado"
    100.000 € nominales significa que al vencimiento se le deberán 100.000 €. No significa que la posición valga 100.000 € hoy — un bono puede cotizar por encima o por debajo del par durante toda su vida.

    Registerwerk es un registro. Anota qué tiene, no cuánto le darían por ello.

### Investments

Una posición, en profundidad. Las condiciones del instrumento, su dirección on-chain y su historial de transacciones, las operaciones societarias que le afectan y sus extractos registrales.

Aquí es donde acude cuando necesita *acreditar* algo en lugar de simplemente verlo.

---

## Lo que le va a ocurrir

### Recibirá un extracto registral

Si mantiene bajo **inscripción individual** y es consumidor, el §19(2) eWpG le da derecho a un *Registerauszug* — tras la inscripción inicial, tras cada cambio que le afecte y al menos una vez al año.

Son documentos permanentes y reproducibles, no correos de notificación. [Más información](../lifecycle/holding.md#su-extracto-registral).

Los titulares institucionales en una inscripción colectiva no los reciben — de ahí que quizá no vea ninguno.

### Recibirá cupones

En un bono, los intereses llegan según un calendario. Que *usted* cobre un pago concreto depende de la **fecha de registro**, no de la fecha de pago — si es titular en la fecha de registro, el pago es suyo aunque venda al día siguiente.

[:octicons-arrow-right-24: Cómo funcionan las operaciones societarias](../lifecycle/redemption.md)

### Su KYC caducará

La verificación tiene fecha de expiración. Cuando se acerca, la plataforma le avisa; cuando se supera, las transmisiones se detienen.

**Esto no le quita sus valores.** Sigue siendo titular, sigue teniendo derecho a los pagos. Simplemente no puede mover nada hasta que su organización vuelva a verificarse.

### Una posición puede bloquearse

Una resolución judicial, una coincidencia en una lista de sanciones, una pignoración, una cuestión de cumplimiento sin resolver. Verá el bloqueo y su motivo junto a la posición.

Sigue siendo suya. No puede moverla. [Más información](../lifecycle/holding.md#cuando-una-posicion-esta-bloqueada).

---

## Lo que no puede hacer aquí

Dicho con claridad, para que no lo busque:

- **No puede vender desde el espacio Investor.** Vender exige la función `TRADER` y el [espacio Trader](trader.md).
- **No puede valorar su cartera.** Registerwerk no dispone de precios de mercado para los valores que registra.
- **No puede transmitir a una dirección cualquiera.** En instrumentos regulados el destino debe ser un titular admitido.
- **No puede recuperar por sí mismo un monedero perdido.** Véase abajo.

!!! danger "Si pierde la clave de su monedero"
    Nadie puede restaurarla. Ni el operador, ni el emisor.

    Su *derecho de crédito* subsiste — el registro sigue haciéndole constar como titular, y mantiene su derecho a cupones y amortización. Lo que ha perdido es la capacidad de mover los tokens.

    La recuperación pasa por una **transferencia forzosa** ejecutada por el operador al amparo del §24 eWpG: una corrección formal y documentada que traslada su posición a un monedero que usted controle. Contacte con el operador. Exige pruebas, exige [doble control](../../compliance/step-up-mfa.md), y no es rápida.

---

## Adónde ir ahora

- [La vida de un valor](../lifecycle/index.md) — qué está ocurriendo realmente a su alrededor
- [Tenencia y custodia](../lifecycle/holding.md) — dónde residen realmente sus valores
- [Preguntas y respuestas](../faq.md)
