---
title: Configuración de Microsoft Entra ID
description: Registros de aplicaciones, acceso condicional, permisos de Graph y la prueba de humo del tenant para la 2FA en producción.
---

# Configuración de Microsoft Entra ID { #microsoft-entra-id-setup }

Este es el runbook para colocar el portal del cliente detrás de Microsoft Entra ID con autenticación
de dos factores obligatoria. Nada de esto se aplica a implementaciones locales o de demostración: con
`ENTRA_ENABLED=false` (el valor predeterminado de `docker-compose.yml`), el portal usa el inicio de
sesión integrado por usuario/contraseña y ningún segundo factor.

**Requiere Microsoft Entra ID P1** para el acceso condicional y los contextos de autenticación.

---

## Qué puede y qué no puede hacer Registerwerk { #what-registerwerk-can-and-cannot-do }

Dos restricciones determinan todo el diseño y conviene entenderlas antes de empezar:

**No podemos emitirle un código QR para Microsoft Authenticator.** Microsoft Graph no expone ninguna
forma de crear un método de autenticador o TOTP — `softwareOathMethods` y
`microsoftAuthenticatorMethods` solo admiten listar, obtener y eliminar, y `secretKey` está
documentado como que siempre devuelve `null`. El secreto pertenece a Entra. Por eso el registro se
realiza en la [página combinada de información de seguridad](https://learn.microsoft.com/en-us/entra/identity/authentication/concept-registration-mfa-sspr-combined)
de Microsoft, y la página `/security` de Registerwerk guía a los usuarios hasta allí. El código QR
que mostramos codifica el *enlace* a esa página, de modo que un usuario en un equipo de escritorio
puede continuar en el teléfono que albergará la credencial.

**Entra External ID (CIAM) no puede usarse** si desea Microsoft Authenticator: los tenants externos
solo admiten OTP por correo electrónico, SMS (un complemento de pago) y passkeys. Los clientes deben
ser miembros o invitados B2B de un tenant corporativo (workforce tenant), o estar federados desde el
suyo propio.

---

## 1. Registros de aplicaciones { #1-app-registrations }

Dos registros. Manténgalos separados: el de la API contiene un secreto de cliente y nunca debe ser un
cliente público.

### API — el backend { #api-the-backend }

| Ajuste | Valor |
|---|---|
| Nombre | `Registerwerk API` |
| URI del identificador de aplicación | `api://<api-client-id>` |
| Ámbito expuesto | `access_as_user` (consentimiento de administrador + de usuario) |
| Secreto de cliente | Genere uno → `ENTRA_CLIENT_SECRET` |

**Atestaciones (claims) opcionales en el token de acceso** — añada las tres en *Token configuration*:

| Atestación (claim) | Por qué importa si falta |
|---|---|
| `acrs` | Entra nunca añade el contexto de autenticación de forma oportunista, así que cada acción de step-up cuesta una redirección completa del navegador. Esto parece exactamente un fallo de la aplicación. |
| `xms_cc` | La API no puede saber que el cliente entiende los desafíos de atestaciones (claims challenges). |
| `auth_time` | La frescura del step-up recae silenciosamente en `iat`, una garantía sustancialmente más débil. El backend registra una advertencia la primera vez que ve un token sin ella. |

### SPA — el frontend del cliente { #spa-the-customer-frontend }

| Ajuste | Valor |
|---|---|
| Nombre | `Registerwerk Customer Portal` |
| Plataforma | Aplicación de una sola página (SPA) |
| URI de redirección | `https://<customer-portal-host>/` |
| Permiso de API | `api://<api-client-id>/access_as_user` |

Sin secreto de cliente — es un cliente público. El SPA declara `clientCapabilities: ['CP1']` en el
código; no hay nada más que configurar aquí.

---

## 2. Acceso condicional { #2-conditional-access }

### Exigir MFA para iniciar sesión { #require-mfa-to-sign-in }

Cree una directiva dirigida a la aplicación de la API, que conceda el acceso únicamente con
**Requerir autenticación multifactor** — o, mejor, una **fortaleza de autenticación**. Las
fortalezas integradas son *Autenticación multifactor*, *MFA sin contraseña* y *MFA resistente al
phishing*; los dos controles de concesión no pueden combinarse en una misma directiva.

> La fortaleza de autenticación solo se aplica a usuarios externos que se autentican **con Entra
> ID**. Para invitados con código de un solo uso por correo electrónico, SAML/WS-Fed o federados con
> Google, use en su lugar el control de concesión de MFA simple.

### Contexto de autenticación para step-up { #authentication-context-for-step-up }

1. **Entra ID → Acceso condicional → Contexto de autenticación** → cree un contexto (c1–c99), p. ej.
   `Registerwerk regulator-grade action`.
2. **Marque "Publish to apps".** Un contexto no publicado es invisible para los recursos y nunca
   puede satisfacerse — el síntoma es un bucle de redirecciones de inicio de sesión sin nada en los
   registros. Registerwerk verifica esto al arrancar y se niega a iniciar en modo producción si no
   está publicado.
3. Cree una directiva con ese contexto como recurso de destino, que conceda el acceso únicamente con
   la fortaleza de autenticación elegida, y fije **Sign-in frequency: Every time**.
4. Fije su id como `ENTRA_STEPUP_AUTH_CONTEXT_ID`.

La frecuencia de inicio de sesión es el verdadero control de frescura para el step-up: un token de
acceso vive entre 60 y 90 minutos y la atestación (claim) `acrs` persiste durante toda esa vida, así
que sin este ajuste un token sigue "elevado" (stepped up) mucho después de que el usuario se haya ido.

### Registrar información de seguridad { #register-security-information }

Fuerce la inscripción en el primer inicio de sesión con la **acción de usuario "Register security
information"** (es una acción de usuario, no una aplicación en la nube), o con la directiva de
registro de MFA de ID Protection.

---

## 3. Microsoft Graph — la consola de soporte del operador { #3-microsoft-graph-the-operator-support-console }

Solo hace falta para la página de estado de 2FA del cliente y la consola de teléfono perdido del
operador. Fije `ENTRA_SUPPORT_ENABLED=true` y conceda al registro de la API:

| Permiso | Tipo |
|---|---|
| `UserAuthenticationMethod.ReadWrite.All` | Aplicación |
| `User.RevokeSessions.All` | Aplicación |

Conceda el consentimiento del administrador y luego asigne a la entidad de servicio el rol de
directorio **Authentication Administrator**. Deliberadamente *no* Privileged Authentication
Administrator: Authentication Administrator puede actuar sobre miembros pero no sobre administradores,
que es precisamente la contención que se busca para una credencial que reside en la configuración de
una aplicación.

Habilite también **Temporary Access Pass** en *Authentication methods → Policies* y aplíquelo al
grupo de clientes — un TAP puede crearse para cualquier usuario, pero solo los usuarios dentro del
alcance de la directiva pueden iniciar sesión con uno.

---

## 4. Clientes federados { #4-federated-customers }

Para un cliente que mantiene su propio tenant de Entra:

1. Fije el `identity_model` de su entidad jurídica en `FEDERATED` y registre la URL de su emisor (el
   id del tenant se deriva de ella).
2. Configure los **ajustes de acceso entre tenants (cross-tenant)** en Entra para la colaboración B2B
   entrante.
3. Decida si confiar en el MFA de su tenant, y regístrelo en `idp_mfa_trusted`. Esto lo controla el
   operador: de lo contrario, un cliente que respondiera por su propio MFA podría rebajar el
   listón aplicado a sus propios usuarios.

Registerwerk no puede administrar los métodos de autenticación de un usuario federado — la consola de
soporte muestra el id de su tenant y rechaza toda acción de mutación con un 409 en lugar de hacer una
llamada a Graph que fallaría de forma confusa.

Tenga en cuenta que **nunca puede emitirse un Temporary Access Pass a un invitado externo**. La
consola lo detecta (`userType` de invitado más `#EXT#` en el UPN) y deshabilita el botón con una
explicación.

---

## 5. Entorno { #5-environment }

```bash
ENTRA_ENABLED=true
JWT_ISSUER_URI=https://login.microsoftonline.com/<tenant-id>/v2.0
JWT_AUDIENCE=api://<api-client-id>          # or the bare client id — must match the token's aud

ENTRA_TENANT_ID=<tenant-id>
ENTRA_CLIENT_ID=<api-client-id>
ENTRA_CLIENT_SECRET=<api-client-secret>
ENTRA_SPA_CLIENT_ID=<spa-client-id>
ENTRA_API_SCOPE=api://<api-client-id>/access_as_user

ENTRA_SUPPORT_ENABLED=true
ENTRA_STEPUP_AUTH_CONTEXT_ID=c1
```

`JWT_AUDIENCE` no es opcional en producción. Entra firma cada token de un tenant con las mismas
claves, así que sin una comprobación de audiencia, un token emitido para *cualquier otra aplicación de
su tenant* se aceptaría aquí como una sesión de Registerwerk. `ProductionReadinessCheck` se niega a
arrancar sin ella.

El portal del operador no se ve afectado por nada de esto: conserva el inicio de sesión HS256
integrado y el step-up local por TOTP, razón por la cual `JWT_DEV_SECRET` sigue siendo importante
incluso en una implementación totalmente habilitada para Entra.

---

## 6. Prueba de humo del tenant { #6-tenant-smoke-test }

Varios comportamientos no pueden verificarse sin un tenant real. Repase esta lista antes de declarar
lista la implementación.

- [ ] **`/actuator/health/entra` informa `UP`**, con un recuento de contextos de autenticación
      publicados distinto de cero. Esto cubre en una sola llamada la accesibilidad de Graph, la
      obtención de tokens y la disponibilidad del contexto.
- [ ] **Inicie sesión como cliente de prueba.** El acceso condicional debería forzar el registro de
      MFA si no existe ninguno.
- [ ] **Decodifique el token de acceso.** Confirme que `aud` coincide con `JWT_AUDIENCE`, y que
      `acrs`, `xms_cc` y `auth_time` están presentes. Si falta `acrs`, revise de nuevo las
      atestaciones opcionales — es el error de configuración más frecuente, con diferencia.
- [ ] **Llame a un endpoint de step-up.** Debería obtener un 401 con `error="insufficient_claims"`,
      luego una redirección, luego éxito. Si en cambio cada llamada redirige, es que `acrs` no se
      está emitiendo de forma oportunista.
- [ ] **Abra `/security`.** Debería mostrar los métodos registrados y una hora de "última
      comprobación".
- [ ] **Ejecute de principio a fin el flujo de teléfono perdido** contra una cuenta de prueba:
      restablecer métodos → revocar sesiones → emitir un TAP → iniciar sesión con el TAP → registrar
      un nuevo método. Confirme que el TAP aparece exactamente una vez en la interfaz y en ningún
      lugar de `audit_event`.
- [ ] **Pruebe el flujo del TAP contra un invitado externo.** El botón debería estar deshabilitado
      con una explicación, no fallar contra Graph.
- [ ] **Confirme que existen filas en `audit_event`** para cada acción del operador, con el
      `actor_id` correcto — esto es precisamente lo que garantiza el filtro de normalización del
      principal.

### Incertidumbres conocidas { #known-uncertainties }

Dependen de la configuración del tenant y de un comportamiento de Microsoft que no está del todo
documentado:

- Si Entra se niega a eliminar el método de autenticación **predeterminado** de un usuario mientras
  quedan otros. El adaptador elimina el predeterminado en último lugar e informa de los fallos por
  método en lugar de asumir nada.
- El comportamiento exacto del TAP para una cuenta interna pero de tipo invitado; la heurística
  `#EXT#` distingue a los invitados externos y debería confirmarse empíricamente.
- Si la confianza de MFA entre tenants satisface un requisito de contexto de autenticación para
  usuarios federados. Microsoft documenta que FIDO2, Windows Hello y la autenticación basada en
  certificados solo satisfacen la fortaleza en el tenant *de origen* (home) del usuario.
- La limitación (throttling) de Graph bajo sondeo sostenido de `/two-factor/refresh`. El backend
  limita por usuario, pero los límites de todo el tenant siguen aplicándose.
