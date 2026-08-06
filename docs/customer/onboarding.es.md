---
title: Alta (onboarding)
---

# Alta (onboarding)

Esta guía le acompaña en el registro de su organización en el registro eWpG — desde el primer correo de invitación hasta tener una cuenta completamente configurada.

## Cómo funciona el alta

El alta la inicia el operador del registro, no un autorregistro. El proceso sigue estos cuatro pasos:

```
Operator creates entity
        |
        v
You receive an invitation email with a one-time token
        |
        v
You redeem the token and configure your organization
        |
        v
Admin activates your account — you can start working
```

## Paso 1 — Recibir su invitación

El operador del registro crea una entidad (sociedad o persona física) por cuenta de usted. Recibirá un correo del registro con el asunto **«Your eWpG Registry Invitation»** que contiene:

- Un **token de alta** de un solo uso (válido 48 horas)
- Un enlace al portal del cliente

!!! warning "Caducidad del token"
    El token de alta caduca a las 48 horas. Si ha caducado, contacte con el operador del registro para pedir uno nuevo. No comparta el token — concede acceso completo a la configuración de su cuenta.


## Paso 2 — Canjear el token

1. Haga clic en el enlace del correo de invitación. Llegará al portal del cliente.
2. Se le pedirá acceder mediante su proveedor de identidad (véase [Acceder](./authentication.md)). Para usuarios nuevos suele ser Microsoft Entra ID (antes Azure AD) con su dirección de correo corporativa.
3. Tras acceder, el portal detecta su token de alta en la URL y activa su entidad automáticamente.
4. Se le redirige a la pantalla **Welcome**, que muestra la función que se le ha asignado (Issuer, Investor o Auditor).

## Paso 3 — Configurar su organización

Tras canjear el token puede configurar el perfil de su organización:

### Datos de la organización

Vaya a **Settings → Organization** y complete:

| Campo | Descripción |
|-------|-------------|
| Legal name | Su denominación social registrada |
| LEI | Identificador de entidad jurídica (obligatorio para emisores) |
| Registration number | Número de inscripción de la sociedad |
| Jurisdiction | País de constitución |
| Contact email | Contacto principal para notificaciones regulatorias |

### Gestión de usuarios

Si su organización tiene varios usuarios, vaya a **Settings → Users** e invítelos por correo. Cada usuario invitado:
- recibe su propio correo de invitación
- accede con su propia identidad corporativa
- recibe una de las funciones de su organización

### Configurar un proveedor de identidad propio (opcional)

Si su organización usa un proveedor de identidad propio (por ejemplo su propio Keycloak, Okta u otro IdP compatible con OIDC), puede configurarlo en **Settings → Identity Provider**.

Tendrá que aportar:

```
OIDC Issuer URL:       https://your-idp.example.com/realms/your-realm
Client ID:             registerwerk-client
```

!!! info "No hay campo de secreto de cliente"
    La federación se establece de inquilino a inquilino en su propio proveedor de identidad. Registerwerk nunca ejecuta un flujo de código de autorización contra su inquilino, así que no tiene uso para un secreto de cliente suyo — y el campo se retiró en lugar de dejarlo recogiendo una credencial que nadie necesita. Véase [Administrador de empresa](workspaces/company-admin.md).


Una vez configurado y verificado, todos los usuarios de su organización serán redirigidos a su IdP para autenticarse, en lugar del acceso predeterminado de Entra ID.

## Paso 4 — Activación de la cuenta

Su cuenta ya está activa. Según su función:

- **Emisores**: puede que se le pida completar una revisión KYC/prevención del blanqueo antes de poder desplegar tokens en la red principal. Véase [Crear una emisión](lifecycle/primary-issuance.md).
- **Inversores**: su cuenta está lista. Puede conectar un monedero y consultar sus tenencias.
- **Auditores**: su cuenta está lista. Tiene acceso de solo lectura a todos los datos del registro.

## ¿Necesita ayuda?

Si tiene problemas durante el alta, contacte con el operador del registro mediante el enlace de soporte del correo de invitación o el botón **Help** del pie de página del portal.
