---
title: Proveedor de identidad
---

# Proveedor de identidad (OIDC) { #identity-provider-oidc }

## Inicio de sesión de administrador integrado (modo desarrollo/sin IdP) { #built-in-admin-login-development-no-idp-mode }

Cuando `ENTRA_ENABLED=false` (el valor predeterminado), la interfaz del operador muestra un formulario de nombre de usuario/contraseña
en lugar del botón "Iniciar sesión con Microsoft". El backend expone `POST /api/v1/public/auth/login`
y emite un HS256 JWT de corta duración firmado con `JWT_DEV_SECRET`.

Configurar mediante variables de entorno:

```dotenv
ENTRA_ENABLED=false
DEFAULT_ADMIN_EMAIL=admin@local
DEFAULT_ADMIN_PASSWORD=changeme-please
JWT_DEV_SECRET=change-me-for-staging
```

Al iniciar, el backend genera (o actualiza) una fila en la tabla `app_user` con el correo electrónico
configurado y un hash BCrypt de la contraseña. Rotar la contraseña es tan simple como cambiar
`DEFAULT_ADMIN_PASSWORD` y reiniciar el servicio: el hash se actualiza en cada arranque.

!!! warning "No para producción"
    El secreto de desarrollo HS256 y el administrador integrado están destinados únicamente a entornos de desarrollo local y demostración.
    Para producción, configure un proveedor de identidad real a continuación y configure
    `ENTRA_ENABLED=true` + `JWT_ISSUER_URI=<your-issuer>`. El punto final `/api/v1/public/auth/login`
    devuelve 404 cuando `ENTRA_ENABLED=true`.



El backend es un servidor de recursos OAuth2. Acepta JWT de cualquier proveedor compatible con OIDC.

## Microsoft Entra ID (recomendado) { #microsoft-entra-id-recommended }

1. Registre una aplicación en Azure Portal → Registros de aplicaciones
2. Agregue permisos API: `openid`, `profile`, `email`
3. Definir roles de aplicación: `REGISTRY_ADMIN`, `AUDIT`, `ISSUER`, `INVESTOR`, `COMPANY_ADMIN`
4. Establecer variables de entorno:
   ```dotenv
   JWT_ISSUER_URI=https://login.microsoftonline.com/<tenant-id>/v2.0
   ENTRA_ISSUER=https://login.microsoftonline.com/<tenant-id>/v2.0
   ENTRA_CLIENT_ID=<app-id>
   ENTRA_CLIENT_SECRET=<client-secret>
   ```

Opcionalmente, si ejecuta Kong Enterprise/Konnect, también puede terminar OIDC en la puerta de enlace
usando `gateway/plugins/oidc-entra.yml`; el backend valida el propio JWT de cualquier manera,
por lo que esto es una defensa en profundidad, no un requisito.

## Keycloak autoadministrado { #self-managed-keycloak }

1. Cree un realm y un client
2. Agregue roles de realm que coincidan con los nombres de roles anteriores
3. Configure el asignador de tokens para incluir roles en el claim `roles` del JWT
4. Establecer variables de entorno:
   ```dotenv
   JWT_ISSUER_URI=https://keycloak.yourhost.com/realms/ewpg
   ENTRA_ISSUER=https://keycloak.yourhost.com/realms/ewpg
   ENTRA_CLIENT_ID=<client-id>
   ENTRA_CLIENT_SECRET=<client-secret>
   ```

Opcionalmente, finalice OIDC en Kong también usando `gateway/plugins/oidc-self-managed.yml` (solo Enterprise/Konnect).

## Claims JWT esperados { #jwt-claims-expected }

El `JwtEntityClaimsConverter` del backend lee los claims directamente del JWT validado. No
depende de ningún encabezado inyectado por la puerta de enlace:
- `sub` - asunto del usuario
- `roles` - lista de cadenas de roles (por ejemplo, `["ISSUER", "COMPANY_ADMIN"]`), convertidas en autoridades `ROLE_*`
- `entity_id` - el UUID de la entidad jurídica, para el alcance multi-tenant

Configure la asignación de tokens/claims de su IdP para que estén presentes en el JWT emitido. No hay ningún paso de mapeo de entidades del lado de
Kong en la configuración de OSS Kong de este repositorio.
