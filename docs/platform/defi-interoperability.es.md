# Interoperabilidad DeFi { #defi-interoperability }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Esta página es una discusión de diseño técnico. Registerwerk y los acuerdos descritos de DeFi, negociación,
    custodia, nominado, pago y préstamo no se presentan como legalmente permitidos, conformes
    o autorizados, y no están listos para producción. La clasificación y el efecto legal exigen una revisión
    específica de operador, instrumento, servicio, contraparte, transacción y jurisdicción.

Registerwerk está pensado para su uso en mercados de capitales regulados. Este documento explica dónde y por qué
tienen sentido los puentes hacia el ecosistema DeFi/Ethereum y — con igual importancia — dónde no lo tienen,
dada la realidad regulatoria de los valores tokenizados frente a la de los criptoactivos.

## El punto de partida regulatorio { #the-regulatory-starting-point }

La clasificación MiCAR/MiFID II no puede inferirse de un estándar de token, una etiqueta `eWpG` o un
indicador de vía de pago. El módulo `payment` almacena campos de divulgación y atestación introducidos
por el operador; no establece por sí solo que un activo sea un instrumento financiero, que una moneda
estable sea un EMT, o que un emisor o prestador de servicios esté autorizado.

Si un AMM sin permiso, un pool de préstamos, un custodio, un nominado o una estructura ómnibus pueden
mantener o transferir un instrumento es una cuestión legal, regulatoria, de custodia, de insolvencia y
de diseño de producto — no algo que respondan los contratos inteligentes o la configuración de
jurisdicción. Los modelos siguientes son opciones técnicas para la revisión de asesoría legal y de
quienes son responsables de los controles.

## Matriz de interoperabilidad por jurisdicción { #jurisdiction-interoperability-matrix }

Modelada en `backend/.../kyc/api/JurisdictionRequirementConfig.ComplianceMetadata` mediante los nuevos
campos `defiInteropModel` / `permissionlessAmmAllowed` (enumeración `DefiInteropModel`, mismo paquete):

| Jurisdicción | Regulador | `defiInteropModel` | `permissionlessAmmAllowed` | Base |
|---|---|---|---|---|
| `DE_EWPG` | BaFin | `NOMINEE_POOL` | `false` | eWpG Sammelverwahrung (custodia colectiva) |
| `LU_CSSF` | CSSF | `NOMINEE_POOL` | `false` | Régimen ómnibus de custodio/depositario supervisado por la CSSF |
| `FR_AMF` | AMF | `NOMINEE_POOL` | `false` | Régimen CMF de teneur de compte-conservation (teneduría de cuenta/custodia) |
| `LI_TVTG` | FMA | `NOMINEE_POOL` | `false` | Modelo de contenedor de tokens de la TVTG + proveedor de servicios VT autorizado |

Las cuatro jurisdicciones recaen hoy en el mismo modelo porque las cuatro ya reconocen una estructura
ómnibus de intermediario autorizado — no hubo divergencia específica por jurisdicción que modelar todavía.
`permissionlessAmmAllowed` es `false` en todas partes, y deliberadamente explícito (no solo la ausencia
de un `true`), de modo que una futura incorporación de jurisdicción tenga que tomar una decisión activa
en lugar de heredar en silencio un valor por defecto. El alcance de este trabajo se limitó a propósito a
las cuatro jurisdicciones ya incorporadas (`DE_EWPG`, `LU_CSSF`, `FR_AMF`, `LI_TVTG`); añadir regímenes
fuera de la UE (por ejemplo, la Ley DLT de Suiza, que tiene su propia licencia de plataforma de
negociación DLT que cubre negociación/liquidación/custodia combinadas) es una incorporación natural en
cuanto Registerwerk opere efectivamente allí.

Existe un tercer modelo, `ORACLE_ONLY`, para el patrón (ya desplegable, con riesgo de custodia cero) en el
que un protocolo externo solo *lee* atestaciones de `PermissionOracle` — véase
[dapp-development.md § Componibilidad DeFi externa](./dapp-development.md#external-defi-composability).
Hoy ninguna jurisdicción está restringida a `ORACLE_ONLY`, ya que `NOMINEE_POOL` es un superconjunto
estricto de lo que aquel permite.

## El puente nominado/ómnibus (`NOMINEE_POOL`) { #the-nomineeomnibus-bridge-nominee_pool }

Un nuevo tema de atestación ONCHAINID, `NOMINEE` (tema 4, junto a los ya existentes 1=KYC/2=AML/
3=Acreditación), lo emite un emisor de confianza al propio ONCHAINID de un custodio/CASP autorizado —
usando exactamente la misma maquinaria de `ClaimIssuanceService`/`EcosystemTrustedIssuersRegistry` ya
existente para las atestaciones de KYC/AML. La dirección del contrato de pool de ese custodio se marca
entonces como pool nominado en el `EwpgComplianceModule` del token concreto
(`contracts/src/compliance/EwpgModularCompliance.sol`, `setNomineePool`), lo que:

- **Exime** la dirección del pool de `maxBalancePerInvestor` y `maxInvestors` — precisamente porque un
  pool neta la exposición económica de muchos LP detrás de una sola dirección, y un límite por inversor
  aplicado a esa única dirección frustraría el propósito regulatorio del límite (bloquearía todo el
  pooling sin más, o dejaría eludir silenciosamente el límite compensando a muchos inversores detrás de
  un solo "inversor").
- **Mantiene** sin condiciones los controles de bloqueo por país y de período de espera de transferencia
  — el propio operador del pool sigue sin poder estar domiciliado en una jurisdicción bloqueada.
- **Solo se aplica a direcciones de contrato** (`to.code.length > 0`) — marcar un EOA como pool nominado
  no tiene efecto alguno, ya que la exención solo tiene sentido para un contrato de custodia agrupada
  genuino, no para el monedero de un particular.

La responsabilidad del KYC/AML "look-through" de los propios LP subyacentes del pool recae por completo
en el operador nominado/custodio, fuera de cadena — exactamente igual que hoy en día en la cuenta ómnibus
de un banco custodio tradicional. Marcar el manifiesto de una dApp con `requiredClaimTopics: [4]` se
muestra al operador que revisa durante la aprobación del mercado (`ManifestValidationService`) como un
indicador que exige revisión humana de la licencia de custodio del publicador, no como una declaración
aprobada automáticamente.

## Qué atrae realmente a los proveedores de liquidez y a los creadores de mercado { #what-actually-attracts-liquidity-providers-and-market-makers }

Antes de decidir qué construir, conviene ser honestos sobre qué es lo que optimiza un operador o un
creador de mercado, y cómo eso encaja — o no — con un registro de valores conforme:

- **Los mercados de bonos de TradFi no son líquidos principalmente por la negociación secundaria.** Son
  líquidos por el **repo** (un pacto de recompra: vender el bono ahora, comprometerse a recomprarlo más
  tarde a un precio fijo) y por el **préstamo de valores**. Un intermediario que mantiene una posición
  ilíquida no la vende para obtener efectivo — la da en repo a un día o a plazo, conserva la exposición
  económica y redespliega el efectivo. Los mercados de repo mueven billones diariamente, muy por encima
  del volumen de negociación secundaria en efectivo, precisamente porque permiten a un tenedor acceder a
  liquidez *sin* una venta directa (sin precio realizado, sin pérdida de revalorización, sin descuento
  por venta forzosa).
- **Los mercados monetarios DeFi (Aave, Compound) son el mismo mecanismo, agrupado y algorítmico**:
  depositar una garantía, pedir prestado contra ella, a un tipo fijado por la utilización en tiempo real
  en lugar de una negociación bilateral. Lo que realmente atrae a los LP a un mercado monetario es un
  tipo transparente e impulsado por la utilización, una entrada sin permiso por el lado de la oferta, y
  un mecanismo de ejecución de la garantía creíble que mantiene indemnes a los depositantes.
- **Los AMM al estilo Uniswap atraen a los LP mediante ingresos por comisiones y la creación de pools sin
  permiso** — pero ese modelo presupone que el activo negociado es fungible, tiene precio continuo y es
  seguro dejar que un contrato anónimo lo mantenga en neto por cuenta de muchas partes. Nada de eso es
  cierto para un valor con precio de NAV y elegibilidad restringida, que es exactamente la razón por la
  que este repositorio descarta un AMM/libro de órdenes para la pata del token de valor (véase la sección
  sobre el mecanismo de negociación más abajo).
- **Las plataformas de activos del mundo real ya aprendieron esta lección.** Ondo, Centrifuge, Maple y el
  BUIDL de BlackRock obtienen la mayor parte de su utilidad DeFi al presentarse como **garantía** en un
  mercado de préstamos, no de la liquidez de negociación al contado — el RWA tokenizado permanece en una
  posición custodiada/agrupada y es la liquidez en stablecoin la que se mueve a su alrededor.
- **Lo que un creador de mercado quiere específicamente**: una forma de ponerse largo y corto, de cubrir
  riesgo, de reutilizar el mismo capital en varias posiciones (eficiencia de capital) y certeza de
  ejecución. El préstamo con garantía le da al tenedor exactamente eso — apalancamiento y eficiencia de
  capital — sin que Registerwerk tenga nunca que operar un motor de casamiento de órdenes.

La conclusión: **una facilidad de referencia de préstamo con garantía es una posible funcionalidad de
liquidez para Registerwerk, no un producto legalmente aprobado.** Además encaja perfectamente con la
restricción de "no construir un DEX", ya que el préstamo con garantía nunca fue, desde el principio, un
libro de órdenes.

## `EwpgRepoFacility` — el mecanismo principal de liquidez de salida { #ewpgrepofacility-the-primary-exit-liquidity-mechanism }

`contracts/src/examples/EwpgRepoFacility.sol` es una facilidad de referencia de repo/préstamo con
garantía con un control de acceso deliberadamente asimétrico. El uso en producción está bloqueado en
espera de la calificación legal, custodia/control, ejecución de la garantía, oráculo, insolvencia y
aprobación del contrato inteligente:

- **El lado del prestamista (`deposit`/`withdraw`) está abierto a cualquier tenedor de stablecoin** — sin
  ninguna comprobación `RegisterwerkGated`. Los depositantes solo llegan a tener un derecho sobre la
  stablecoin agrupada; nunca tocan el token de valor restringido, así que no hay ninguna razón de
  normativa de valores para restringirlos. Esta es la palanca individual más importante para "la
  capacidad de Registerwerk de atraer liquidez al mercado": cuantas menos barreras haya para *aportar*
  capital, más profundo será el pool, ya que el riesgo que se está fijando recae por completo en el lado
  del prestatario (restringido).
- **El lado del prestatario (`pledgeAndBorrow`) está restringido** — permiso `repo-facility.borrow` más
  una atestación KYC válida — ya que solo un inversor verificado puede pignorar el activo de garantía
  restringido. Un prestatario pignora, por ejemplo, una posición de bono `EwpgERC3643` y retira stablecoin
  hasta una relación préstamo-valor configurada, manteniendo intacta la posición del bono y sus derechos
  de cupón/amortización. Esta es la operación de "repo": liquidez sin venta.
- **`repay` y `liquidate` se dejan deliberadamente sin restringir.** La devolución de la garantía a quien
  llama está a su vez sujeta a la propia comprobación del registro de identidad T-REX del token — la
  transacción de un llamador no verificado simplemente revierte en la capa del token. Esto significa que
  la ejecución de la garantía puede ser técnicamente sin permiso para los destinatarios elegibles, pero
  eso no establece cumplimiento legal ni regulatorio. El muro `isVerified()` existente solo aporta una
  restricción a nivel de contrato. El repago se deja abierto por principio — reducir el riesgo y recuperar
  la propia garantía previamente pignorada nunca debería quedar bloqueado por un cambio administrativo de
  permisos.
- **El interés se basa en la utilización** (`liquidityIndex`/`borrowIndex` al estilo Aave, escalados en
  WAD), de modo que ambos lados liquidan en O(1) con independencia del número de participantes, y los
  depositantes ven un rendimiento transparente y determinado por el mercado en lugar de un tipo fijo.
- Igual que `CompliantSecondaryMarket`, la propia dirección de la facilidad agrupa la garantía de muchos
  prestatarios detrás de una sola dirección, así que cualquier activo de garantía `EwpgERC3643` necesita
  el mismo indicador `EwpgComplianceModule.setNomineePool(token, address(facility), true)` antes de que
  puedan efectuarse pignoraciones que superen el límite individual del primer inversor.

### Mecanismo de negociación, según el tipo de par { #trading-mechanism-split-by-pair-type }

- **Patas de token de valor: casamiento RFQ/bilateral sobre `DvpSettlement`**
  (`contracts/src/examples/CompliantSecondaryMarket.sol`). Sin curva de bonding compartida — las
  cotizaciones se casan fuera de cadena (o mediante una simple función de publicación de cotizaciones en
  cadena) y se liquidan en una única transacción exitosa mediante las primitivas ya existentes
  `lockAsset`/`lockPayment`/`settle`. El comportamiento de pata exacta presupone tokens sin comisiones de
  transferencia ni rebases; la finalidad y el asiento en el registro legal son cuestiones aparte. Esto
  evita la exposición a pérdida impermanente y a manipulación de oráculo en un bono con precio de NAV y
  potencialmente ilíquido — la misma razón por la que los centros de negociación regulados reales (SDX,
  los MTF del régimen piloto DLT de la UE) usan fijación de precios por libro de órdenes/RFQ en lugar de
  curvas de producto constante para valores. **Su función se entiende hoy mejor como descubrimiento de
  precios que alimenta `EwpgRepoFacility.updatePrice`** (la última ejecución es una marca de garantía
  legítima) que como el centro de liquidez principal — exactamente igual que la negociación secundaria de
  bonos en TradFi sirve sobre todo al descubrimiento de precios mientras el repo hace el trabajo pesado de
  liquidez. Varios operadores nominados en competencia pueden desplegar cada uno su propia instancia y
  quedar marcados en el mismo token, de modo que esto es una competencia al estilo dealer-to-client entre
  creadores de mercado, no una mesa monopolística única.
- **Patas exclusivamente en stablecoin: un AMM simple de producto constante**
  (`contracts/src/examples/StablecoinAmm.sol`). Reservado para pares en los que ninguna de las patas es un
  valor (por ejemplo, AUEUR/USDC, ambas declaradas mediante el catálogo de vías
  `PaymentRailType.STABLECOIN` del módulo `payment`) — el único caso en el que un AMM nativo de DeFi
  habitual es realmente la opción de menor riesgo, ya que no existe ninguna preocupación de integridad de
  precio propia de los valores.

Las tres dApps de referencia heredan `RegisterwerkGated` de la misma manera que `BoardroomGovernance`/
`EwpgBondDesk`. `EwpgRepoFacility` incluye además un manifiesto completo, un README y sembrado de
demostración como los otros dos ejemplos insignia — véase
[dapp-development.md § dApps de ejemplo de referencia](./dapp-development.md#reference-example-dapps).
`CompliantSecondaryMarket` y `StablecoinAmm` siguen siendo solo Solidity con pruebas (sin manifiesto, no
sembrados como listados de mercado).

## `EwpgRepoMarket` / `EwpgRepoVault` — la evolución hacia mercados aislados { #ewpgrepomarket-ewpgrepovault-the-isolated-market-evolution }

`contracts/src/lending/` es la evolución al estilo Morpho Blue de `EwpgRepoFacility`, aditiva respecto a
ella (ambas pueden ejecutarse contra el mismo ecosistema — véase `script/DeployRepoMarkets.s.sol`).
Mientras que la facilidad agrupa cada tipo de garantía detrás de un único pool de efectivo compartido y un
único par de índices compartido, cada `EwpgRepoMarket` aísla el riesgo a exactamente un par
`{loanToken, collateralToken}`, desplegado mediante `EwpgRepoMarketFactory` (CREATE2, restringido al
operador). `EwpgRepoVault` es la capa curadora al estilo MetaMorpho por encima, que dirige los depósitos
de los prestamistas a través de varios mercados con límites por mercado.
`RegisterwerkNavOracle`/`IRepoOracle` formalizan el patrón de envío de NAV de la facilidad como una
interfaz independiente e intercambiable. El mismo control de acceso asimétrico que en la facilidad (lado
del prestamista abierto, lado del prestatario restringido por KYC+permiso, repago/ejecución de la
garantía sin restringir en esta capa — el propio muro T-REX del token es la restricción real); véase el
NatSpec de cada contrato para conocer los detalles mecánicos.

Esta evolución resuelve dos de las tres simplificaciones señaladas más abajo para la facilidad: un factor
de reserva (limitado al 25 %, configurable por el operador) y una ejecución parcial de la garantía (factor
de cierre del 50 %, al estilo Aave) existen ya ambos en `EwpgRepoMarket` — la facilidad en sí no ha
cambiado y sigue siendo una implementación de referencia más simple. El tercer punto — la revisión legal
de préstamo con margen específica de cada jurisdicción — se aplica por igual a ambas y **sigue abierto**;
véase la revisión más abajo.

## Revisión de cumplimiento (21-07-2026) — hallazgos y refuerzos { #compliance-review-2026-07-21-findings-and-hardening }

Una revisión completa de cumplimiento sobre `EwpgRepoFacility`, la pila `EwpgRepoMarket`/`Vault`/oráculo,
y el modelo de lectura `lending` del backend detectó los puntos siguientes. Detalle completo, mapeo por
jurisdicción y clasificación de gravedad: `docs/compliance/lending-facility-review.md`. Resumen de lo que
se incorporó en este trabajo frente a lo que sigue abierto:

**Reforzado (en este trabajo):**

- **Disyuntor de desviación de precio del oráculo** — `RegisterwerkNavOracle.pushPrice` ahora rechaza un
  envío que se desvíe más de un `maxDeviationBps` configurable por el operador (20 % por defecto) respecto
  de la última marca; existe un `pushPriceWithOverride` con permiso independiente para repreciaciones
  grandes legítimas. Acota el radio de impacto de una única clave de alimentación de NAV comprometida o
  con un error de tecleo.
- **Antigüedad de oráculo obligatoria para los mercados desplegados** — el propio `EwpgRepoMarket` todavía
  permite `maxPriceAgeSeconds == 0` (comprobación de antigüedad deshabilitada) para pruebas unitarias de
  construcción directa, pero `EwpgRepoMarketFactory.createMarket` ahora rechaza `0` — todo mercado
  desplegado por el operador tiene un límite de frescura real.
- **Protección contra reentrancia en `EwpgRepoVault`** — la vault era el único contrato de la pila de
  préstamos sin `ReentrancyGuard` en sus puntos de entrada que mueven valor (`deposit`/`mint`/`withdraw`/
  `redeem`/`allocate`/`deallocate`); ahora está protegida como cualquier otro contrato Ewpg* de préstamo.
- **Conciliación del libro de garantías** (eWpG §24 Berichtigung) — un nuevo
  `EwpgRepoMarket.reconcileCollateral(borrower, attributableCollateral)`, restringido por CONFIGURE,
  permite al operador corregir **a la baja** (nunca al alza) la garantía registrada de una posición
  después de que un `forcedTransfer`/`forceBurn` de un agente saque garantía del pool al margen de
  `repay`/`liquidate`, cerrando una brecha por la que el libro interno podía desincronizarse del saldo
  real del token.
- **Brecha de autorización en `LendingPositionController` (backend)** — `/api/v1/lending/my-positions` y
  `/supply-positions` no llevaban `@PreAuthorize`; ahora exigen autenticación.
- **`borrowPaused` en cadena ahora llega al backend/frontend** — `LendingMarketService` lee el indicador en
  vivo (con el mejor esfuerzo posible; si falla la lectura en cadena, recae en el estado persistido en
  lugar de hacer fallar el listado) y lo refleja como `PAUSED`, cerrando la brecha por la que el estado
  existía en el modelo pero nunca se mostraba. El asistente de préstamo ahora muestra un estado explícito
  de "mercado en pausa" en lugar de dejar que un intento de préstamo revierta en cadena.

**Todavía abierto (véase el documento de revisión para el detalle completo):**

- **Revisión legal de préstamo con margen específica de cada jurisdicción** — sin cambios respecto a la
  facilidad (véase más arriba): la segregación de custodia, las licencias de préstamo con margen y las
  restricciones de rehipotecación son una cuestión regulatoria independiente del contrato, y siguen sin
  revisarse para `DE_EWPG`/`LU_CSSF`/`FR_AMF`/`LI_TVTG`.
- **La ejecución de la garantía no es realmente sin permiso cuando el conjunto de ejecutores verificados
  es reducido** — la garantía embargada se entrega al ejecutor, así que el muro T-REX también restringe
  al ejecutor; sin un ejecutor elegible, una posición insana no puede cerrarse. Todavía no existe una vía
  de ejecución alternativa (por ejemplo, un agente de último recurso).
- **El estado de pool nominado en cadena solo se afirma fuera de cadena** — nada en cadena verifica que un
  mercado se haya marcado realmente como pool nominado antes de aceptar pignoraciones que superen el
  límite por inversor; hoy, la primera pignoración que exceda el límite simplemente revierte en la capa
  del token.
- **La congelación del monedero del prestatario no alcanza a la garantía ya pignorada** — una vez que la
  garantía está en el pool, una congelación posterior sobre el propio monedero del prestatario ya no la
  restringe, ya que a partir de ese momento el contrato del pool es el titular registrado del token.

## Préstamo con valores como garantía: qué está realmente implementado frente a lo que aún necesita visto bueno legal { #lending-against-securities-as-collateral-whats-actually-implemented-vs-what-still-needs-legal-sign-off }

`EwpgRepoFacility` está implementada y probada (`contracts/test/examples/EwpgRepoFacility.t.sol`) como
**implementación de referencia** — la mecánica de préstamo con garantía, el control de acceso y la lógica
de ejecución de la garantía son reales y correctas, pero lo siguiente sigue siendo una simplificación
deliberada o una cuestión abierta antes de una implementación en producción:

- **Sin factor de reserva de protocolo** — hoy el 100 % del interés del prestatario fluye hacia los
  depositantes, mantenido así precisamente para lograr una contabilidad auditable en una implementación de
  referencia. Un recorte de reserva es un cambio aislado y aditivo. (`EwpgRepoMarket` ya añade uno — véase
  más arriba.)
- **Solo ejecución con factor de cierre total** — una posición insana se liquida en una única llamada por
  la totalidad de la deuda pendiente, no de forma parcial. Los mercados monetarios reales suelen admitir
  la ejecución parcial para reducir los requisitos de capital de quien ejecuta la garantía.
  (`EwpgRepoMarket` ya añade ejecución parcial con factor de cierre — véase más arriba.)
- **Los valores como garantía siguen activando su propia capa regulatoria, independiente del diseño del
  contrato inteligente**: reglas de segregación de custodia, licencias de préstamo con margen y (según la
  jurisdicción) restricciones de rehipotecación que no se aplican a un simple préstamo garantizado en
  efectivo. Obtenga una revisión legal específica de cada jurisdicción sobre las reglas de préstamo con
  margen conforme a `DE_EWPG`/`LU_CSSF`/`FR_AMF`/`LI_TVTG` antes de operar esta facilidad contra valores
  reales en producción — es uno de los rincones más regulados del MiFID II / del derecho nacional de
  valores, y que el contrato sea correcto no sustituye esa revisión. **Sigue sin revisar tras la revisión
  de cumplimiento del 21-07-2026 mencionada arriba** — esto es trabajo de asesoría legal, y ningún otro
  cambio de contrato puede sustituirlo.
