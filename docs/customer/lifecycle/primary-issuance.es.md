---
title: 2. Emisión primaria
description: Desplegar el contrato, admitir a los inversores y crear los títulos — el momento en que un valor viene a la existencia.
---

# Etapa 2 — Emisión primaria

*El bono está aprobado. Ahora tiene que hacerse real.*

La **emisión primaria** es la operación entre el emisor y los primeros inversores: el único momento en que Nordwind recibe dinero. Todo lo posterior — cada negociación, cada préstamo — ocurre entre inversores. El balance de Nordwind no se ve afectado.

Conviene retener esta distinción: explica por qué esta etapa está tan controlada y las siguientes son comparativamente libres.

---

## El orden de las operaciones

```mermaid
graph TB
    A["1 Desplegar el contrato<br/><small>un recipiente vacío on-chain</small>"] --> B["2 Admitir inversores<br/><small>quién puede tenerlo</small>"]
    B --> C["3 Acuñar<br/><small>los títulos nacen</small>"]
    C --> D["4 Emitir<br/><small>el registro entra en servicio</small>"]
```

El orden no es arbitrario. Con ERC-3643, un inversor no admitido **no puede recibir tokens** — la transmisión se revierte. Acuñar antes de admitir solo produce transacciones fallidas.

---

## 1. Desplegar el contrato

*Issuances → su emisión → Deploy.*

Registerwerk envía la transacción que inscribe el contrato en la blockchain elegida y registra la dirección resultante. En ERC-3643 no se trata de un contrato, sino de toda la suite — token, identity registry, trusted issuers registry, compliance — cableados entre sí.

Obtendrá un **hash de transacción** (el recibo) y una **dirección de contrato** (donde reside ahora el bono). Ambos son públicos; cualquiera puede consultarlos en un explorador de bloques.

En este punto el contrato existe y contiene **cero títulos**. Nadie posee nada.

??? note "Para especialistas: direcciones deterministas"

    La factoría despliega con `CREATE2`, de modo que la dirección del contrato es una función pura del desplegador, una sal y el bytecode. Puede calcularse *antes* del despliegue.

    No es un truco. Significa que la dirección puede anotarse en el registro, comunicarse a las contrapartes y citarse en contratos antes incluso de que la transacción se mine — y que un despliegue fallido y reintentado acaba en la misma dirección. Los sistemas posteriores no necesitan esperar un recibo para saber dónde mirar.

    [:octicons-arrow-right-24: Desplegar en una blockchain](../issuers/deploying-to-chain.md)

---

## 2. Admitir a los inversores

*Issuance → Investors → Add investor.*

El colocador de Nordwind ha encontrado compradores. Antes de que cualquiera de ellos pueda recibir un solo título, tiene que ser admitido:

1. **Su entidad debe estar dada de alta y con el KYC aprobado.** No a juicio del emisor, sino del operador. Véase [Revisar el KYC](../../operator/customers/kyc-process.md).
2. **Debe registrar una dirección de monedero** (un *punto final*) donde recibir. Véase [Conectar un monedero](../investors/wallet-setup.md).
3. **Se le inscribe en el identity registry**, que es lo que lo admite on-chain.

Solo entonces puede tener el bono.

!!! warning "Este es el paso que se subestima"
    Admitir inversores no es papeleo que se pueda dejar para después. Es un requisito previo impuesto por el propio contrato del token. Un emisor que ha acuñado antes de admitir se queda con un contrato lleno de títulos y ninguna forma lícita de moverlos.

### Qué contiene una inscripción registral

Cada inversor admitido pasa a ser **titular** — una fila del registro. Conforme al §16 eWpG, ese es el asiento que cuenta, y el Derecho alemán conoce dos formas:

=== "Inscripción colectiva (Sammeleintragung)"

    El registro nombra a un **depositario** que mantiene por cuenta de muchos inversores subyacentes. El registro ve al depositario; el depositario lleva sus propios libros para sus clientes.

    El modelo familiar, y la forma en que hoy se mantienen la mayoría de los valores institucionales.

=== "Inscripción individual (Einzeleintragung)"

    El registro nombra **directamente al inversor**, identificado por una referencia seudónima en lugar de por un nombre en claro on-chain.

    El §17(2) eWpG exige más contenido para estos asientos: derechos de terceros sobre la posición, restricciones de disposición y cualquier nota sobre la capacidad jurídica del titular. Y el §19(2) obliga al emisor a remitir un **extracto registral** (*Registerauszug*) a los titulares consumidores — tras la inscripción inicial, tras cada cambio que les afecte y al menos una vez al año.

    Registerwerk genera y conserva esos extractos como documentos registrales por derecho propio, porque un extracto que no puede reproducirse después no prueba nada.

Un mismo activo puede llevar ambas formas a la vez — el registro lo llama posición `MIXED`.


!!! info "Las inscripciones las realiza el operador"
    Una inscripción, y cualquier cambio de un atributo del §17(2), la realiza el operador del registro conforme a una instrucción registrada: **quién la dio** (titular, beneficiario, tribunal, administrador concursal, cambio de condiciones del emisor) y una **referencia**. El emisor no edita el registro; presenta una *solicitud* que el operador ejecuta o rechaza. Retirar un derecho o una restricción requiere una acción explícita y un segundo aprobador, y se conservan los valores anterior y posterior.

---

## Suscripciones: de la orden al registro

Los inversores suscriben a través del portal; el registro ya no se rellena tecleando posiciones en un diálogo. Una orden recorre estos estados:

```mermaid
stateDiagram-v2
    direction LR
    SUBMITTED --> ALLOCATED: allocate
    ALLOCATED --> PAYMENT_CONFIRMED: accepted and paid
    PAYMENT_CONFIRMED --> SETTLED: settle
    ALLOCATED --> LAPSED: not paid in time
    ALLOCATED --> RELEASED: released
    SUBMITTED --> REJECTED: reject
```

1. **Enviar.** Cualquier inversor incorporado puede enviar una orden mientras el activo esté abierto a la suscripción (`APPROVED` o `ISSUED`) y el inversor esté dentro del mercado objetivo MiFID.
2. **Asignar.** El emisor o el operador asigna, en su totalidad o de forma reducida. Las asignaciones cuentan contra el tamaño de la emisión y contra el máximo de tenencia del inversor **junto con sus demás asignaciones abiertas**, de modo que dos asignaciones paralelas no puedan quedar cada una por debajo del límite.
3. **Aceptar.** El inversor acepta la asignación. Todavía no se inscribe nada en el registro. En un bono se muestran el importe a pagar (unidades asignadas × valor nominal × precio de emisión), una referencia de pago y un plazo de pago — por defecto 10 días hábiles TARGET. Una asignación no pagada a tiempo **caduca** y libera su capacidad; el emisor o el operador también pueden **liberarla**.
4. **Confirmar el pago.** El emisor o el operador confirma que el dinero ha llegado (requiere [step-up](../../compliance/step-up-mfa.md)). Un pago insuficiente se rechaza; un pago excesivo se acepta y se muestra como *reembolso pendiente*.
5. **Liquidar.** Antes de escribir nada se repiten las mismas comprobaciones que en la liquidación de una operación: KYC aprobado, ninguna coincidencia de sanciones sin resolver, ningún [Sperrvermerk](holding.md), registro no congelado por un traspaso, finalidad de la cadena, mercado objetivo y límite de tenencia. Después se emiten las unidades. Si el activo está **desplegado**, las unidades se acuñan en la wallet del inversor y la sincronización de titulares las abona en el registro cuando la transferencia está indexada. En caso contrario, el registro se abona directamente; una segunda suscripción en la misma wallet incrementa la inscripción existente.

!!! note "Puntos abiertos"
    Un estándar de token sin acuñación automática deja la orden en *pago confirmado*: un operador emite las unidades. Para activos sin condiciones de bono, el operador introduce el importe recibido; no hay precio calculado. En las inscripciones colectivas se inscribe al inversor, no a un depositario, como titular; esa cuestión aún no está decidida.

---

## 3. Acuñar

*Issuance → Mint.*

**Acuñar** es crear unidades que antes no existían y asignarlas a un titular. Es el momento en que el valor viene a la existencia.

Nordwind acuña 50.000 títulos repartidos entre sus inversores en las proporciones suscritas. La oferta total del contrato pasa de cero a 50.000. Cada asiento registral recoge el valor nominal que tiene el inversor.

!!! danger "La acuñación es el filo más afilado del sistema"
    Acuñar crea valor de la nada. Un error aquí no es una cifra equivocada en un informe — son valores reales en las manos equivocadas.

    Por eso Registerwerk lo trata como una operación controlada: las **reglas de control de acuñación** pueden limitar cuánto podrá recibir jamás una dirección concreta, la operación exige [autenticación reforzada](../../compliance/step-up-mfa.md), y cada acuñación queda en el registro de auditoría con la persona que la realizó.

### Adónde va el dinero

Fíjese en lo que la plataforma **no** ha hecho: no ha movido 50 millones de euros.

La pata de efectivo de una emisión primaria — los inversores pagando a Nordwind — es una cuestión de pagos, y Registerwerk admite varias respuestas, llamadas **vías de pago**:

| Vía | Qué es |
|---|---|
| **Stablecoin** | Un token que representa una divisa, circulando en la misma cadena que el valor. |
| **Pontes** | Una API de pago bancario instantáneo. |
| **DvP ERC-7573** | Un contrato de liquidación que hace cada pata condicionada a la otra. |
| **SEPA fuera de cadena** | Una transferencia bancaria ordinaria, conciliada por referencia. |

La tercera merece atención. La **entrega contra pago** elimina el riesgo más antiguo de la liquidación de valores: que una parte cumpla y la otra no. Con entrega contra pago, el valor se mueve *si y solo si* se mueve el pago — no como promesa, sino como propiedad de la transacción.

??? note "Para especialistas: la entrega contra pago, y lo que no prueba"

    `DvpSettlement.sol` implementa un esquema al estilo ERC-7573. Una parte bloquea su pata en depósito en garantía; la contraparte liquida después ambas patas en una sola transacción, o la operación expira y el depósito se devuelve. Al liquidar, su cliente entrega una huella de los términos pactados (partes, importes, tokens, vencimiento): si lo bloqueado difiere en cualquier detalle, no se mueve nada. La liquidación también se detiene mientras cualquiera de las partes esté congelada por los controles de cumplimiento del token. `EwpgBondDesk` muestra la misma forma de «token y pago en la misma transacción».

    Dos matices honestos:

    **La atomicidad es por cadena.** Si el valor está en Ethereum y el dinero llega por SEPA, ningún contrato puede hacerlos atómicos. Lo que la entrega contra pago aporta allí es una liberación condicionada, no una única transacción. La atomicidad real exige ambas patas en la misma cadena.

    **La liquidación técnica no es la liquidación jurídica.** Que un contrato ejecute ambas transmisiones en una transacción prueba lo que hizo un ordenador. Si eso constituye extinción de la obligación, firmeza frente a un concurso o buena entrega según su ley aplicable es una cuestión jurídica que el código no resuelve.

    Las vías de stablecoin llevan campos de divulgación vinculados a MiCAR — emisor, autorización, condición de token de dinero electrónico, reembolso a la par, libro blanco — más una atestación auditable del operador de que alguien los comprobó realmente. Registerwerk no verifica nada de eso de forma independiente. [:octicons-arrow-right-24: Vías de pago](../../platform/defi-interoperability.md)

---

## 4. Emitir

La transición final: `APPROVED` → `ISSUED`.

El bono está vivo. El registro hace fe. Los inversores ven sus posiciones, reciben sus extractos y pueden — a partir de aquí — negociar.

```mermaid
stateDiagram-v2
    direction LR
    APPROVED --> ISSUED: emitir
    ISSUED --> SUSPENDED: suspender
    SUSPENDED --> ISSUED: reactivar
    ISSUED --> REDEEMED: amortizar
    SUSPENDED --> REDEEMED: amortizar
    note right of ISSUED
        Está aquí.
        Vivo y negociable.
    end note
```

`SUSPENDED` congela la negociación sin poner fin al instrumento — por una operación societaria, un litigio o un error sospechado. Reversible. `REDEEMED` no lo es.

---

## Lo que acaba de ocurrir, en un párrafo

Nordwind describió un bono, un operador lo aprobó, se desplegó un contrato, se verificó y admitió a los inversores en ese contrato, se crearon 50.000 títulos a su nombre y el registro lo anotó todo. Nordwind tiene 50 millones de euros. Cincuenta inversores tienen un derecho de crédito frente a Nordwind. Y cada paso es imputable a una persona con nombre y apellidos, en un registro que nadie puede alterar sin que se note.

[Etapa 3: Tenencia y custodia :octicons-arrow-right-24:](holding.md){ .md-button .md-button--primary }
