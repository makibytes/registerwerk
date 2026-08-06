---
title: Concesiones de administrador de tokens — autoridad delegable de acción forzada
description: ASSET_TOKEN_ADMIN — el permiso delegable que controla forcedTransfer/forcedApprove/forceBurn más allá de REGISTRY_ADMIN.
---

# Concesiones de administrador de tokens — autoridad delegable de acción forzada { #token-admin-grants-delegatable-forced-action-authority }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Esta página registra las asignaciones de control previstas. No es evidencia de que una delegación sea legalmente
    válida, esté dentro de la autorización de un operador, o sea suficiente para una corrección, cancelación,
    congelación, destrucción (burn) o transferencia forzada. La capacidad, la evidencia de instrucción, la
    segregación de funciones y las reglas de instrumento/jurisdicción requieren una revisión externa.

Las operaciones de token obligatorias del registro — **forcedTransfer**, **forcedApprove** y **forceBurn** —
permiten que el registro mueva, vuelva a aprobar o destruya los tokens de un titular sin su consentimiento.
Son las herramientas más afiladas de la plataforma: una llamada indebida mueve valor real a una dirección
elegida por un atacante, o lo destruye directamente. Hasta ahora, solo podía acceder a ellas `REGISTRY_ADMIN`
(además, para `forcedTransfer`/`forcedApprove`, cualquier emisor que actuara sobre su propio activo por el
mero hecho de ser su titular).

**`ASSET_TOKEN_ADMIN`** sustituye ese atajo basado en la titularidad del emisor por un permiso explícito,
concedido por el operador. Por defecto **nadie lo tiene — ni siquiera el propio emisor de un activo.** Un
operador debe delegarlo deliberadamente a una entidad cliente designada (emisor o inversor), y solo después
de validar que el monedero de esa entidad es genuino, está en la lista blanca (whitelisted) y, para los
activos ERC-3643, verificado mediante ONCHAINID.

Nótese lo que **no** cambia: la transacción on-chain real sigue firmándose con el propio monedero operador
del registro, exactamente igual que antes. `ASSET_TOKEN_ADMIN` es puramente una **puerta de autorización a
nivel de API**: decide quién puede *pedir* al registro que realice una acción forzada, no quién la *ejecuta*
on-chain.

---

## Qué controla { #what-it-gates }

| Acción | Vía del operador | Vía del cliente |
|---|---|---|
| `forcedTransfer` / `forcedTransferSingle` | `TokenAdminController` | `IssuerTokenController` |
| `forcedApprove` | `TokenAdminController` | `IssuerTokenController` |
| `forceBurn` / `forceBurnSingle` | `TokenAdminController` | — (solo operador) |
| Equivalentes ERC-3643 (incl. por lotes) | `Erc3643Controller` | `Erc3643Controller` |
| Canton `force-transfer-canton` / `burn-holding` | `TokenAdminController` | — (solo operador) |

Cada endpoint anterior ahora requiere `hasRole('REGISTRY_ADMIN')` **o** una concesión `ASSET_TOKEN_ADMIN`
activa para la entidad de quien llama, sobre ese activo específico (véase
`AssetAccessChecker.canForceAdmin`). Todo lo demás — pausar, congelar, incluir en la lista blanca,
emitir (mint), destruir (burn, la variante no forzada) — no se ve afectado.

---

## eWpG §24 / §26 como base de delegación (Alemania) { #ewpg-24-26-as-the-delegation-basis-germany }

Las acciones forzadas se corresponden con disposiciones concretas del eWpG: `forcedTransfer` con la
**Berichtigung del §24** (corrección del registro por orden de BaFin/judicial), `forceBurn` con la
**Einziehung del §26** (cancelación obligatoria). Ambas disposiciones describen la facultad del *encargado del
registro* para corregir o cancelar una inscripción — no contemplan por sí mismas delegar esa facultad a un
cliente. La posición que adopta esta funcionalidad es que el encargado del registro (el operador) sigue
siendo legalmente responsable de cada acción forzada, con independencia de quién haya iniciado la llamada a
la API; `ASSET_TOKEN_ADMIN` es una **delegación operativa de la iniciación**, no una delegación de la
autoridad legal — el propio control dual con step-up del operador (véase más abajo) es lo que realmente
autoriza la ejecución, en cada llamada individual, ya sea que quien inicie la acción sea REGISTRY_ADMIN o una
entidad cliente con concesión.

**Otras jurisdicciones:** FR, LU y LI aún no tienen documentado en este código base un concepto directamente
análogo de "delegar el inicio de una corrección obligatoria del registro". Trate la delegación a una entidad
cliente bajo los regímenes locales de valores/DLT de esas jurisdicciones como **no revisada** — obtenga
confirmación de un asesor local antes de otorgar `ASSET_TOKEN_ADMIN` a una entidad no alemana en producción,
conforme a la convención de exención de responsabilidad usada en el resto de este directorio (p. ej.
[Sperrvermerk](sperrvermerk.md)) y en la [visión general de jurisdicciones](../legal/index.md).

---

## Modelo de concesión { #grant-model }

Dos variantes, ambas creadas/revocadas exclusivamente por `REGISTRY_ADMIN` con
`@RequiresStepUp(requireSecondApprover = true)` (el mismo flujo de TOTP + doble control (4-eyes)
utilizado para las propias acciones forzadas):

- **Con alcance de activo** (`POST /api/v1/assets/{assetId}/token-admin-grants`) — el caso habitual:
  un activo, una entidad beneficiaria.
- **A nivel de entidad** (`POST /api/v1/entities/{entityId}/token-admin-grants`) — se aplica a todos
  los activos de los que esa entidad sea emisora/titular, presentes y futuros. Una delegación de
  confianza sustancialmente mayor; resérvela para un emisor recurrente y de confianza, no como opción por defecto.

### Elegibilidad, validada una vez en el momento de la concesión { #eligibility-validated-once-at-grant-time }

| Beneficiario | Comprobación del monedero |
|---|---|
| Emisor propio del activo (con alcance de activo) | Monedero vinculado a la identidad de organización de la entidad (`orgidentity.PermissionGate.isWalletBoundToEntity`) |
| Un titular/inversor del activo (con alcance de activo) | `AssetHolder.whitelisted = true` para ese monedero en ese activo, **más** `IdentityRegistry.isVerified` de T-REX si el activo es ERC-3643/CONF_ERC3643 |
| A nivel de entidad | Monedero vinculado a la identidad de organización de la entidad (no hay un único activo frente al que verificar la lista blanca) |

La comprobación superada queda registrada en la concesión (`eligibilityBasis`) a efectos de auditoría — **no**
se vuelve a verificar en vivo en cada llamada posterior de acción forzada; solo se verifica el estado
`ACTIVE`/no vencido de la propia concesión. Si un monedero posteriormente se retira de la lista blanca o se
bloquea, el operador debe revocar la concesión por separado.

### Ciclo de vida { #lifecycle }

Sigue el mismo patrón que el `HolderBlock` de [Sperrvermerk](sperrvermerk.md): `ACTIVE → REVOKED` (manual,
step-up + doble control) o `ACTIVE → EXPIRED` (trabajo nocturno `@Scheduled` una vez pasado `expiresAt`, si se configuró uno).

---

## UI del operador { #operator-ui }

- **Con alcance de activo** — pestaña "Token Admin Grants" en la página de detalle del activo: enumera las
  concesiones activas, concede nuevas (entidad, monedero, configuración de cadena opcional, base legal,
  vencimiento opcional), revoca.
- **A nivel de entidad** — `/compliance/token-admin-grants`: busca una entidad y gestiona sus concesiones a
  nivel de entidad. Es deliberadamente una página distinta de la pantalla de Permissions del ecosistema
  orgidentity (`/permissions`), que no guarda relación con esta: aquella gobierna los permisos de organización
  del dApp-marketplace y no tiene ninguna dimensión de activo.

---

## Pista de auditoría { #audit-trail }

Cada concesión y revocación publica los eventos de auditoría `ASSET_TOKEN_ADMIN_GRANTED` /
`ASSET_TOKEN_ADMIN_REVOKED` (`asset.events.AssetTokenAdminGrantedEvent` / `...RevokedEvent`), capturados
automáticamente mediante la [cadena de hash de auditoría](../platform/audit-log.md) — se registran el actor,
la entidad, el activo (o "a nivel de entidad"), el monedero, la base legal y la base de elegibilidad.
