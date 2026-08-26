---
title: Funciones y permisos
description: Quién usa Registerwerk, qué puede hacer cada cual, y a qué obligación regulatoria responde cada función.
---

# Funciones y permisos

Registerwerk es multiinquilino: una instalación de operador sirve a muchas entidades jurídicas clientes. El acceso se gobierna mediante un conjunto de funciones definido en la enumeración `AppRole` y aplicado con `@PreAuthorize` en cada método de controlador.

---

## Panorámica de funciones

| Función | Portal | Quién la tiene | Obligación regulatoria |
|---|---|---|---|
| `REGISTRY_ADMIN` | Operador | Personal del registro | §15 eWpG entidad responsable del registro; §10 GwG responsable de prevención del blanqueo |
| `COMPLIANCE_OFFICER` | Operador | Equipo de cumplimiento / blanqueo | §7 GwG responsable de cumplimiento; art. 8 AMLD6 |
| `AUDITOR` | Operador | Auditores internos/externos | §15(3) eWpG acceso a las anotaciones |
| `ISSUER` | Cliente | Emisores de valores | §4 eWpG obligaciones del emisor |
| `INVESTOR` | Cliente | Titulares de tokens / inversores | |
| `COMPANY_ADMIN` | Cliente | Administradores del emisor | |
| `TRADER` | Cliente | Acceso de ejecución para integraciones con centros de negociación | Art. 26 MiFIR reporte |

---

## Funciones del operador

### REGISTRY_ADMIN

La función con más privilegios. Un `REGISTRY_ADMIN` puede:

- Crear, modificar y desactivar [entidades jurídicas](../intro/concepts.md#entidades-clientes)
- Aprobar y denegar [documentos KYC](../compliance/kyc-aml.md)
- Desplegar y administrar [tokens de valores](../token-standards/index.md)
- Inscribir un [Sperrvermerk](../compliance/sperrvermerk.md) (restricción a la negociación) — exige [autenticación reforzada](../compliance/step-up-mfa.md)
- Transferir y destruir tokens de forma forzosa — exige autenticación reforzada + doble control
- Suplantar a usuarios clientes con fines de asistencia — capacidad permanente, véase la salvedad más abajo
- Acceder a todas las anotaciones de la [pista de auditoría](../platform/audit-log.md)
- Lanzar las exportaciones regulatorias [MiFIR](../compliance/mifir.md) y [DAC8](../compliance/dac8.md)

!!! warning "Las operaciones forzosas exigen control dual"
    La transferencia forzosa, la destrucción forzosa y la aprobación forzosa son operaciones on-chain irreversibles. La implementación actual exige que un segundo `REGISTRY_ADMIN` distinto aporte el token de doble control; no existe una función de aplicación `SECOND_APPROVER`. Su adecuación jurídica y regulatoria requiere revisión externa.

### COMPLIANCE_OFFICER

Centrada en las funciones de prevención del blanqueo y KYC:

- Revisar y gestionar ejecuciones y coincidencias del [filtrado de sanciones](../compliance/sanctions-screening.md)
- Aceptar o rechazar coincidencias (con doble control para entidades de alto riesgo)
- Aprobar documentos KYC de las jurisdicciones que tenga asignadas
- Inscribir y levantar un [Sperrvermerk](../compliance/sperrvermerk.md) — exige autenticación reforzada
- Acceder a las anotaciones de incidentes [DORA](../compliance/dora.md)
- Lanzar un nuevo filtrado de sanciones a demanda

### AUDITOR

Acceso de solo lectura a toda la pista de auditoría:

- Leer todas las entradas de la [pista de auditoría](../platform/audit-log.md)
- Verificar la integridad de la cadena de hash de auditoría
- Exportar anotaciones de auditoría para revisión externa
- Acceder al historial de ejecuciones de filtrado y a las versiones de los documentos KYC

### Aprobador en doble control

La aprobación en doble control es hoy una capacidad de un segundo `REGISTRY_ADMIN` distinto, no una función de aplicación aparte. El aprobador debe ser distinto de quien inicia la operación y debe superar las comprobaciones de autenticación reforzada configuradas.

---

## Funciones del cliente

Los usuarios clientes acceden a la plataforma por el front end del cliente (`:44201`), cuyas llamadas a la API pasan por Kong. Su JWT lleva una atestación `entityId` (emitida también como `entity_id`) que indica a qué `LegalEntity` pertenecen, y el backend deriva de ella el aislamiento de datos en cada petición.

`X-Entity-Id` es el nombre de una *cabecera*, no una atestación — y una cabecera que Kong **elimina** deliberadamente de las peticiones entrantes para que no pueda falsificarse. Nada en el backend confía en ella.

### ISSUER

Un emisor puede:

- Crear y gestionar sus propias definiciones de [activo](../token-standards/index.md)
- Iniciar el despliegue de tokens (sujeto, si procede, a la aprobación del operador)
- Gestionar el alta de inversores para sus tokens
- Proponer [operaciones societarias](../intro/concepts.md) — dividendos, desdoblamientos, amortizaciones anticipadas — para revisión del operador, y retirar una propuesta antes de que sea revisada
- Certificar que la liquidación de una operación societaria está lista — la primera de las dos partes requeridas, junto con la confirmación de un operador
- Consultar el historial de operaciones societarias de sus valores
- Descargar extractos de posición y documentos regulatorios

### INVESTOR

Un inversor puede:

- Consultar su cartera (tokens mantenidos, posiciones)
- Aceptar solicitudes de transmisión
- Consultar el historial de transacciones
- Consultar las operaciones societarias que afectan a sus posiciones y descargar las confirmaciones de liquidación
- Descargar sus extractos de posición

### COMPANY_ADMIN

Gestiona usuarios y funciones dentro de una entidad jurídica cliente:

- Invitar y retirar usuarios de la empresa
- Asignar las funciones `ISSUER` / `INVESTOR` / `TRADER` dentro de su entidad
- Consultar el estado KYC de la entidad (sin poder aprobarlo — solo pueden los operadores)

### TRADER

Un usuario, máquina o persona, autorizado a interactuar con las integraciones de centros de negociación:

- Enviar y gestionar ofertas de venta
- Consultar los informes de ejecución
- Estas actuaciones se reportan a los supervisores mediante [MiFIR RTS 22](../compliance/mifir.md)

---

## Modo soporte

Los usuarios `REGISTRY_ADMIN` pueden suplantar a un usuario cliente para investigar una incidencia o ayudar en el alta. El modo soporte:

- Emite un token de corta vida cuyo `sub` sigue siendo el identificador de usuario del **operador**, de modo que toda actuación se atribuye al operador y nunca al cliente
- Queda registrado en la [pista de auditoría](../platform/audit-log.md), marcado con `imp` para que esas actuaciones sigan siendo distinguibles
- Es visible para todos los usuarios `REGISTRY_ADMIN` mediante la barra del front end del cliente
- Caduca con el token; vuelva a entrar en lugar de intentar prolongarlo

!!! warning "El modo soporte no está protegido por autenticación reforzada"
    `AdminImpersonationController` no lleva ningún `@RequiresStepUp`. Cualquier `REGISTRY_ADMIN` puede entrar en el portal de cualquier cliente sin un segundo desafío de autenticación y sin una segunda persona.

    Trátelo como una cuestión de control más que técnica: mantenga reducida la lista de administradores, exija un motivo registrado fuera de la plataforma y revise periódicamente los eventos. [Modo soporte](../operator/customers/impersonation.md) trata su gobierno.

Además, el modo soporte no está disponible en absoluto cuando `ENTRA_ENABLED=true` — el backend se niega a emitir una sesión por cuenta de un cliente.
