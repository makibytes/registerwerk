---
title: Glosario
description: Cada término empleado en esta documentación, definido con sencillez.
---

# Glosario

Definiciones sencillas. Cuando un término tiene un sentido técnico preciso distinto del uso corriente, se señala la diferencia.

---

## Finanzas

**Amortización**
: Devolver el principal de un valor y retirarlo.

**Bono**
: Un préstamo dividido en partes iguales para que muchos prestamistas puedan tomar una cada uno. El prestatario paga intereses y devuelve el valor nominal al vencimiento.

**Bono cupón cero**
: Un bono que no paga intereses y que, a cambio, se vende por debajo del valor nominal. Compra a 800 €, recibe 1.000 € al vencimiento.

**Cupón**
: El interés que paga un bono. El nombre sobrevive de los bonos en papel, donde se recortaba un cupón del título para reclamar cada pago.

**Depositario**
: Una entidad que mantiene valores por cuenta de otros. En una inscripción colectiva, el depositario es el titular inscrito.

**Emisor**
: La organización que crea un valor y debe lo que promete.

**Entrega contra pago**
: Liquidar de modo que el valor se mueva si y solo si se mueve el pago. Elimina el riesgo de que una parte cumpla y la otra no.

**Fecha de registro**
: El instante en que se fotografía el registro para decidir a quién le corresponde un pago. Si mantiene en esa fecha, el pago es suyo aunque venda mañana.

**Fecha ex-cupón**
: A partir de esta fecha un valor se negocia *sin* un pago próximo. Si compra después, el pago es del vendedor.

**Garantía** (también *colateral*)
: Algo de valor pignorado para asegurar un préstamo. Si el prestatario no devuelve, el prestamista puede venderlo.

**Liquidación** (*settlement*)
: Completar una operación — que los valores y el dinero cambien efectivamente de manos. Distinto de acordarla.

**Liquidez**
: Con qué facilidad algo se convierte en efectivo sin mover su precio. Un valor que nadie quiere comprar es ilíquido.

**LLTV**
: El umbral de relación préstamo-valor por encima del cual un préstamo puede ejecutarse.

**Mercado primario**
: El emisor vendiendo a los inversores. El dinero llega al emisor. Ocurre una vez.

**Mercado secundario**
: Los inversores vendiéndose entre sí. El emisor no es parte y no recibe nada.

**Operación con pacto de recompra** (*repo*)
: Una venta con recompra pactada a un precio superior. Económicamente un préstamo garantizado; la diferencia de precio es el interés. Se estructura como venta porque la propiedad plena resiste la insolvencia mejor que una garantía real.

**Operación societaria**
: Cualquier cosa que hace un emisor y que afecta a los titulares en cuanto titulares — pagar un cupón, desdoblar unidades, devolver el principal.

**Punto básico (pb)**
: Una centésima de punto porcentual. 100 pb = 1 %. Se usa porque «el tipo subió un 1 %» es ambiguo — ¿del 4 % al 5 %, o del 4 % al 4,04 %? Los puntos básicos eliminan la ambigüedad.

**Relación préstamo-valor (LTV)**
: Cuánto ha tomado prestado como porcentaje del valor de su garantía. Tome 50.000 € contra 100.000 € de garantía y su LTV es del 50 %.

**Valor nominal** (también *nominal*, *par*)
: El importe que figura en el instrumento — lo que se amortiza al vencimiento. **No** el precio. Un bono de 1.000 € puede cotizar a 960 €.

**Valor nominal mantenido**
: El valor nominal que mantiene un titular. Lo que anota el registro. No el valor de mercado.

**Vencimiento**
: La fecha en que un bono termina y se amortiza su valor nominal.

---

## Blockchain

**Acuñación** (*minting*)
: Crear tokens que no existían. Lo contrario de la destrucción.

**Blockchain**
: Un libro compartido, mantenido por muchas partes, en el que las anotaciones no pueden alterarse en silencio una vez registradas.

**Clave privada**
: El secreto que autoriza actuaciones desde una dirección de monedero. No puede restablecerse, recuperarse ni reemitirse. Perderla es perder la capacidad de mover los tokens.

**Contrato inteligente** (*smart contract*)
: Un programa en una blockchain. Se ejecuta exactamente como está escrito, cuando se le invoca, sin que nadie decida permitírselo.

**Destrucción** (*burning*)
: Destruir tokens. La oferta disminuye. Irreversible.

**Dirección de contrato**
: Dónde reside un contrato inteligente en una cadena. Pública; cualquiera puede inspeccionarla.

**ERC-20**
: El estándar común de token fungible. Simple y admitido en todas partes, **sin** noción alguna de quién puede mantenerlo.

**ERC-3643** (también *T-REX*)
: Un estándar de token para valores regulados. Comprueba la elegibilidad antes de cada transmisión y hace fallar on-chain las que no cumplen.

**Gas**
: La comisión que se paga para que se procese una transacción.

**Hash de transacción**
: El identificador de una transacción. Su recibo; búsquelo en un explorador de bloques.

**Mainnet / testnet**
: La red real, donde el valor es real. Y la red de práctica, donde no lo es.

**Monedero** (*wallet*)
: Software que custodia una clave privada. No contiene tokens — es el contrato el que anota un saldo frente a su dirección.

**ONCHAINID**
: Un contrato de identidad on-chain que guarda las atestaciones verificadas de una parte bajo ERC-3643.

**Revert**
: Una transacción que falla y se deshace por completo. Una comprobación de cumplimiento que falla provoca un revert — no ocurre nada a medias.

**Stablecoin**
: Un token pensado para mantener un valor estable frente a una divisa.

**Token**
: Una unidad anotada en un contrato inteligente. Aquí, la representación on-chain de un valor — el mecanismo, no el valor en sí.

---

## Registerwerk

**Activo** (*asset*)
: Un valor en el registro. Formalmente: la anotación que el registro hace de un instrumento.

**Autenticación reforzada** (*step-up*)
: Exigir una prueba de identidad fresca para una actuación sensible, más allá de una sesión ya abierta.

**Denegación por defecto** (*fail closed*)
: Cuando una comprobación no puede ejecutarse, rechazar en lugar de permitir. El filtrado de sanciones funciona así — una caída significa transmisiones rechazadas, no dejadas pasar sin comprobar.

**Entidad jurídica**
: Una organización en el registro. Los usuarios pertenecen a una; la verificación y los permisos se adhieren a ella.

**Espacio de trabajo**
: Una vista del portal del cliente que agrupa las herramientas de un oficio — Investor, Trader o Issuer. Navegación, **no** permiso.

**Extracto registral** (*Registerauszug*)
: Un estado del contenido del registro referido a un titular. Conforme al §19(2) eWpG, se debe a los titulares consumidores con inscripción individual. Un documento del registro conservado, no una notificación.

**Indexador**
: Software que observa las blockchains y escribe en el registro lo que ve.

**Manifiesto**
: El JSON firmado que describe una dApp del mercado. Su hash se ancla on-chain al aprobarla.

**Modo soporte** (*impersonation*)
: Un operador actuando dentro del portal de un cliente para darle asistencia. Toda actuación se atribuye al **operador**, nunca al cliente.

**Operador**
: La organización que explota el registro. Aprueba entidades y emisiones, y ostenta las facultades de rectificación registral.

**Pista de auditoría**
: El registro a prueba de manipulación de toda operación que cambia el estado. Encadenado por hash, de modo que una alteración es detectable.

**Principio de doble control**
: Exigir dos personas distintas. Se aplica a las operaciones más afiladas.

**Punto final** (*endpoint*)
: Una dirección de monedero que usted ha registrado ante el registro, con una etiqueta.

**Registro**
: La base de datos del operador que anota quién mantiene qué. **La anotación jurídicamente relevante**, distinta del token.

**Sperrvermerk**
: Una restricción anotada sobre una inscripción conforme al §16 eWpG. Mientras subsiste, la posición no puede transmitirse. Sigue siendo suya.

**Tipo de inscripción**
: Si una inscripción es *colectiva* (un depositario mantiene por cuenta de muchos) o *individual* (el inversor se nombra directamente).

**Titular**
: Una inscripción registral que anota que alguien mantiene un importe de un valor.

**Transferencia forzosa**
: Una rectificación ejecutada por el operador que traslada una posición entre monederos, conforme al §24 eWpG. El remedio ante una clave perdida o una resolución judicial. Exige doble control.

**Vía de pago**
: Un medio admitido para mover la pata de efectivo — stablecoin, API de pago inmediato, liquidación de entrega contra pago, o transferencia bancaria.

---

## Regulación

**DORA**
: Reglamento europeo sobre riesgo tecnológico y resiliencia operativa de las entidades financieras.

**eWpG**
: La ley alemana de valores electrónicos, en vigor desde junio de 2021. Permite que un valor exista como inscripción registral en lugar de como título en papel.

**Folleto**
: El documento informativo de una oferta pública de valores. Existen exenciones — habitualmente para ofertas restringidas a inversores profesionales.

**KYC**
: *Know Your Customer*, el conocimiento del cliente. Verificar con quién se está tratando.

**MiCAR**
: Reglamento europeo que cubre a los emisores de criptoactivos y a los prestadores de servicios.

**MiFIR**
: Reglamento europeo del que deriva el reporte de operaciones.

**Prevención del blanqueo** (*AML*)
: Las reglas que impiden que el sistema financiero encubra fondos de origen delictivo.

**RGPD / DSGVO**
: La normativa europea de protección de datos.

**Travel Rule**
: La exigencia de que la información de ordenante y beneficiario viaje con una transmisión. El equivalente cripto de lo que un banco envía con una transferencia.

**§16 eWpG**
: El contenido del registro y su eficacia jurídica.

**§17(2) eWpG**
: El contenido adicional exigido para las inscripciones individuales.

**§19(2) eWpG**
: La obligación de facilitar extractos registrales a los titulares consumidores.

**§24 eWpG**
: La rectificación del registro — la base de las transferencias forzosas.
