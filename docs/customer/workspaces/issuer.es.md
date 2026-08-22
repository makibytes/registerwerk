---
title: Emisor
description: Para organizaciones que captan financiación emitiendo valores — crearlos, desplegarlos, administrarlos y amortizarlos.
---

# Emisor

**Está tomando dinero prestado, o vendiendo una participación, y lo hace emitiendo un valor.** Describe el instrumento, consigue su aprobación, lo lleva a una blockchain, admite inversores, crea las unidades — y después administra el asunto durante años.

De los tres espacios de trabajo, este es el que lleva más responsabilidad adherida. Lo que crea aquí es una obligación jurídica de su organización.

---

## Qué hay aquí

| | |
|---|---|
| **Issuances** | Crear y administrar sus valores. El acontecimiento principal. |
| **My dApps** | Publicar aplicaciones en el mercado — véase [Editor de dApps](dapp-publisher.md). |
| **Company Admin** | Gestionar sus usuarios y su organización — véase [Administrador de empresa](company-admin.md). |
| **Marketplace** | Aplicaciones del ecosistema. |

---

## Antes de su primera emisión

- **Su organización está dada de alta y su KYC está aprobado.** Un emisor con el KYC caducado no puede emitir.
- **Conoce su jurisdicción.** No es una etiqueta — selecciona todo el cuerpo de reglas que se aplica al instrumento durante su vida. [Marcos jurídicos](../../legal/index.md).
- **Tiene un ISIN, si lo necesita.** Registerwerk exige su unicidad pero no los asigna; se obtiene de su agencia nacional de codificación. Puede seguir adelante sin él, pero interoperar con el mundo exterior se vuelve más difícil.
- **Ha decidido quién puede mantenerlo.** ¿Oferta pública? ¿Solo inversores profesionales? ¿Una sola jurisdicción? Eso determina su estándar de token, y cambiarlo después significa un instrumento nuevo.

---

## Crear una emisión

*Issuances → New Issuance.* Tres pasos.

=== "1. Características"

    Nombre, ISIN, jurisdicción y la economía del instrumento. Para un bono: valor nominal, divisa, fechas de emisión y vencimiento, tipo de cupón, base de cálculo de intereses, frecuencia de pago, amortizabilidad anticipada y precio de emisión como fracción del nominal.

    **El precio de emisión** importa en los bonos cupón cero: no pagan intereses y compensan al inversor vendiéndose bajo par — compra a 800 €, recibe 1.000 € al vencimiento. Por defecto es `1.0`.

    **La base de cálculo de intereses** (ACT/360, ACT/365, 30/360…) decide cómo un año parcial se convierte en fracción al calcular los intereses. Es poco vistosa y cambia el dinero.

=== "2. Cadena y estándar"

    Qué blockchain y qué estándar de token.

    Para un valor regulado la respuesta suele ser [ERC-3643](../../token-standards/erc3643.md), porque es el que impone *quién puede mantener esto* dentro del propio token. [ERC-20](../../token-standards/erc20.md) es más simple y se entiende en todas partes, pero no tiene noción de elegibilidad — quien recibe una unidad es su dueño.

    Otras formas: ERC-1155 para muchas series en un solo contrato, ERC-3525 para instrumentos semifungibles, ERC-4626/7540 para fondos y bóvedas, DAML sobre Canton donde se exige confidencialidad frente a las contrapartes, SPL-2022 sobre Solana.

    [:octicons-arrow-right-24: Elegir un estándar de token](../issuers/token-standards.md)

=== "3. Revisar y enviar"

    Compruébelo y envíelo. El estado pasa de `DRAFT` a `PENDING_APPROVAL` y **la edición se detiene**.

---

## Aprobación

El operador revisa. Después:

| | |
|---|---|
| **Aprobada** | `APPROVED`. Condiciones bloqueadas. Puede desplegar. |
| **Rechazada** | Vuelve a `DRAFT` con un motivo registrado. Edite y vuelva a enviar. |

No existe un estado `REJECTED` — una emisión rechazada regresa a borrador, donde es editable. El motivo queda registrado en la [pista de auditoría](../../platform/audit-log.md).

---

## Desplegar

*Issuance → Deploy.* Registerwerk envía la transacción y registra la dirección del contrato. En ERC-3643 esto despliega la suite completa — token, identity registry, trusted issuers registry, cumplimiento — cableada entre sí.

El contrato ya existe y mantiene **cero unidades**.

[:octicons-arrow-right-24: Desplegar en una blockchain](../issuers/deploying-to-chain.md)

---

## Admitir a los inversores

*Issuance → Investors.* Cada inversor debe ser una entidad con KYC aprobado y un monedero registrado, inscrita en el identity registry.

!!! warning "Es un requisito previo, no papeleo"
    Bajo ERC-3643 un monedero no admitido **no puede recibir tokens** — la transmisión falla on-chain. Acuñar antes de admitir produce transacciones fallidas y nada más.

Elija el tipo de inscripción para cada titular:

- **Colectiva** (*Sammeleintragung*, inscripción colectiva) — un depositario mantiene por cuenta de muchos inversores subyacentes.
- **Individual** (*Einzeleintragung*, inscripción individual) — el inversor se nombra directamente, mediante una referencia seudonimizada. El §17(2) eWpG exige contenido adicional: derechos de terceros, restricciones de disposición, notas sobre capacidad jurídica. El §19(2) le obliga a remitir extractos registrales a los titulares consumidores.

Un activo puede sostener ambas formas a la vez.

[:octicons-arrow-right-24: Gestionar a sus inversores](../issuers/managing-investors.md)

---

## Acuñar y emitir

*Issuance → Mint.* Las unidades pasan a existir y se asignan a los titulares. Después `APPROVED` → `ISSUED` y el instrumento está vivo.

!!! danger "Acuñar crea valor de la nada"
    Un error aquí no es una cifra equivocada en un informe — son valores reales en manos equivocadas.

    Las reglas de control de acuñación pueden limitar cuánto podrá recibir jamás una dirección, la acción exige [autenticación reforzada](../../compliance/step-up-mfa.md), y toda acuñación queda registrada con un actor nombrado.

---

## Convivir con ello: cinco años de administración

Esta es la parte que se subestima. La emisión dura una semana. La administración, el resto de la década.

### Operaciones societarias

Los cupones y, al final, la amortización se crean automáticamente a partir del calendario de pagos y avanzan por sus fechas — usted no los crea.

Los dividendos, los desdoblamientos (splits) y las amortizaciones anticipadas son distintos: usted los **propone** (*Emisión → Operaciones societarias → Proponer*), y un operador revisa la propuesta — aprobándola en el registro, o rechazándola — antes de que continúe.

Sea cual sea el origen de la operación, la liquidación exige la conformidad de dos partes separadas: **usted certifica** que la obligación subyacente está lista — el efectivo de un cupón o dividendo, el mecanismo de un split o una amortización anticipada — y después **un operador confirma** el lado del registro/on-chain. Certificar es una acción autenticada normal, no requiere [verificación reforzada](../../compliance/step-up-mfa.md) — solo la confirmación del operador la exige. Si usted nunca certifica, un operador puede anular el requisito; esa anulación queda registrada como una excepción distinta y permanentemente visible, nunca indistinguible de una certificación genuina.

Las tres fechas que deciden quién cobra: la **fecha de registro** (quien mantiene en ese instante tiene derecho), la **fecha ex-cupón** (a partir de ahí se negocia sin el pago), la **fecha de pago** (el dinero se mueve).

[:octicons-arrow-right-24: Las operaciones societarias en detalle](../lifecycle/redemption.md)

### Vigilar su lista de titulares

Sus inversores negocian entre sí y usted no puede impedirlo. Lo que obtiene es visibilidad: el registro se actualiza y su lista de titulares cambia.

Atención a los **límites de titularidad**, si su instrumento los tiene — una regla de cumplimiento que hace fallar las transmisiones una vez alcanzado un tope. Los inversores lo viven como un fallo inexplicado, así que conocer los propios límites ahorra tráfico de soporte.

### Extractos registrales

Para los titulares consumidores con inscripción individual, los extractos del §19(2) se generan y se conservan como documentos del registro. Reproducibles años después, porque un extracto que no puede volver a producirse no es prueba.

### Suspensión

`ISSUED` → `SUSPENDED` congela la negociación sin poner fin al instrumento — por una operación societaria, una disputa o un error sospechado. Reversible.

### Amortización

Al vencimiento: fotografía de posiciones, derechos, su certificación y la confirmación de un operador, pago, tokens destruidos, `REDEEMED`. Terminal — de ahí no se sale.

Las filas de titulares se **borran lógicamente, nunca se eliminan**: una inscripción del §16 que desaparece no puede satisfacer obligaciones de conservación ni de prueba de manipulación.

---

## Cosas que le sorprenderán

!!! info "No puede bloquear una operación lícita entre titulares elegibles"
    Una vez emitido, el instrumento se negocia bajo sus propias reglas de cumplimiento. Usted fija esas reglas en la emisión; no juzga operaciones individuales.

!!! info "No puede editar una emisión aprobada"
    Las condiciones se bloquean con la aprobación. Un cambio significa una emisión nueva, o una corrección del operador con pista de auditoría.

!!! info "El KYC de sus inversores no es su criterio"
    El operador verifica a las entidades. No puede admitir a un inversor que el operador no haya aprobado, por bien que lo conozca.

!!! info "Una transferencia forzosa necesita al operador"
    Las correcciones del §24 eWpG — una clave perdida, una resolución judicial, una inscripción errónea — son actuaciones del operador bajo doble control, no algo que ejecute usted.

---

## Adónde ir ahora

- [La vida de un valor](../lifecycle/index.md) — el arco completo, de principio a fin
- [Elegir un estándar de token](../issuers/token-standards.md)
- [Administrador de empresa](company-admin.md) — gestionar los usuarios de su organización
