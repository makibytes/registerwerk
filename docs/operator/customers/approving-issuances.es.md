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

### Convenciones del cronograma de cupones { #coupon-schedule-conventions }

Al guardar los términos del bono se genera el cronograma de cupones con el que trabajan los procesos de eventos corporativos. Las convenciones siguen la práctica ICMA; cada una se define en los términos, con estos valores predeterminados:

| Parámetro | Predeterminado | Efecto |
|---|---|---|
| Base de cálculo | ACT/ACT (ICMA) | Un periodo regular devenga exactamente 1/frecuencia; un primer periodo corto devenga sus días reales sobre el periodo regular nocional. Están disponibles ACT/360, ACT/365 (fijo), 30/360 y 30E/360. |
| Cronograma | Hacia atrás desde el vencimiento, primer periodo corto | Las fechas regulares se cuentan hacia atrás desde el vencimiento; un periodo irregular queda al principio. Si el vencimiento es fin de mes, cada fecha de cupón es fin de mes. |
| Convención de día hábil | Modified Following | Una fecha de pago en día inhábil pasa al siguiente día hábil, salvo que eso cambie de mes; entonces, al anterior. El devengo usa siempre las fechas sin ajustar. |
| Calendario de festivos | TARGET2 | Fines de semana, 1 de enero, Viernes Santo, Lunes de Pascua, 1 de mayo, 25 y 26 de diciembre. |
| Fecha de registro | 1 día hábil antes del pago | Quien figure en el registro al final de ese día recibe el cupón. |
| Anuncio | 5 días hábiles antes de la fecha de registro | Cuándo se anuncia el cupón. |
| Periodos de gracia | 30 días intereses, 7 días principal | Cuánto tiempo tras la fecha de pago un importe impagado solo está vencido. |

El cupón por unidad es valor nominal × tasa de cupón × fracción de días, sin redondear; solo se redondea el derecho de cada titular. Un cupón variable no tiene importe hasta que se fija su tipo y no se anuncia antes. Solo se generan fechas de pago futuras, así que unos términos introducidos tarde no crean cupones con fecha pasada. El cronograma aparece en la pestaña **Corporate Actions** del activo.

### Cómo se generan los cupones y el reembolso

- **Anuncio.** El cupón (y el reembolso final) se genera automáticamente en su *fecha de anuncio*, no en la fecha de pago, para dejar tiempo de atestiguar y confirmar antes del pago. El reembolso sigue la misma regla: fecha de pago = vencimiento ajustado por la convención de día hábil.
- **Fecha de registro.** Los derechos se fijan al **final de la fecha de registro** (Europe/Berlin), según el registro tal como estaba entonces. Las transferencias posteriores no los modifican. En activos desplegados en una cadena, la instantánea espera a que el registro esté conciliado más allá de la fecha de registro y se rechaza («unmapped at record date») si una wallet con unidades en ese momento no tiene entrada en el registro. La fecha de registro de un dividendo, split o amortización anticipada debe seguir en el futuro al proponerse y al aprobarse (como pronto, el siguiente día hábil).
- **Redondeo.** El derecho de cada titular se redondea a la unidad mínima de la moneda (half-even); la confirmación muestra el total pagado y la diferencia de redondeo.
- **Vencido, incumplido, impago.** Un importe impagado tras la fecha de pago aparece primero como **OVERDUE** (solo operadores; los clientes ven «pago pendiente»). Solo tras el plazo de gracia (30 días intereses, 7 días principal) un cupón pasa a **MISSED** y un bono a **DEFAULTED**. La liquidación borra cada uno de estos estados: un reembolso liquidado deja el bono en **REDEEMED**, una amortización anticipada liquidada en **CALLED**, un cupón liquidado es **PAID**.
- **Control dual.** El emisor atestigua; un operador confirma. Un operador nunca puede atestiguar como emisor: la vía del operador es *Override attestation* (step-up y motivo, auditado por separado), también al suplantar. Una propuesta debe aprobarla una persona distinta de quien la propuso.
- **Derechos retenidos.** Los derechos de los pools de nominados (look-through) no se pagan y mantienen abierta una acción liquidada, marcada «held entitlements outstanding», hasta que se resuelvan.
- **Orden de los procesos.** 05:30 cupones, 05:45 reembolsos, 06:00 transiciones diarias (Europe/Berlin): una acción generada por la mañana se procesa en la misma ejecución.

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

    Los términos solo pueden fijarse en bloque (con step-up) hasta la emisión, y el ISIN, la moneda, el importe de emisión, la denominación y las fechas ya no se pueden cambiar en el formulario de edición una vez aprobado. Los cambios posteriores son **modificaciones**: *Editar activo → Amend terms* pide la base jurídica, step-up y un segundo operador, registra cada valor anterior/posterior en el registro de auditoría y regenera los cupones futuros aún no anunciados. Los cupones pagados o ya anunciados nunca se reescriben. El valor nominal, el cupón y el vencimiento de un bono Canton desplegado no se pueden modificar aquí: están fijados en el instrumento del ledger.

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


### Órdenes de suscripción e inscripciones registrales

Los inversores suscriben a través del portal. Usted (o el emisor) trabaja la cola en la pestaña **Subscription orders** del activo:

1. **Asignar**, en su totalidad o reducida. El tamaño de la emisión y el máximo de tenencia del inversor (incluidas sus otras asignaciones abiertas) se comprueban bajo bloqueo, de modo que asignaciones paralelas no pueden excederse.
2. Espere a que el inversor **acepte**. La asignación lleva entonces un plazo de pago (por defecto 10 días hábiles TARGET). Si no se paga a tiempo, un proceso programado la marca como **caducada** y libera la capacidad.
3. **Confirme el pago** cuando el dinero esté en la cuenta ([step-up](../../compliance/step-up-mfa.md)). En un bono el importe a pagar es unidades asignadas × valor nominal × precio de emisión: un pago insuficiente se rechaza y uno excesivo se registra como *reembolso pendiente*; el reembolso en sí es un pago manual. Para activos sin condiciones de bono, usted introduce el importe recibido.
4. **Liquidar.** Se comprueban de nuevo KYC, cribado de sanciones, Sperrvermerk, congelación del registro, finalidad, mercado objetivo y límite de tenencia. En un activo ERC-20 o ERC-3643 desplegado, las unidades se acuñan y la sincronización de titulares las abona en el registro cuando la transferencia está indexada; en otros estándares desplegados la orden permanece como *pagada* hasta que usted emita las unidades manualmente. Sin despliegue, el registro se abona directamente.
5. **Liberar** devuelve una asignación con un motivo; en una orden pagada, el pago se marca como reembolso pendiente.

!!! note "Controles de la parte, wallet de liquidación y term sheet (fase 6)"
    El envío y la liquidación de una orden pasan por el mismo control de parte que la liquidación de operaciones: la entidad debe estar activa, con KYC aprobado y no vencido, sin coincidencias de screening sin resolver (entidad y beneficiarios finales) ni Sperrvermerk. La wallet de la orden debe ser una vinculada por la entidad (desafío de vinculación firmado) o ya mantenida para este activo; de lo contrario la orden se rechaza con «bind the wallet first». El screening de sanciones de la dirección por un proveedor de analítica blockchain no forma parte de este control (aparcado, T6-19).

    El term sheet público (`/api/v1/public/assets/{isin}/termsheet`) solo se sirve para activos emitidos, suspendidos o amortizados, siempre en la misma versión determinista (la que coincide con el hash on-chain; si no, la primera subida) con su hash de contenido y número de versión. Tras la emisión se rechaza una nueva subida del term sheet por el emisor; sustituirlo requiere una enmienda del operador (`POST /api/v1/assets/{id}/documents/term-sheet-amendment`, step-up y segundo aprobador) que conserva la versión anterior marcada como sustituida. Si un term sheet debe ser público antes de abrir la oferta es una decisión aparcada (T6-18). Las exportaciones CSV anteponen un apóstrofo a las celdas que empiezan por `=`, `+`, `-` o `@` para que las hojas de cálculo no las ejecuten; los números simples no cambian.

Las inscripciones registrales las hace usted, no el emisor. En la pestaña **Holders** del activo, *Add register entry* y *Change §17(2) attributes* exigen una instrucción (quién y una referencia); un campo vacío significa sin cambio, y retirar un derecho requiere su propia casilla y un segundo aprobador. Los emisores lo solicitan mediante *solicitudes de cambio*, que usted ejecuta (cuatro ojos) o rechaza. En un activo desplegado, una inscripción manual es solo una asignación de wallet con nominal 0.

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
