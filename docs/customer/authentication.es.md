---
title: Acceder
description: Cómo se accede, qué hace la autenticación de doble factor y qué hacer cuando no consigue entrar.
---

# Acceder

Cómo accede depende de cómo haya configurado la plataforma el operador de su registro. Hay dos modos, y se comportan de forma lo bastante distinta como para que merezca la pena saber en cuál está.

**La forma más rápida de saberlo:** si la página de acceso muestra un campo de correo y otro de contraseña, está en modo local. Si muestra un botón **Sign in with Microsoft**, está en modo Entra.

---

## Los dos modos

=== "Modo local — el predeterminado"

    Accede con una dirección de correo y una contraseña que custodia el propio registro.

    **Sin segundo factor al acceder.** Es la configuración predeterminada y lo que obtiene de un `docker compose up` corriente. Está pensada para instalaciones locales, demostraciones y evaluaciones.

    Su contraseña puede restablecerse por el flujo habitual de restablecimiento.

=== "Modo Entra — producción"

    Accede con **Microsoft Entra ID**, usando la cuenta Microsoft de su organización, y **la autenticación de doble factor es obligatoria**.

    El registro nunca ve su contraseña. Microsoft le autentica y emite un token; el registro lo valida.

!!! info "El personal del operador usa siempre el acceso integrado"
    Incluso en modo Entra, el personal del operador del registro accede con usuario y contraseña y usa una aplicación de autenticación local para las actuaciones sensibles.

    Solo el portal del **cliente** pasa a Entra. Si ha leído que Entra es lo predeterminado para todos, incluido el personal del operador, era incorrecto — la plataforma nunca se ha comportado así.

---

## Autenticación de doble factor

Se aplica en modo Entra.

La autenticación de doble factor es **obligatoria** para el portal del cliente en producción. La impone el acceso condicional de Microsoft al iniciar sesión, **no el portal** — si no ha registrado un segundo factor, Microsoft se lo pide antes de dejarle continuar. Nunca llega a Registerwerk sin haberse inscrito.

La página **Security** (menú de usuario → Security) muestra su estado y le guía en la configuración.

!!! note "Por qué el registro no puede darle un código QR de configuración"
    La credencial es de Microsoft. Su API no ofrece manera alguna de crear un método autenticador o TOTP — el secreto no se revela a nadie, tampoco al registro.

    Por eso el código que escanea se muestra en **la propia página de información de seguridad de Microsoft**. El código QR de nuestra página Security es sencillamente un **enlace a esa página**, para que pueda pasar del ordenador al teléfono que alojará el autenticador.

    Es una limitación de Entra, no una función que falte. Ningún software puede hacerlo de otro modo.

**Para configurar Microsoft Authenticator:**

1. Instale **Microsoft Authenticator** en su teléfono.
2. Abra **Security** en el portal y escanee el código QR, o elija **Set up now**.
3. Añada un método de acceso en la página de Microsoft y siga sus instrucciones.
4. Vuelva al portal y elija **I've finished** — la página vuelve a comprobar y confirma.

### Teléfono perdido o sustituido

Contacte con el operador del registro. Tras verificar su identidad por otra vía, retirará sus métodos antiguos, **cerrará sus sesiones existentes** y emitirá un **Temporary Access Pass** — un código de vida corta, normalmente de un solo uso, que le permite acceder una vez para registrar un método nuevo.

Úselo con prontitud; suele caducar en menos de una hora.

!!! warning "Si su organización opera su propio inquilino de Entra, el operador no puede ayudar"
    Sus usuarios están en *su* directorio, no en el del operador. No puede restablecer sus métodos de autenticación y la consola de soporte se negará a intentarlo.

    Diríjase a su propio servicio de asistencia informática.

---

## Si su organización usa su propio proveedor de identidad

Las organizaciones que configuraron un proveedor de identidad durante el [alta](onboarding.md) acceden a través de **su propio inquilino de Microsoft Entra**.

El acceso se establece **de inquilino a inquilino** en Entra, mediante colaboración B2B y ajustes de acceso entre inquilinos. El registro nunca ejecuta un flujo de código de autorización contra su inquilino y por tanto **nunca pide un secreto de cliente** — solo su URL de emisor y su identificador de cliente, a efectos de identificación.

Con este modelo:

- Sus administradores controlan qué métodos de autenticación están disponibles y con qué robustez.
- La autenticación multifactor realizada en su inquilino se acepta aquí **solo si el operador del registro ha configurado la confianza MFA entrante**. Es decisión del operador, no suya — que un cliente responda por su propio MFA sería una manera de rebajar el listón aplicado a sus propios usuarios.
- **El operador del registro no puede restablecer los segundos factores de sus usuarios.** Lo hace su servicio de asistencia.

---

## De dónde vienen sus permisos

!!! danger "Su proveedor de identidad no decide qué puede hacer"
    Esto sorprende a los administradores, y equivocarse tiene consecuencias reales.

    Entra responde a *quién es esta persona*. **Registerwerk responde a qué puede hacer**, desde su propio registro de usuario. Las funciones de aplicación de Entra se consultan una sola vez, cuando se crea su cuenta por primera vez, para elegir un valor predeterminado razonable.

    Así que: **quitarle a alguien su función de aplicación en Entra no le quita sus permisos de Registerwerk.** El administrador que lo haga creyendo revocado el acceso se equivocará.

    Para cambiar lo que alguien puede hacer, cámbielo en Registerwerk — lo hace su [administrador de empresa](workspaces/company-admin.md). Para impedirle acceder del todo, desactive la cuenta en Entra.

Documentación más antigua describía funciones tomadas de una atestación `roles` o `groups` en su token. No funciona así, y configurar una atestación de ese tipo no tendrá aquí ningún efecto.

---

## Sesiones

Las sesiones duran **8 horas** por defecto, tras lo cual vuelve a acceder.

En modo Entra, las políticas de acceso condicional de su organización pueden exigir volver a autenticarse antes, y las actuaciones sensibles pueden reclamar una prueba de identidad fresca con independencia de lo que le quede de sesión. Eso es la [autenticación reforzada](../compliance/step-up-mfa.md), y funciona según lo previsto en lugar de ser un problema de sesión.

---

## Llamar a la API directamente

Para integraciones, obtenga un token y envíelo como `Authorization: Bearer <token>`.

En **modo Entra**, obtenga el token de Entra usando su propio registro de aplicación y el ámbito que le indique su operador. En **modo local**, `POST /api/v1/public/auth/login` devuelve uno.

!!! warning "Nunca ponga un token en código de front end ni en un repositorio"
    Use variables de entorno o un gestor de secretos. Un token filtrado es una sesión en su nombre, durante toda su vida restante.

[:octicons-arrow-right-24: Visión general de la API](../platform/api.md)

---

## Cuando no consigue entrar

| Lo que ve | Suele significar | Haga |
|---|---|---|
| **Account not recognised** | Su cuenta Microsoft no está en un inquilino admitido por el operador | Contacte con el operador |
| **Access denied** tras acceder | El acceso funcionó; le falta una función | Pregunte a su administrador de empresa |
| **Un aviso para registrar información de seguridad** | Doble factor aún sin configurar | Sígalo — es obligatorio |
| **Token expired** | Sesión terminada | Vuelva a acceder |
| **Bucle de redirección** | Configuración errónea del lado del operador | Contacte con el operador — no es algo que pueda arreglar |
| **Todo parece bien pero nada funciona** | El [KYC](kyc.md) de su organización puede haber caducado | Consulte la página de KYC |

!!! tip "La diferencia entre 401 y 403 vale la pena conocerla"
    Si informa de un problema, decir cuál obtuvo ahorrará tiempo a todos.

    **401** — su token no se acepta. Un problema de acceso.
    **403** — su token está bien, sus permisos no. Un problema de funciones, y su administrador de empresa probablemente pueda resolverlo sin implicar al operador.

---

## Adónde ir ahora

- [Obtener su cuenta](onboarding.md)
- [Administrador de empresa](workspaces/company-admin.md) — gestionar usuarios y ajustes de IdP
- [MFA reforzada](../compliance/step-up-mfa.md) — por qué algunas actuaciones vuelven a preguntar
