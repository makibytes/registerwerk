---
title: Qué hace un operador
description: El papel del operador en su totalidad: las decisiones que son suyas, el portal y un inicio local de quince minutos.
---

# Qué hace un operador { #what-an-operator-does }

Usted dirige el registro. Los clientes dependen de que sea correcto, esté disponible y esté atendido por alguien que entienda lo que está aprobando.

Esta página es el trabajo. [Cómo se construye Registerwerk](architecture.md) es el sistema; [Atender a los clientes](customers/index.md) es el detalle de cada proceso.

---

## El rol, honestamente { #the-role-honestly }

La mayor parte del trabajo es **juzgar sobre personas e instrumentos**, no sobre infraestructura. Pasará mucho más tiempo decidiendo si una entidad es quien dice ser y si se debe admitir una emisión, que reiniciando contenedores.

Los poderes que son exclusivamente suyos comparten una propiedad: **cada uno puede causar un daño que es difícil o imposible de revertir.**

| | Por qué es suya |
|---|---|
| **Admitir una organización** | Todo lo posterior supone que se produjo esta verificación. |
| **Aprobar una emisión** | Crea algo que se convierte en una obligación legal de los inversores. |
| **Corregir el registro** | Las transferencias forzosas y las destrucciones forzosas según §§24/26 eWpG trasladan bienes ajenos. |
| **Actuar como cliente** | [La suplantación](customers/impersonation.md) le sitúa dentro de su portal. |

---

## Su día { #your-day }

### Rutina { #routine }

- **La cola de aprobación.** Entidades en espera de revisión de KYC, emisiones en espera de aprobación.
- **El registro de auditoría.** Léalo cuando no haya ningún problema, para saber cómo se ve lo normal.
- **Salud.** Retraso del indexador, estado de la cadena RPC, disponibilidad de detección, [espacio de partición de auditoría](maintenance/monitoring.md).
- **Soporte.** Generalmente una de tres cosas; consulte a continuación.

### En un programa { #on-a-schedule }

- **Revisar la membresía de `REGISTRY_ADMIN`.** Cualquiera con ese rol puede aprobar emisiones, corregir el registro y suplantar a cualquier cliente.
- **Verifique los próximos vencimientos de KYC.** Advertir a un cliente con un mes de antelación evita una interrupción que experimentará como culpa suya.
- **Verifique la cadena de auditoría** y conserve la evidencia. Un control de integridad que nadie ejerce es indistinguible de uno que no funciona.
- **Restauraciones de prueba.** Una copia de seguridad que nadie ha restaurado es una hipótesis.

### La clasificación de tres preguntas { #the-three-question-triage }

Antes de investigar algo exótico, el problema de un cliente suele ser:

1. **KYC caducó**: las transferencias se detienen, todo lo demás parece normal.
2. **Monedero no registrado o no admitido**: las transferencias fallan en la cadena en lugar de estar pendientes.
3. **Falta el rol**: obtienen un `403` y lo llaman "la página está rota".

A `401` significa que el token está defectuoso. Un `403` significa que el token está bien y el rol no. Esa distinción por sí sola resuelve una gran parte de los tickets.

---

## El portal del operador { #the-operator-portal }

En `:44200`. Omite la puerta de enlace por completo y utiliza el inicio de sesión integrado con nombre de usuario y contraseña, con TOTP local para la autenticación reforzada (step-up), en cada configuración, incluidas las implementaciones donde los clientes usan Microsoft Entra ID.

| Área | |
|---|---|
| **Clientes** | Entidades jurídicas, su estado, su KYC. |
| **Incorporación** | Crea entidades, genera tokens de invitación. |
| **Activos** | Cada emisión de cada cliente. |
| **Usuarios** | Cuentas y roles, incluido el [soporte 2FA](customers/two-factor-support.md). |
| **Cumplimiento** | Casos de detección de sanciones, revisión de KYC. |
| **Auditoría** | El registro a prueba de manipulaciones. |
| **Organizaciones / Permisos** | Identidad en cadena y permisos del ecosistema. |
| **Revisión de dApp** | Envíos al mercado. |
| **Vías de pago** | Curación del catálogo de la pata de efectivo. |
| **Monederos / Nodos de red** | Monederos custodiados, estado de la cadena y del RPC. |

!!! warning "La navegación en el portal no es una barrera de seguridad"
    Las rutas del portal del operador no se filtran por rol en el navegador. El acceso lo aplica el **backend**, por solicitud, a partir de su token.

    Por lo tanto, un usuario con solo `AUDIT` ve las entradas del menú de cosas que no puede hacer, y se le deniega el acceso al abrirlas. No se expone nada — pero no infiera, a partir de un elemento de menú visible, que alguien pueda usarlo.

---

## Quince minutos para un registro local { #fifteen-minutes-to-a-local-registry }

```bash
git clone <your-registerwerk-remote> && cd registerwerk
git submodule update --init --recursive
cp .env.example.test .env
# CHAINCACHE_IMAGE en .env debe indicar una imagen suministrada de forma independiente.
docker compose up -d --build
```

Con `CHAINCACHE_ENABLED=true`, el mismo comando inicia ambos workloads Chaincache y sus PostgreSQL
privado. Registerwerk solo necesita la imagen indicada por `CHAINCACHE_IMAGE` y no
compila `../chaincache`. Con `false`, la pila principal puede arrancar de forma independiente.

!!! danger "Deje `JWT_ISSUER_URI` en blanco para un inicio local"
    Al configurarla, el portal del cliente cambia al modo OIDC, que necesita un inquilino de Entra real, registros de aplicaciones y acceso condicional. Una URI de emisor configurada solo a medias produce fallos de inicio de sesión que parecen errores del sistema.

    El modo local es el valor predeterminado y el punto de partida correcto. Active Entra de forma deliberada, siguiendo [Configuración de Entra ID](../platform/entra-setup.md).

Entonces:

| | |
|---|---|
| Portal del operador | `http://localhost:44200` |
| Portal del cliente | `http://localhost:44201` |
| Salud del backend | `curl http://localhost:48080/actuator/health` |
| A través de la puerta de enlace | `curl http://localhost:48000/api/v1/public/chains` |
| Documentación | `docker compose --profile docs up` → `http://localhost:48003` |

Inicie sesión con `DEFAULT_ADMIN_EMAIL` / `DEFAULT_ADMIN_PASSWORD` desde su `.env`.

Kong se ejecuta sin base de datos a partir de `gateway/kong.yml`, por lo que no hay credenciales de base de datos de la puerta de enlace, ni ninguna base de datos `kong` o `konga`. Su API de administración está vinculada a loopback — acceda a ella con `docker compose exec kong kong health`, nunca la exponga.

Para cualquier cosa más allá de una prueba local, vaya a [Requisitos previos](installation/prerequisites.md) y lea con atención [Entorno](configuration/environment.md).

---

## Antes de atender a clientes reales { #before-you-serve-real-customers }

- [ ] `DEFAULT_ADMIN_PASSWORD` y `JWT_DEV_SECRET` cambiados respecto a sus valores predeterminados.
- [ ] `JWT_AUDIENCE` configurado, si Entra está habilitado. **No es opcional**: sin él, un token emitido a cualquier otra aplicación de su inquilino se acepta aquí como una sesión válida.
- [ ] Copias de seguridad configuradas **y restauradas al menos una vez** — incluido el almacén de objetos, que no está en la base de datos.
- [ ] [Monitorización](maintenance/monitoring.md) implementada, con alertas de margen de partición de auditoría.
- [ ] Más de un `REGISTRY_ADMIN`, en manos de **personas distintas**, de modo que los controles de [cuatro ojos](../compliance/step-up-mfa.md) sean reales.
- [ ] Un procedimiento probado de [recuperación ante desastres](dr/runbook.md).
- [ ] Sus criterios de KYC y de aprobación de emisiones puestos por escrito, para que las decisiones sean consistentes y explicables.

---

## Dónde siguiente { #where-next }

- [Cómo se construye Registerwerk](architecture.md)
- [Atender a los clientes](customers/index.md)
- [Solución de problemas](troubleshooting.md)
