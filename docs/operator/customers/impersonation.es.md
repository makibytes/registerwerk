---
title: Modo soporte — ver lo que ellos ven
description: Actuar dentro del portal de un cliente para darle asistencia: cómo funciona, a quién se atribuye, cuáles son sus límites y cómo gobernarlo.
---

# Modo soporte — ver lo que ellos ven

Un cliente dice que el Trading Desk no le deja publicar una tenencia. Usted mira su cuenta en el portal del operador y todo parece correcto. Pide una captura de pantalla y recibe la fotografía de un monitor.

**El modo soporte pone fin a ese bucle.** Abre el portal del cliente con su organización seleccionada, de modo que usted ve precisamente lo que él ve.

Es también lo más potente que puede hacer sin la aprobación de una segunda persona, y merece usarse con criterio.

---

## Qué es en realidad

No es un restablecimiento de contraseña. No es entrar como él. Nunca obtiene sus credenciales y él nunca queda desconectado.

El backend emite un **token de corta vida** que lleva:

| Claim | Valor |
|---|---|
| `sub` | **Su** identificador de usuario — no el de él |
| `entityId` | La organización cliente dentro de la cual actúa |
| `roles` | `COMPANY_ADMIN`, `ISSUER`, `INVESTOR`, `TRADER` |
| `imp` | `true` |
| `exp` | Corto — la vida estándar de un token |

!!! success "El sujeto sigue siendo usted, y en eso consiste todo el diseño"
    Como `sub` sigue siendo su identificador, **toda actuación que realice se le atribuye a usted** en la [pista de auditoría](../../platform/audit-log.md) — no al cliente, ni a un actor «del sistema» compartido.

    A un cliente nunca se le puede culpar de algo que hizo un operador mientras lo asistía, y un operador nunca puede esconderse tras la identidad de un cliente. Sin esa propiedad, el modo soporte sería inservible en un contexto regulado.

    La marca `imp: true` señala la sesión como de soporte, de modo que esas actuaciones se distinguen de las ordinarias en el registro.

---

## Usarlo

1. En el portal del operador, abra la ficha del cliente y elija **Impersonate**.
2. Se le transfiere al portal del cliente en `/admin/handoff`, que consume el token del fragmento de la URL y le deja en el panel.
3. Una **barra permanente** aparece en la parte superior de cada página: *Acting as **Nordwind Energie GmbH***, con **Switch company** y **Exit impersonation**.
4. Trabaje. Todo lo que haga queda registrado a su nombre.
5. Elija **Exit impersonation** al terminar.

También puede entrar sin elegir antes un cliente — la barra indica entonces *Admin mode — no company selected* y ofrece **Select company**, con una lista consultable.

!!! tip "La barra siempre está visible por una razón"
    Todo `REGISTRY_ADMIN` ve la barra de modo soporte en el portal del cliente en todo momento, esté o no seleccionada una sociedad. Es un recordatorio permanente de que usted no es un usuario corriente de esta interfaz, y hace mucho más difícil trabajar por descuido en el contexto equivocado.

---

## Cuándo usarlo

**Buenas razones**

- Reproducir un problema comunicado por un cliente que usted no ve en el portal del operador.
- Comprobar qué aspecto tiene la vista de un cliente tras un cambio de configuración.
- Guiar a un cliente por un flujo mientras está al teléfono.
- Confirmar que un problema de permisos o de elegibilidad es el que usted cree.

**Malas razones**

!!! danger "No use el modo soporte para hacer el trabajo del cliente por él"
    Cursar una orden, crear una oferta de venta o enviar una emisión en nombre de un cliente produce una anotación que muestra que *un operador* tomó una decisión comercial dentro de la cuenta de un cliente.

    Incluso con una atribución perfecta — quizá *sobre todo* con una atribución perfecta — esa es una anotación difícil de explicar ante un supervisor o en una disputa. La voluntad del cliente no aparece por ninguna parte.

    Mire, diagnostique, explique. Deje actuar al cliente.

!!! danger "No lo use para leer datos a los que en otro caso no tendría derecho"
    El modo soporte le da la vista del cliente sobre su propia información. Si *usted* está legitimado para consultarla sin un motivo de asistencia es una cuestión de [protección de datos](../../compliance/data-protection.md), no técnica. La pista de auditoría mostrará que usted miró.

---

## Sus límites

### No funciona en modo Entra

Cuando `ENTRA_ENABLED=true`, los clientes acceden mediante Microsoft Entra ID, que emite las sesiones directamente a cada usuario. Registerwerk no puede emitir una sesión por cuenta de un cliente, y el backend **se niega** a intentarlo.

El portal del cliente muestra un mensaje explícito en lugar de una redirección inexplicada:

> **Impersonation is unavailable.** This portal signs in through Microsoft Entra ID, which issues the session directly to each user. Registerwerk cannot act on a customer's behalf in this mode. Ask the customer to sign in themselves, or use the operator portal's read-only views.

Es una limitación real, no un hueco que sortear. En instalaciones con Entra, su caja de herramientas de soporte son las vistas del portal del operador más una sesión compartida de pantalla.

!!! warning "Planifique los procesos de soporte contando con esto antes de cambiar"
    Los operadores que han construido su flujo de soporte sobre el modo soporte y después activan Entra descubren la pérdida en el peor momento. Decida cómo atenderá a los clientes sin él *antes* del cambio, no después.

### Otros límites

- **El token es de corta vida.** Las sesiones largas caducan; vuelva a entrar en lugar de intentar prolongarla.
- **Recibe un conjunto fijo de funciones**, no las funciones concretas de un usuario determinado. No puede reproducir un problema que dependa de los permisos más estrechos de un usuario.
- **La autenticación reforzada y el doble control siguen aplicándose.** El modo soporte no los elude.
- **No puede suplantar a otro operador.** Solo afecta a entidades jurídicas clientes.

---

## Gobernarlo

El modo soporte es una capacidad permanente de todo `REGISTRY_ADMIN`. Eso lo convierte en una cuestión de control más que técnica, y los auditores preguntarán.

!!! tip "Prácticas que conviene adoptar"

    **Exija un motivo, registrado fuera de la plataforma.** Una referencia de ticket, antes de la sesión. La pista de auditoría registra que usó el modo soporte; no puede registrar *por qué*.

    **Revise periódicamente los eventos de modo soporte.** Son consultables. Un vistazo mensual a quién asistió a quién, contrastado con los tickets, convierte un poder ilimitado en un poder supervisado.

    **Mantenga reducido `REGISTRY_ADMIN`.** Cualquiera con ese rol puede entrar en cualquier cliente. Es el argumento más fuerte a favor de una lista de administradores ajustada.

    **Diga a los clientes que existe.** Enterarse a posteriori de que el personal del operador puede entrar en su portal daña la confianza mucho más que la propia capacidad. Bien planteada — *podemos ver lo que ustedes ven, y cada actuación queda registrada a nuestro nombre* — tranquiliza.

    **Nunca deje una sesión abierta.** Salga al terminar. Un navegador desatendido en una sesión de modo soporte es un navegador desatendido dentro de la cuenta de un cliente.

---

## Qué preguntará un auditor

Tenga las respuestas listas:

- ¿Quién tiene `REGISTRY_ADMIN`, y cuántas personas son?
- ¿Cómo enlaza un evento de modo soporte con un motivo de asistencia?
- ¿Cómo detectaría un uso del modo soporte *sin* un ticket correspondiente?
- ¿Puede demostrar que esas actuaciones se atribuyen al operador y no al cliente?

La última es una demostración en vivo y conviene ensayarla: entre en una entidad de prueba, realice una actuación inocua, muestre el asiento de auditoría que nombra a su usuario con `imp` activado.

---

## Adónde ir ahora

- [Soporte de doble factor](two-factor-support.md) — el otro gran flujo de asistencia
- [Pista de auditoría](../../platform/audit-log.md)
- [Funciones y permisos](roles.md)
