---
title: Aprobar una emisión
description: La decisión que da origen a una seguridad: qué verificar, qué significa y qué no significa aprobación y qué sucede a continuación.
---

# Aprobación de una emisión { #approving-an-issuance }

Un emisor describió un valor y lo presentó. Hasta que lo apruebe, es una descripción. Después de su aprobación, puede convertirse en una obligación legal de ese emisor en manos de los inversores.

Esta es la decisión de rutina más importante que toma un operador.

---

## Lo que realmente está decidiendo { #what-you-are-actually-deciding }

!!! warning "Sea preciso sobre lo que significa aprobación"
    Aprobación significa: **esta emisión cumple con los criterios de admisión del registro.**

    No significa que el instrumento sea legal, que la oferta cumpla con las reglas del folleto, que el emisor pueda emitirlo legalmente o que el token tenga efecto legal. Eso depende de la autorización del emisor, su asesoramiento y sus circunstancias.

    Si un emisor trata su aprobación como una opinión de cumplimiento, corríjalo por escrito. Ese malentendido sale caro después.

---

## Antes de mirar { #before-you-look }

Confirme primero las cosas aburridas: se descalifican más rápido que cualquier otra cosa en los términos:

- [ ] La entidad emisora está **activa** y su **KYC está aprobado y vigente**.
- [ ] La entidad está registrada como emisor.
- [ ] No hay ningún asunto de [sanciones](../../compliance/sanctions-screening.md) abierto en su contra.

---

## Qué verificar { #what-to-check }

### Identidad { #identity }

| | |
|---|---|
| **Nombre** | Sensible y no engañosamente similar a un instrumento existente. |
| **ISIN** | Único: la plataforma hace cumplir esto. Registerwerk no emite ISIN; el emisor obtiene uno de su agencia nacional de numeración. Se permite una emisión sin uno, pero limita la interoperabilidad. |
| **Jurisdicción** | Selecciona todo el cuerpo de reglas aplicadas durante la vida del instrumento. Cambiarlo más tarde no es una edición de campo. |

### Términos { #terms }

Para un bono: valor nominal, moneda, fechas de emisión y vencimiento, tasa de cupón, recuento de días, frecuencia de pago, capacidad de rescate, precio de emisión.

!!! tip "Tres cosas que merecen una segunda mirada"
    **Vencimiento antes de la fecha de emisión.** Raro y catastrófico si alcanza la producción: el cronograma de cupones se genera a partir de estos.

    **Precio de emisión de un bono cupón cero.** Su valor predeterminado es `1.0` — par. Un bono cupón cero a la par no paga intereses y reembolsa su valor nominal: un instrumento que no devuelve nada. Si realmente es cupón cero, el precio de emisión debería ser un descuento. Este valor predeterminado ha causado confusión real.

    **Base de cálculo de intereses.** Poco glamurosa, y cambia la cantidad de dinero que se mueve. Confirme que coincide con la hoja de términos en lugar de asumirlo.

### Cadena y estándar { #chain-and-standard }

¿El estándar del token se ajusta a lo que se afirma sobre el instrumento?

!!! danger "Un ERC-20 para un valor restringido es la falta de coincidencia que hay que detectar"
    Si el instrumento solo puede ser mantenido por inversores verificados o profesionales, [ERC-20](../../token-standards/erc20.md) no puede hacer cumplir eso. Cualquiera que reciba una unidad es su propietario.

    Los instrumentos restringidos deben usar [ERC-3643](../../token-standards/erc3643.md), donde la elegibilidad se verifica en el contrato del token y las transferencias que no cumplen con las normas revierten (revert) en la cadena.

    Esta es la verificación técnica más importante de la revisión, porque después es invisible. Nada se rompe en la aprobación. Se rompe la primera vez que una unidad llega a un monedero que nunca debería haberla mantenido — momento en el cual ya hay 50.000 unidades en circulación.

Confirme también que mainnet frente a testnet es lo que el emisor pretendía. Aprobar en mainnet una emisión que alguien concibió como un ensayo es una conversación incómoda.

---

## La decisión { #deciding }

=== "Aprobar"

    El estado pasa a `APPROVED`. **Los términos quedan bloqueados.** El emisor ya puede implementar.

    Registre por qué aprobó. El registro de auditoría deja constancia de que lo hizo, no de qué le convenció.

=== "Rechazar"

    El estado vuelve a **`DRAFT`** — de nuevo editable — con su motivo registrado.

    No existe un estado `REJECTED`. Una emisión rechazada es un borrador. Esto sorprende a los operadores que esperan un estado sin salida.

    **Escriba un motivo sobre el que el emisor pueda actuar.** "No conforme" produce un nuevo envío de lo mismo. "El instrumento está restringido a inversores profesionales, pero utiliza ERC-20, que no puede hacer cumplir eso; vuelva a enviarlo como ERC-3643" produce uno correcto.

---

## Después de la aprobación { #after-approval }

No ha terminado con esto. El emisor:

1. **Implementará** el contrato.
2. **Admitirá inversores** — cada uno necesita una entidad KYC aprobada y un monedero registrado.
3. **Acuñará** las unidades.
4. **Emitirá**, poniéndolo en marcha.

Volverá a estar involucrado cuando los inversores necesiten incorporarse y, a partir de entonces, de forma permanente para las operaciones societarias.

!!! info "La liquidación de una operación societaria necesita un segundo operador"
    Aprobar una operación societaria para su liquidación requiere [cuatro ojos](../../compliance/step-up-mfa.md).

    Pagar a la lista de titulares incorrecta es el clásico error catastrófico en la administración de valores, y es muy difícil de revertir. Asegúrese de que su turno de guardia tenga realmente dos personas disponibles cuando llegan las fechas de cupón: un control de cuatro ojos que nadie puede cumplir un viernes por la tarde es un control que acaba sorteándose.

---

## Suspensión y canje { #suspension-and-redemption }

**Suspend** (`ISSUED` → `SUSPENDED`) congela la negociación sin poner fin al instrumento, por una operación societaria, una disputa o un error sospechado. Reversible.

**Redeem** es terminal. No hay salida de `REDEEMED`.

Ambos quedan registrados con un actor identificado por su nombre.

---

## Dónde siguiente { #where-next }

- [Revisando KYC](kyc-process.md) — la puerta previa a esta
- [Diseño y aprobación](../../customer/lifecycle/design.md) — la visión del emisor del mismo paso
- [Elección de un estándar de token](../../customer/issuers/token-standards.md)
