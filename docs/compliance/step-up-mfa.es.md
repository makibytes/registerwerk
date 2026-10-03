---
title: Step-up MFA y doble control (4-eyes)
description: Autenticación reforzada (step-up) y control dual (doble control / 4-eyes) para operaciones reguladas de alto riesgo.
---

# Step-up MFA y doble control (4-eyes) { #step-up-mfa-4-eyes }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Esta página describe las asignaciones de control previstas. No es evidencia de que el flujo de MFA o de control
    dual configurado satisfaga un requisito legal, regulatorio, de seguridad o de segregación de funciones en
    particular. Las funciones, las acciones protegidas, el nivel de garantía, la recuperación y la evidencia de
    auditoría requieren una revisión específica de la implementación.

Ciertas operaciones en Registerwerk son tan trascendentes — o están tan claramente sujetas a doble supervisión por
regulación — que una sesión de inicio de sesión normal no es suficiente. La **autenticación reforzada (step-up)**
exige que el operador vuelva a probar su identidad en el momento de ejecutar la operación. El **principio de doble
control** (Vier-Augen-Prinzip, «4-eyes») exige además que un segundo aprobador independiente confirme la acción
antes de que se ejecute.

---

## Por qué existe esto { #why-this-exists }

| Normativa | Obligación |
|---|---|
| GwG §6(2) | Sistemas de control interno: las decisiones de alto riesgo requieren doble supervisión documentada |
| eWpG §16 | Las operaciones de bloqueo (Sperrvermerk) deben ser rastreables hasta un operador verificado y nombrado |
| BaFin KAIT | La seguridad de TI requiere MFA para el acceso privilegiado a sistemas críticos |
| DSGVO Art. 32 | Medidas técnicas apropiadas para proteger los datos personales: MFA es la línea base |

---

## Operaciones protegidas { #protected-operations }

La anotación `@RequiresStepUp` se coloca en los siguientes endpoints y métodos de servicio. Las operaciones marcadas con **doble control (4-eyes)** requieren además un segundo aprobador.

| Operación | Step-up | Doble control (4-eyes) | Motivo |
|---|---|---|---|
| `forceTransfer` | ✅ | ✅ | Operación on-chain irreversible |
| `forceBurn` | ✅ | ✅ | Destrucción permanente de tokens |
| `forceApprove` | ✅ | ✅ | Anulación de cumplimiento |
| `setSupplyCap` | ✅ | ✅ | Cambio de parámetro económico |
| Anulación de KYC (aprobar pese a un indicador) | ✅ | ✅ | Elusión de la puerta AML |
| Crear Sperrvermerk | ✅ | ✅ | Restricción legal sobre el titular |
| Levantar Sperrvermerk | ✅ | ✅ | Eliminación de una restricción legal |
| Iniciar modo soporte (impersonation) | ❌ ¹ | ❌ | Acceso privilegiado a los datos del cliente |
| Aceptar hit de detección | ✅ (puntuación alta) | ✅ (puntuación ≥ 80) | Anulación de AML para un hit confirmado |
| Exportación de clave privada del monedero (break-glass) | ✅ | ✅ | Acceso al material de la clave |
| Entra: eliminar un método de autenticación | ✅ | ❌ | Elimina un factor obsoleto |
| Entra: restablecer todos los métodos de autenticación | ✅ | ✅ | Fuerza un nuevo registro de MFA para otra persona |
| Entra: revocar sesiones de inicio de sesión | ✅ | ❌ | Solo impacto en disponibilidad, sin ganancia de privilegios |
| Entra: emitir un Temporary Access Pass | ✅ | ✅ | Una credencial portadora que autentica *como* el cliente |

¹ `AdminImpersonationController` no lleva ningún `@RequiresStepUp` hoy, y el modo soporte se rechaza
categóricamente cuando `ENTRA_ENABLED=true`. Esta fila afirmaba antes contar con protección step-up que el
código no implementa.

---

## Dos vías { #two-tracks }

La forma en que se prueba el segundo factor depende de quién emite los tokens de sesión. Ambas vías se aplican
mediante la misma anotación `@RequiresStepUp` y el mismo aspecto; solo la verificación difiere.

### TOTP local — `ENTRA_ENABLED=false`, y siempre en el portal del operador { #local-totp-entraenabledfalse-and-the-operator-portal-always }

RFC 6238 TOTP (HMAC-SHA1, ventana de 30 segundos, 6 dígitos), verificado por
`StepUpTokenIssuer`. Inscríbase en `POST /api/v1/auth/step-up/enroll`, confirme en
`/enroll/confirm`, y luego canjee un código en `POST /api/v1/auth/step-up` por un token de corta duración
que lleva `acr=stepup`, válido durante 10 minutos. Quien llama envía ese token en lugar de su token de sesión
en la solicitud protegida. El rechazo es **403**.

> **WebAuthn / FIDO2 no está implementado.** El campo `method` de la solicitud de step-up se acepta
> y se ignora. Versiones anteriores de este documento lo describían como el factor principal; nunca
> existió en el código. Con el inicio de sesión de Entra, hay MFA resistente al phishing disponible, pero a
> través de Conditional Access, no a través de este módulo.

### Contexto de autenticación de Entra: `ENTRA_ENABLED=true` { #entra-authentication-context-entraenabledtrue }

El access token debe llevar el contexto de autenticación de Conditional Access requerido en su claim `acrs`.
Registerwerk no verifica un factor por sí mismo; declara un requisito y deja que Conditional Access decida
qué lo satisface — lo que permite a un operador exigir MFA resistente al phishing para las transferencias
forzosas sin ningún cambio de código.

El rechazo es un **claims challenge 401**, de modo que la SPA se reautentica para esa única acción en lugar
de cerrar la sesión del usuario:

```
WWW-Authenticate: Bearer realm="", authorization_uri="…",
                  error="insufficient_claims", claims="<base64>"
```

El identificador del contexto es configuración, indexada por `@RequiresStepUp(reason = …)`:

```yaml
registerwerk.auth.step-up.entra:
  auth-context-id: c1                 # ENTRA_STEPUP_AUTH_CONTEXT_ID
  reason-overrides:
    FORCE_BURN_EWG26: c2
    "Payment rail creation": c1       # quote reasons containing spaces
```

Se valida frente al tenant en el arranque: un contexto que no existe, o que existe pero **no está publicado
para las apps**, hace fallar el arranque en modo producción. Un contexto no publicado nunca puede satisfacerse
y produce un bucle de redirección de inicio de sesión sin nada en los logs que lo explique.

#### Aquí la frescura funciona de otra manera { #freshness-works-differently-here }

Un access token de Entra dura entre 60 y 90 minutos y `acrs` persiste durante toda su vida útil, de modo que
aplicar `maxAgeMinutes` sobre `iat` forzaría una redirección completa del navegador en casi todas las
llamadas protegidas. En su lugar:

- el control de frescura **principal** es la política de Conditional Access sobre el contexto de autenticación
  (fije *Sign-in frequency: Every time* para las acciones de nivel regulatorio);
- `maxAgeMinutes` se compara con el claim `auth_time` como respaldo.

`auth_time` es un claim opcional que debe solicitarse en el registro de la aplicación de la API. Sin él, la
comprobación recae en `iat`, que es más débil: el backend registra una advertencia la primera vez que ve un
token de Entra que carece de él.

---

## Implementación del doble control (4-eyes) { #4-eyes-implementation }

La aplicación actual del control dual exige dos usuarios `REGISTRY_ADMIN` distintos. No existe un rol de
aplicación `SECOND_APPROVER`, y un `COMPLIANCE_OFFICER` no se acepta como sustituto salvo que la
implementación se modifique y se revise por separado.

**El doble control es idéntico en ambas vías**: un token de control dual siempre se acuña localmente tras la
verificación TOTP y siempre se valida con el decodificador HS256 local, por lo que no depende de cómo se
demostró el factor principal.

```mermaid
sequenceDiagram
    participant Initiator
    participant Approver
    participant Backend

    Approver->>Backend: POST /api/v1/auth/step-up { code, action, target[, targetBody] }
    Backend-->>Approver: approver token (acr=stepup, stepup_scope, stepup_target, jti; 5 min, single use)
    Approver->>Initiator: Hand over the approver token
    Initiator->>Backend: POST /api/v1/auth/step-up { code, action }
    Backend-->>Initiator: initiator step-up token
    Initiator->>Backend: Protected call — Authorization: initiator token,<br/>X-Dual-Control-Token: approver token
    Backend->>Backend: Validate both, then execute + audit with both identities
```

Invariantes clave aplicadas por `StepUpEnforcementAspect` y `StepUpTokenValidator`:

- El iniciador y el aprobador **deben ser usuarios diferentes** (comparación de `sub`)
- El token del aprobador debe contener `stepup_scope` **exactamente igual** al `reason` de la anotación —
  de lo contrario, una sola aprobación sería una credencial genérica válida para cualquier acción de doble
  control dentro de su ventana de validez
- El aprobador debe seguir siendo un **`REGISTRY_ADMIN` habilitado en la base de datos**, no solo según las
  claims del token, que reflejan el estado únicamente en el momento en que se acuñó
- La aprobación está **vinculada a la solicitud para la que se concedió** (K3). El aprobador la emite con `action` *y* `target` (`"METHOD /ruta?query"` de la llamada exacta; además `targetBody`, el cuerpo JSON de la solicitud, que vincula todo motivo). El token lleva `stepup_target`, el SHA-256 en base64url de la solicitud canónica (`v1`, método en mayúsculas, ruta sin barra final, query ordenada y el hash del cuerpo JSON canónico: claves ordenadas, sin espacios, números decimales exactos en forma simple). El backend calcula el mismo resumen a partir de la solicitud real; si difiere, la llamada se rechaza con **403**. Ya no se aceptan tokens sin destino
- **Los tokens de aprobación solo valen en la cabecera.** Una aprobación lleva `use=dual_control` y la audiencia `registerwerk-dual-control`. Se acepta en `X-Dual-Control-Token` y en ningún otro sitio: como `Authorization: Bearer` (o cookie de sesión) en cualquier endpoint, también en los `@RequiresStepUp`, se rechaza con **403**; una aprobación que alguien tiene en su poder nunca puede reutilizarse como la sesión de otra persona. Los tokens de autenticación reforzada ordinarios (sin alcance ni marca) siguen siendo la prueba propia del llamante.
- **El cuerpo siempre está vinculado.** Cada motivo vincula el cuerpo canónico de la solicitud (`targetBody`, omitido si la solicitud no tiene). Las excepciones figuran en `registerwerk.auth.step-up.dual-control.body-opt-out-reasons`: cargas que no son JSON (subida de term sheet, importación de keystore, importación CSV CASP) y cargas que son material de clave secreto o la contraseña de un keystore (importación de clave en bruto, exportación de keystore); método, ruta y query siguen vinculados. Los números son decimales exactos, nunca `double`; un cuerpo con una clave JSON repetida o una solicitud con un parámetro de query repetido no puede vincularse y se rechaza. El token de aprobación sigue siendo de un solo uso aunque se restrinja `bind-target-reasons`.
- **El arranque (bootstrap) es una puerta de un solo sentido.** La excepción de «basta un step-up» solo rige hasta que hayan existido a la vez dos `REGISTRY_ADMIN` habilitados y con TOTP. La base de datos registra ese momento (`dual_control_bootstrap`, fijado por trigger, nunca borrado); desde entonces la excepción no vuelve, aunque después se deshabilite a un administrador o este pierda su autenticador. Deshabilitar o eliminar una cuenta de operador, `REGISTRY_ADMIN`, `COMPLIANCE_OFFICER` o `AUDIT` exige el segundo aprobador (`OPERATOR_USER_DISABLE`, `OPERATOR_USER_DELETE`).
- La aprobación es de **un solo uso**: su `jti` se escribe en `dual_control_token_use` junto con el evento de auditoría en una única transacción (un segundo uso, en cualquier réplica, da **403**). Una acción que falla después de consumirse la aprobación requiere una nueva aprobación
- La aprobación solo se acepta durante una **ventana corta** tras su emisión (`registerwerk.auth.step-up.dual-control.window-seconds`, 300 s por defecto); el token de autenticación reforzada del iniciador conserva sus 10 minutos

---

## Alta de TOTP, almacenamiento y restablecimiento { #totp-enrolment-storage-reset }

- **Sin confianza en el primer uso.** Iniciar un alta (`POST /api/v1/auth/step-up/enroll`) exige la contraseña actual de la cuenta en el cuerpo (`{ "currentPassword": "…" }`): una sesión robada o desatendida no puede vincular el autenticador de un atacante. Las contraseñas erróneas cuentan para el mismo bloqueo que los códigos erróneos. Las cuentas cuyo segundo factor gestiona un proveedor de identidad externo no pueden dar de alta un autenticador local. La confirmación (`/enroll/confirm`) consume el intervalo de tiempo del código, por lo que no puede reutilizarse como código de autenticación reforzada.
- **Cifrado en reposo.** El secreto TOTP se cifra con cifrado de sobre (AES-256-GCM, una clave de datos nueva por valor envuelta por la KEK de la plataforma, el id de usuario como datos autenticados adicionales) en `app_user.totp_secret`; `totp_secret_kid` registra el proveedor de KEK. Los secretos guardados en claro por versiones anteriores se cifran mediante una tarea de arranque; la verificación rechaza un secreto que siga en claro (un administrador restablece el registro).
- **Estado compartido entre réplicas.** La protección contra repetición (RFC 6238 §5.2: se rechaza un código en el último intervalo aceptado o anterior) y el bloqueo contra fuerza bruta (5 códigos erróneos o repetidos bloquean la autenticación reforzada durante 15 minutos) residen en la tabla `totp_state` y se actualizan de forma atómica: un código aceptado en una réplica se rechaza en todas las demás. Cada intento se reserva antes de comparar el código, de modo que los intentos paralelos comparten un presupuesto de cinco.
- **Baja por el propio usuario.** `POST /api/v1/auth/step-up/disenroll { "code": "…" }` exige un código actual válido, elimina el alta y finaliza las sesiones del usuario. El usuario debe volver a darse de alta antes de cualquier acción de autenticación reforzada.
- **Restablecimiento por el operador (dispositivo perdido).** `POST /api/v1/admin/users/{id}/totp-reset` exige autenticación reforzada **y** un segundo aprobador (motivo `TOTP_RESET`). Elimina el alta, finaliza las sesiones del usuario y escribe el evento de auditoría `TOTP_RESET` con ambas identidades; no se puede restablecer así el alta propia. El usuario se da de alta de nuevo en el siguiente inicio de sesión.
- **Supervisión.** El indicador `registerwerk_stepup_unenrolled_operators` cuenta las cuentas locales habilitadas `REGISTRY_ADMIN` / `COMPLIANCE_OFFICER` con más de siete días y sin autenticador. Es una métrica de aviso, no un fallo de arranque; genere una alerta con valores superiores a cero.

Eventos de auditoría del ciclo de vida: `TOTP_ENROLMENT_STARTED`, `TOTP_ENROLLED`, `TOTP_DISENROLLED`, `TOTP_RESET`; `DUAL_CONTROL_APPROVED` registra ahora también el id del token de aprobación y el resumen del destino, y `DUAL_CONTROL_BOOTSTRAP_USED` señala la excepción de actor único mientras existan menos de dos administradores con TOTP.

---

## Aplicación de AOP { #aop-enforcement }

El `StepUpEnforcementAspect` intercepta cualquier método anotado con `@RequiresStepUp` y:

1. Lee el JWT autenticado del contexto de seguridad
2. Se ramifica según la vía activa:
   - **local** — requiere `acr=stepup` y `iat` dentro de `maxAgeMinutes` (predeterminado 10); el fallo es **403**
   - **Entra** — requiere que `acrs` contenga el contexto de autenticación configurado y que `auth_time`
     esté dentro de `maxAgeMinutes`; el fallo es un **claims challenge 401**
3. Si `requireSecondApprover = true`, valida la cabecera `X-Dual-Control-Token` y expone el id del aprobador
   como atributo de solicitud `stepup.dualControlApproverId`, que los controladores leen con
   `@RequestAttribute` — no deben decodificar el token por su cuenta
4. El claims challenge lo emite `ClaimsChallengeAdvice`, no Spring Security: la excepción se lanza desde un
   `@Around` de AOP y por eso la resuelve `@RestControllerAdvice`; el `BearerTokenAuthenticationEntryPoint`
   de Spring Security no tiene, en cualquier caso, ninguna ruta de código capaz de serializar un parámetro
   `claims=`

---

## Eventos de auditoría { #audit-events }

Cada evento de autenticación step-up y cada operación protegida genera un `AuditEvent`:

| Tipo de evento | Contenido |
|---|---|
| `STEP_UP_ISSUED` | ID de usuario, método, marca de tiempo |
| `DUAL_CONTROL_INITIATED` | ID del iniciador, tipo de operación, hash de parámetros de operación |
| `DUAL_CONTROL_CONFIRMED` | ID del aprobador, tipo de operación, referencia del token_confirmado |
| `PROTECTED_OPERATION_EXECUTED` | Ambos ID de usuario, tipo de operación, parámetros de operación completos |
| `STEP_UP_FAILED` | ID de usuario, motivo de la falla, dirección IP |

Estos eventos forman parte de la [cadena de auditoría](../platform/audit-log.md) a prueba de manipulaciones y no se pueden eliminar ni modificar.
