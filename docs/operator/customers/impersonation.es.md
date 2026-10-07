---
title: Modo soporte — ver lo que ellos ven
description: Actuar dentro del portal de un cliente para darle asistencia: cómo funciona, a quién se atribuye, cuáles son sus límites y cómo gobernarlo.
---

# Modo soporte — ver lo que ellos ven

Un cliente dice que el Trading Desk no le deja publicar una tenencia. Usted mira su cuenta en el portal del operador y todo parece correcto. Pide una captura de pantalla y recibe la fotografía de un monitor.

**El modo soporte pone fin a ese bucle.** Abre el portal del cliente con su organización seleccionada, de modo que usted ve precisamente lo que él ve.

Da acceso a la vista que un cliente tiene de sus propios datos y por eso está protegido: iniciarlo exige una prueba de autenticación reforzada reciente y un motivo escrito, la sesión por defecto es de **solo lectura**, y una sesión con escritura exige un segundo aprobador y solo existe en modo demo.

---

## Qué es en realidad

No es un restablecimiento de contraseña. No es iniciar sesión como él. Nunca obtiene sus credenciales y a él nunca se le cierra la sesión.

La llamada de inicio (`POST /api/v1/impersonation`, motivo de autenticación reforzada `ADMIN_IMPERSONATION`) lleva el cliente, un **motivo obligatorio** (al menos 15 caracteres) y, opcionalmente, una referencia de ticket. **No devuelve ningún token**, sino una URL de traspaso con un **código de un solo uso**, válido durante 60 segundos. El portal del cliente cambia ese código por una cookie de sesión httpOnly; reutilizar el código pone fin a la sesión. Así, el token nunca pasa por las manos del operador ni por el historial de su navegador.

El token de sesión que hay detrás de la cookie lleva:

| Claim | Valor |
|---|---|
| `sub` | **Su** id de usuario — no el suyo |
| `entityId` | La organización cliente dentro de la que actúa |
| `roles` | `COMPANY_ADMIN`, `ISSUER`, `INVESTOR`, `TRADER` |
| `imp` | `true` |
| `imp_mode` | `READ_ONLY` (por defecto) o `ACT_ON_BEHALF` |
| `jti` | El id del registro `impersonation_session` |
| `exp` | 30 minutos (`registerwerk.auth.impersonation-ttl-seconds`, 1800 por defecto) |

!!! success "El sujeto sigue siendo usted, y en eso consiste todo el diseño"
    Como `sub` sigue siendo su id de usuario, **cada acción que realiza se le atribuye a usted** en el [registro de auditoría](../../platform/audit-log.md) — no al cliente ni a un actor «sistema» compartido.

    A un cliente nunca se le puede culpar de algo que hizo un operador durante la suplantación, y un operador nunca puede esconderse tras la identidad de un cliente. Sin esa propiedad, el modo soporte sería inutilizable en un contexto regulado.

    La marca `imp: true` señala la sesión como suplantada, de modo que esas acciones se distinguen de las ordinarias en el registro.

### Modos

| | `READ_ONLY` (por defecto) | `ACT_ON_BEHALF` |
|---|---|---|
| Endpoint de inicio | `POST /api/v1/impersonation` | `POST /api/v1/impersonation/act-on-behalf` |
| Quién puede iniciarlo | `REGISTRY_ADMIN` o `SUPPORT_AGENT` | solo `REGISTRY_ADMIN` |
| Autenticación reforzada | Sí (`ADMIN_IMPERSONATION`) | Sí, más un **segundo aprobador** (`ADMIN_IMPERSONATION_ACT_ON_BEHALF`) |
| Qué puede hacer la sesión | Solo lectura: `POST`, `PUT`, `PATCH` y `DELETE` se rechazan con `403 IMPERSONATION_READ_ONLY` | Escritura, salvo la lista de denegación de abajo |
| Modo producción | Disponible | **Rechazado.** En modo producción toda sesión activa se aplica como solo lectura, incluso una sesión de escritura residual |

La lista de denegación de `ACT_ON_BEHALF` (`registerwerk.auth.impersonation-deny-patterns`) devuelve `403 IMPERSONATION_ACTION_DENIED` para las atestaciones del cliente y la administración de cuentas: confirmación de pago, disputa y liquidación de trades, declaraciones de incumplimiento del repo desk, gestión de usuarios de la empresa, ajustes del proveedor de identidad de la empresa, webhooks, identidad de la organización y borrado de documentos KYC.

!!! note "SUPPORT_AGENT"
    `SUPPORT_AGENT` es un rol del personal del operador para soporte: puede iniciar sesiones de solo lectura y listar entidades cliente para elegir una, nada más. Concederlo o retirarlo exige autenticación reforzada y un segundo aprobador, y está incluido en las revisiones de acceso.

Solo se pueden suplantar entidades jurídicas **activas**. La sesión queda registrada en `impersonation_session` (actor, entidad, modo, motivo, ticket, aprobador, caducidad), y **los administradores de la empresa del cliente ven cada sesión sobre su entidad** en `GET /api/v1/company/impersonation-sessions`.

---

## Usarlo

1. En el portal del operador, abra la ficha del cliente y elija **Impersonate**. Indique el motivo (y una referencia de ticket si la tiene). El diálogo ofrece solo lectura; el modo de escritura aparece únicamente en modo demo.
2. Se le transfiere al portal del cliente en `/admin/handoff`. El fragmento de la URL lleva el `code` de un solo uso, `entityId` y `entityName`; el portal cambia el código por su cookie de sesión y le deja en el panel.
3. Una **barra permanente** aparece en la parte superior de cada página: *Acting as **Nordwind Energie GmbH*** (en una sesión de solo lectura: *Viewing … (read-only support session - changes are blocked)*), con **Switch company** y **Exit impersonation**.
4. Mire y diagnostique. Todo lo que haga queda registrado a su nombre.
5. Elija **Exit impersonation** al terminar. La sesión termina y queda registrada; si no lo hace, caduca a los 30 minutos.

También puede entrar sin elegir antes un cliente — la barra indica entonces *Admin mode — no company selected* y ofrece **Select company**, con una lista consultable. Un `SUPPORT_AGENT` llega a este selector de empresa tras iniciar sesión.

!!! tip "La barra siempre está visible por una razón"
    Cualquier `REGISTRY_ADMIN` ve la barra de suplantación en el portal del cliente en todo momento, haya o no una empresa seleccionada. Es un recordatorio permanente de que no es un usuario ordinario de esta interfaz, y hace mucho más difícil trabajar por error en el contexto equivocado.

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

- **La sesión es de corta duración.** Caduca a los 30 minutos; vuelva a entrar (con un nuevo motivo) en lugar de intentar prolongarla.
- **El código de traspaso es de un solo uso y vale 60 segundos.** Si el portal no lo recoge a tiempo, o se reutiliza, la sesión termina; empiece de nuevo.
- **Obtiene un conjunto fijo de roles**, no los roles concretos de un usuario individual. No puede reproducir un problema que dependa de los permisos más estrechos de un usuario.
- **No se eluden la autenticación reforzada ni el doble control.** Iniciar la sesión exige su propia prueba de autenticación reforzada; una sesión de escritura exige además un segundo aprobador. Dentro de una sesión, las operaciones protegidas del cliente siguen protegidas, y la cola de aprobaciones rechaza las sesiones de suplantación.
- **No puede suplantar a otro operador.** Solo apunta a entidades jurídicas cliente.

---

## Gobernarlo

El modo soporte está disponible para todo `REGISTRY_ADMIN` y todo `SUPPORT_AGENT`. Es, por tanto, una cuestión de control además de técnica, y los auditores preguntarán por ella.

!!! tip "Prácticas que conviene adoptar"

    **Haga útil el motivo.** La plataforma rechaza un inicio sin un motivo de al menos 15 caracteres y lo registra, junto con la referencia de ticket opcional, en `impersonation_session` y en el evento de auditoría. Ponga el número de ticket en el campo de ticket y escriba en el motivo lo que necesita ver.

    **Revise periódicamente los eventos de suplantación.** Se pueden consultar (nombres de evento más abajo). Una revisión mensual de quién suplantó a quién, contrastada con los tickets, convierte un poder amplio en un poder supervisado. Los administradores de la empresa del cliente pueden hacer la misma comprobación desde su lado.

    **Prefiera `SUPPORT_AGENT` para el personal de soporte.** Puede iniciar sesiones de solo lectura y nada más, así que el soporte no necesita una cuenta `REGISTRY_ADMIN`.

    **Mantenga reducido `REGISTRY_ADMIN`.** Cada titular puede iniciar sesiones para cualquier cliente activo.

    **Diga a los clientes que existe.** Descubrir a posteriori que el personal del operador puede entrar en su portal daña la confianza mucho más que la capacidad en sí. Bien planteado — *podemos ver lo que usted ve, cada acción queda registrada a nuestro nombre y sus administradores pueden consultar cada sesión* — tranquiliza.

    **No deje nunca una sesión abierta.** Salga al terminar. Un navegador desatendido en una sesión suplantada es un navegador desatendido dentro de la cuenta de un cliente (aunque caduca a los 30 minutos).

---

## Qué preguntará un auditor

Tenga respuestas preparadas:

- ¿Quién tiene `REGISTRY_ADMIN` o `SUPPORT_AGENT`, y cuántas personas son?
- ¿Cómo vincula un evento de suplantación con un motivo de soporte? (El motivo y el ticket figuran en `impersonation_session` y en el evento `ADMIN_IMPERSONATION_STARTED`.)
- ¿Cómo detectaría una suplantación *sin* ticket correspondiente?
- ¿Puede demostrar que las acciones suplantadas se atribuyen al operador y no al cliente?
- ¿Está desactivada en producción la suplantación con escritura? (Sí: el modo producción rechaza `ACT_ON_BEHALF` y degrada a solo lectura cualquier sesión activa.)

La pista de auditoría contiene los eventos `ADMIN_IMPERSONATION_STARTED`, `ADMIN_IMPERSONATION_HANDOFF_EXCHANGED` y `ADMIN_IMPERSONATION_ENDED`; las peticiones dentro de una sesión llevan la marca `imp`. La pregunta de la atribución es una demostración en directo y conviene ensayarla: suplante una entidad de prueba, mire una página, muestre las entradas de auditoría que nombran a su usuario con `imp` activado y muestre la sesión en la vista de los administradores de la empresa del cliente.

---

## Adónde ir ahora

- [Soporte de doble factor](two-factor-support.md) — el otro gran flujo de asistencia
- [Pista de auditoría](../../platform/audit-log.md)
- [Funciones y permisos](roles.md)
