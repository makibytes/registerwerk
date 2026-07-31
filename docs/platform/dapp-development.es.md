# Creación de dApps para el ecosistema Registerwerk { #building-dapps-for-the-registerwerk-ecosystem }

Registerwerk proporciona un **marco de identidad y permisos en cadena** sobre el que las instituciones financieras
construyen dApps de tokenización, además de un **mercado (marketplace)** donde esas dApps se revisan, se anclan en cadena y se ofrecen a otros participantes. Esta guía cubre el flujo de trabajo del desarrollador
de principio a fin.

## Los componentes básicos { #the-building-blocks }

| Contrato | Propósito |
|---|---|
| `OrgRegistry` | Vincula los monederos de los miembros a las organizaciones (una organización = su dirección ONCHAINID). Cada monedero pertenece como máximo a una organización por cadena. |
| `PermissionRegistry` | El operador otorga permisos a las organizaciones; los administradores de la organización los delegan en roles de miembros y pueden marcarlos como restringidos por rol. |
| `EcosystemTrustedIssuersRegistry` | Emisores de atestaciones (claims) de confianza según el tema de atestación ONCHAINID (1 = KYC, 2 = AML, 3 = Acreditación). |
| `DappRegistry` | Ancla manifiestos de mercado aprobados (keccak256) y atestaciones de instancia opcionales. |
| `PermissionOracle` | **La única dirección que almacena su dApp.** Compone todo lo anterior detrás de una fachada de consulta estable. |

Su dApp nunca se comunica directamente con los registros — solo con `PermissionOracle`
(`IPermissionOracle`), al que el operador puede redirigir hacia registros actualizados sin
romper las dApps ya implementadas.

## Escribir un contrato con control de acceso (gated) { #writing-a-gated-contract }

Herede `RegisterwerkGated` (en `contracts/src/ecosystem/RegisterwerkGated.sol`) y pase
la dirección del oráculo en su constructor:

```solidity
import "@registerwerk/ecosystem/RegisterwerkGated.sol";

contract LoanDesk is RegisterwerkGated {
    bytes32 public constant OPEN_LOAN = keccak256("loandesk.open");

    constructor(IPermissionOracle oracle_) RegisterwerkGated(oracle_) {}

    function openLoan() external requiresPermission(OPEN_LOAN) requiresClaim(1) {
        // caller's wallet belongs to an active org holding "loandesk.open",
        // and the org's ONCHAINID carries a valid KYC claim
    }
}
```

Modificadores disponibles:

- `requiresPermission(bytes32 permission)` — concesión a nivel de organización (más delegación de
  rol cuando la organización marcó el permiso como restringido por rol).
- `requiresClaim(uint256 topic)` — una atestación válida de ese tema en el ONCHAINID de la
  organización que llama, firmada por un emisor de confianza del ecosistema.
- `requiresActiveMember` — el monedero de quien llama está vinculado a una organización no suspendida.

Los identificadores de permiso son `keccak256("<your-slug>.<action>")`. Su slug de mercado es su
espacio de nombres: los manifiestos que declaran permisos fuera de `<slug>.*` se rechazan a menos
que el código ya exista como permiso de la plataforma.

Un ejemplo mínimo ejecutable se encuentra en `contracts/test/ecosystem/SampleGatedDapp.t.sol`. Para
dos dApps de referencia completamente empaquetadas y listas para el mercado — incluida una
integración real con ERC-3643 (T-REX) — consulte [dApps de ejemplo de referencia](#reference-example-dapps)
más abajo.

## El manifiesto { #the-manifest }

El mercado almacena **solo metadatos**: sus contenedores permanecen en su propio registro OCI,
fijados por digest. El manifiesto (JSON, esquema:
`backend/src/main/resources/schemas/dapp-manifest.schema.json`) describe:

```json
{
  "slug": "loandesk",
  "name": "Loan Desk",
  "version": "1.0.0",
  "description": "Institutional loan origination on Registerwerk rails.",
  "category": "lending",
  "contracts": [
    { "name": "LoanDesk", "abiSha256": "<sha256 of the ABI json>" }
  ],
  "requiredPermissions": [
    { "code": "loandesk.open", "rationale": "Open loan requests on behalf of the org" }
  ],
  "requiredClaimTopics": [1],
  "images": [
    { "name": "backend",  "role": "backend",
      "ref": "registry.bank.example/loandesk/backend@sha256:…" },
    { "name": "frontend", "role": "frontend",
      "ref": "registry.bank.example/loandesk/frontend@sha256:…" }
  ],
  "deployment": { "composeUrl": "https://…/docker-compose.yml", "composeSha256": "…" },
  "docsUrl": "https://docs.bank.example/loandesk",
  "contact": "dapps@bank.example",
  "license": "commercial",
  "pricingNote": "Contact publisher"
}
```

Reglas aplicadas en la validación:

- **La fijación por digest es obligatoria** — `images[].ref` debe coincidir con
  `…@sha256:<64 hex>`; las referencias que solo indican una etiqueta (tag) se rechazan.
- El `slug` del manifiesto debe coincidir con el slug del listado.
- `requiredPermissions[].code` debe estar dentro de su espacio de nombres o ser un permiso ya
  existente de la plataforma.

## Declarar métodos de pago { #declaring-payment-methods }

Emitir un token de activo es solo la mitad de la historia: la mayoría de las dApps también
necesitan una pata de efectivo (pagos de suscripción, pagos de cupón/dividendo, amortizaciones).
En lugar de que cada publicador construya y audite sus propias vías de pago, el operador del
registro selecciona un catálogo de vías ya preparadas — monedas estables con divulgación
relacionada con MiCAR y campos de atestación introducidos por el operador, la API de pago
instantáneo de Pontes, la liquidación de entrega contra pago al estilo ERC-7573, y el SEPA
clásico fuera de cadena — y su manifiesto puede simplemente referenciarlas por código:

```json
"paymentMethods": [
  { "rail": "aueur", "note": "Primary-market subscription plus coupon and redemption payouts" },
  { "rail": "usdc" },
  { "rail": "erc7573-dvp", "note": "Same-transaction DvP; exact-leg, finality, and legal-register checks remain external" }
]
```

Obtenga el catálogo vigente de vías de pago habilitadas en `GET /api/v1/payment-rails/catalog`
(que también aparece en el paso "Payment methods" del asistente de publicación) y copie un
`code`. Cada entrada de vía se valida en el envío **y de nuevo en la aprobación** — una vía que
el operador deshabilitó entretanto bloquea la aprobación de la versión hasta que se actualice el
manifiesto.

Esto es orientativo, no una lista blanca: su dApp siempre puede implementar su propia lógica de
pago. Declárela como una entrada `custom` en lugar de una referencia `rail`:

```json
"paymentMethods": [
  { "custom": { "name": "Own SEPA collection account", "description": "Publisher-run SEPA rail, settled off-chain", "currency": "EUR" } }
]
```

Las entradas `custom` pasan la validación incondicionalmente, pero se marcan de forma destacada
para el operador durante la revisión (y para los inversores en la página de detalle del
catálogo) — el mercado puede ver exactamente qué se salió del camino cómodo de las "vías
provistas por el registro".

Para las dApps que quieran ofrecer entrega contra pago atómica por sí mismas (por ejemplo, una
mesa de mercado secundario), el contrato `DvpSettlement` del operador
(`contracts/src/settlement/DvpSettlement.sol`) implementa DvP en la misma cadena al estilo
ERC-7573: una parte bloquea en depósito en garantía el activo o la pata de pago, la contraparte
liquida ambas patas de forma atómica, o la operación expira y quien la bloqueó la recupera.
Consulte su NatSpec para la advertencia sobre el depósito en garantía de ERC-3643 (los tokens
T-REX exigen que el ONCHAINID del contrato de liquidación esté verificado en el registro de
identidad antes de poder depositarse en garantía — bloquear en su lugar la pata de pago evita
este requisito para los valores tokenizados).

## Flujo de trabajo de publicación { #publication-workflow }

1. **Requisito previo:** su empresa está registrada como organización en cadena (por el lado del
   operador) y su monedero de publicación está vinculado a ella
   (Portal del cliente → Company Admin → Organization).
2. Portal del cliente → **My dApps** → *New dApp* (slug + cadena de anclaje).
3. Pegue el manifiesto en el asistente de publicación → la validación del lado del servidor
   devuelve los errores y `manifestHash = keccak256(manifest_raw_bytes)` como cadena hexadecimal
   con prefijo 0x.
4. **Firme** con un monedero de organización vinculado: `personal_sign` (EIP-191) se invoca con la
   ***cadena* hexadecimal 0x del hash** como mensaje — no con los 32 bytes del hash sin formato.
   Es una elección deliberada para que cualquier interfaz de monedero muestre la cadena
   hexadecimal legible que se está firmando; los verificadores deben recuperar la firma contra
   esa misma cadena (véase [Verificación de integridad](#integrity-verification-consumers) más
   abajo).
5. **Envíe** — el operador del registro revisa con step-up + 4-eyes.
6. Al aprobarse, el backend llama a `DappRegistry.registerDapp(keccak256(slug), publisherOrg,
   manifestHash, …)`; una vez confirmada la transacción, el listado queda activo en el catálogo.

Las actualizaciones de versión repiten los pasos 3–6; el nuevo hash se ancla con
`DappRegistry.updateManifest` y la versión anterior se marca como sustituida.

## Verificación de integridad (consumidores) { #integrity-verification-consumers }

Todo lo necesario para verificar un listado de forma independiente está en el detalle del catálogo:

```bash
# 1. The manifest hash must match the onchain anchor
MANIFEST_HASH=$(cast keccak "$(cat manifest.json)")
cast call $DAPP_REGISTRY "getDapp(bytes32)" $(cast keccak "loandesk") --rpc-url $RPC

# 2. The signature must recover to the declared publisher wallet, which must be a bound
#    member wallet of the publisher org. Recovery is over the hex *string* $MANIFEST_HASH
#    (EIP-191 personal_sign), not the raw 32 hash bytes:
cast wallet verify --address $PUBLISHER_WALLET "$MANIFEST_HASH" $SIGNATURE

# 3. Pull images only by the digests listed in the manifest
```

## Atestación de instancia (opcional) { #instance-attestation-optional }

Las instancias de contrato implementadas de su dApp pueden atestiguarse en `DappRegistry`
(`attestInstance`) por el administrador de su organización. Otros contratos pueden entonces exigir
`oracle.isApprovedInstance(caller)` — una capa de composición opcional; deliberadamente no está
integrada en `hasPermission`, ya que las implementaciones autoalojadas controlan a quienes las
llaman.

## Componibilidad DeFi externa { #external-defi-composability }

`PermissionOracle` y `DvpSettlement` pueden ser invocados libremente y sin permiso por **cualquier**
contrato externo — no solo por las dApps del mercado de Registerwerk. Ninguno de los dos tiene
`onlyRole` ni lista blanca:

- **`PermissionOracle`** — un protocolo DeFi externo (su propio pool, bóveda o mercado de
  préstamos) puede llamar a `hasPermission`/`hasClaimTopic`/`isActiveMember` sobre cualquier
  dirección de monedero para restringir su *propia* lógica a inversores verificados por
  Registerwerk, sin tocar nunca un token de valor de Registerwerk ni custodiar fondo alguno del
  que el oráculo tenga conocimiento. Este es el modelo de interoperabilidad `ORACLE_ONLY` (véase
  `DefiInteropModel` en el módulo `kyc` del backend) — riesgo de custodia cero, ya que el oráculo
  nunca custodia nada; solo responde a "¿tiene este monedero KYC para el tema X?".
  Una restricción que conviene interiorizar: el oráculo comprueba la pertenencia a organización
  del **propio monedero consultado**, no la de quien llama. Si su contrato necesita *ser* él mismo
  la identidad comprobada (por ejemplo, para llamar a una función de dApp de Registerwerk
  restringida como `msg.sender`), la dirección de su contrato debe darse de alta a través de
  `OrgRegistry` como cualquier otro monedero de miembro — no existe un atajo genérico de "todo
  contrato inteligente pasa".
- **`DvpSettlement`** — un depósito en garantía genérico y sin restricciones al estilo ERC-7573,
  utilizable por cualquier protocolo externo para intercambios atómicos de activo↔stablecoin, con
  total independencia del marco de permisos del ecosistema. Lea con atención su advertencia de
  NatSpec antes de integrarlo: depositar en garantía un activo ERC-3643 mediante `lockAsset`
  exige que el propio `DvpSettlement` pase `isVerified()` en el registro de identidad de ese token
  (un paso de alta único a cargo del agente de registro del token); llamar en su lugar a
  `lockPayment` evita esto por completo, ya que entonces la pata del valor tokenizado se mueve
  directamente de vendedor a comprador como una transferencia a nivel de contrato, en lugar de
  quedar en depósito en garantía. Superar las comprobaciones técnicas del token no establece
  cumplimiento ni liquidación legal o regulatoria.

Para la pregunta más difícil — si un protocolo externo puede *mantener* un token de valor de
Registerwerk como saldo agrupado (un pool de AMM, un mercado de préstamos) — véase
[`docs/platform/defi-interoperability.md`](./defi-interoperability.md), que explica por qué eso
exige una estructura de nominado/custodio autorizada (el modelo `NOMINEE_POOL`) en lugar de un pool
anónimo y sin permisos, y cómo funciona la exención de nominado de `EwpgComplianceModule`.

## dApps de ejemplo de referencia { #reference-example-dapps }

Este repositorio incluye tres ejemplos de referencia técnica con manifiestos, código fuente en
Solidity, pruebas y un `README`. Son ejemplos, no plantillas de producto aprobadas, y se siembran
como listados de mercado de demostración en estado `PUBLISHED` por `EcosystemDemoDataSeeder`
cuando `registerwerk.seed-demo-data=true`:

| dApp | Slug | Muestra |
|---|---|---|
| **Boardroom Governance** | `boardroom` | El marco de gestión de permisos al completo: proponer/votar/escrutar restringido por permisos + atestaciones ONCHAINID (KYC, Acreditación), y el flujo de **restricción por rol / delegación de administrador de organización** en `boardroom.tally`. |
| **eWpG Bond Desk** | `bond-desk` | Un ejemplo técnico ERC-3643/T-REX con una pata de pago en token configurada. `subscribe` realiza la transferencia de pago y la acuñación en una sola transacción; `payCoupon`/`redeem` ejercitan los controles de tiempo/idempotencia. No es un bono clasificado legalmente, ni un acuerdo de pago verificado, ni prueba de liquidación legal. |
| **eWpG Repo & Lending Facility** | `repo-facility` | Un ejemplo técnico de préstamo con garantía, con un lado de prestamista en stablecoin abierto y un lado de prestatario restringido por contrato. El uso en producción está bloqueado en espera de la calificación legal, custodia/control, ejecución de la garantía, oráculo, insolvencia, elegibilidad y aprobación de seguridad. Las comprobaciones de identidad del token por sí solas no hacen que la ejecución de la garantía sea conforme. Véase [Interoperabilidad DeFi](./defi-interoperability.md#ewpgrepofacility-the-primary-exit-liquidity-mechanism). |

| | Ruta |
|---|---|
| Contratos | `contracts/src/examples/{BoardroomGovernance,EwpgBondDesk,EwpgRepoFacility,MockStablecoin}.sol`, `contracts/src/settlement/DvpSettlement.sol` |
| Pruebas | `contracts/test/examples/{BoardroomGovernance,EwpgBondDesk,EwpgRepoFacility}.t.sol`, `contracts/test/settlement/DvpSettlementTest.t.sol` |
| Ayudante de arranque T-REX | `contracts/test/helpers/TrexSuiteDeployer.sol` — la puesta en marcha completa de T-REX + ONCHAINID (autoridad de implementación, fábrica de identidades, módulo de cumplimiento), reutilizada por la prueba y el script de implementación de la mesa de bonos |
| Scripts de implementación | `contracts/script/DeployEwpgTrexBond.s.sol`, `contracts/script/DeployExampleDapps.s.sol` (boardroom, bond desk), `contracts/script/DeployLiquidityDapps.s.sol` (repo facility, más `EwpgPaymaster` — se mantiene en un script aparte porque ambos usan pragma `^0.8.36` y no pueden compartir unidad de compilación con los contratos dependientes de erc3643 anteriores; véase el NatSpec propio de ese script) |
| Manifiestos | `backend/src/main/resources/demo/dapps/{boardroom,bond-desk,repo-facility}.manifest.json` — leídos también directamente por el sembrador de datos de demostración (`registerwerk.seed-demo-data=true`), que publica los tres como listados de mercado en vivo con firmas reales verificables de forma independiente |
| Guías | `examples/dapps/{boardroom,bond-desk,repo-facility}/README.md` |

Ejecute `forge test --match-path 'test/examples/*'` para ver los tres casos ejercitados de
principio a fin, incluidas — para la mesa de bonos — identidades ONCHAINID reales y atestaciones
KYC/AML firmadas con ECDSA a través de un `ClaimIssuer` en cadena.

Otros dos contratos demuestran el puente `NOMINEE_POOL` y el patrón de AMM para stablecoins de
[Interoperabilidad DeFi](./defi-interoperability.md) — a diferencia de las tres dApps anteriores,
se entregan solo como Solidity con pruebas (sin manifiesto, sin sembrarse como listados de mercado
en vivo):

- `contracts/src/examples/CompliantSecondaryMarket.sol` — una mesa de mercado secundario de
  nominado/ómnibus restringida por `secondary-market.trade` + el tema de atestación `NOMINEE` (4);
  liquida cada operación a través del `DvpSettlement` anterior, sin modificar y sin
  restricciones, y sus ejecuciones sirven a la vez como fuente de precios para
  `EwpgRepoFacility.updatePrice`. Pruebas: `contracts/test/examples/CompliantSecondaryMarket.t.sol`.
- `contracts/src/examples/StablecoinAmm.sol` — un AMM mínimo de producto constante restringido a
  pares exclusivamente de stablecoins, deliberadamente **no** `RegisterwerkGated` (véase su
  NatSpec para saber por qué). Pruebas: `contracts/test/examples/StablecoinAmm.t.sol`.
