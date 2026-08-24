---
title: Revisión del cumplimiento de la línea de repo/préstamo
description: Hallazgos de cumplimiento priorizados para EwpgRepoFacility y EwpgRepoMarket/Vault/oracle stack, con mapeo por jurisdicción y estado de refuerzo.
---

# Revisión del cumplimiento de la línea de repo/préstamo { #repolending-facility-compliance-review }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Esta página registra los hallazgos técnicos y las asignaciones de control previstas. No es evidencia de cumplimiento legal,
    autorización regulatoria, aprobación de producto o preparación para la producción.
    Las cuestiones de repos, préstamo de valores, garantías, custodia, ejecución de la garantía, insolvencia y reutilización
    requieren una revisión específica del operador, del producto, del instrumento, de la transacción y de la jurisdicción
    por parte de asesores calificados y de los responsables de riesgo correspondientes.

Fecha de revisión: 2026-07-21. Alcance: `contracts/src/examples/EwpgRepoFacility.sol`, la evolución de mercado aislado
bajo `contracts/src/lending/` (`EwpgRepoMarket`, `EwpgRepoMarketFactory`,
`EwpgRepoVault`, `oracle/RegisterwerkNavOracle`), y el módulo de modelo de lectura (`lending`) del backend.
Documento complementario a [DeFi Interoperability](../platform/defi-interoperability.md), que cubre la justificación de producto/regulatoria
del diseño de la línea; este documento es el análisis de brechas de seguridad/cumplimiento.

Los hallazgos se clasifican como **P0** (debe corregirse u obtener aprobación legal antes de su uso en producción contra valores reales),
**P1** (debe corregirse; reducción de riesgo significativa, no bloqueante para el lanzamiento de una implementación de referencia),
y **P2** (documentado para conocimiento; límite MVP aceptable o requiere un
cambio de diseño más amplio que el alcance de este pase).

---

## Resumen: enviado vs. aún abierto { #summary-shipped-vs-still-open }

| # | Hallazgo | Gravedad | Estado |
|---|---|---|---|
| 1 | Falta el disyuntor de desviación de precios del oráculo | P0 | **Corregido** — ver más abajo |
| 2 | La desactivación de la comprobación de antigüedad del oráculo es opcional, no aplicada para los mercados desplegados | P0 | **Corregido** — ver más abajo |
| 3 | Revisión legal de préstamos con margen para jurisdicciones específicas no realizada | P0 | **Abierto — trabajo de asesoría** |
| 4 | A `EwpgRepoVault` le falta `ReentrancyGuard` | P1 | **Corregido** — ver más abajo |
| 5 | El libro de garantías puede desincronizarse del saldo del token tras una transferencia forzosa | P1 | **Corregido** — ver más abajo |
| 6 | El `LendingPositionController` del backend no tenía `@PreAuthorize` | P1 | **Corregido** — ver más abajo |
| 7 | El `borrowPaused` on-chain nunca llegó al backend/frontend | P1 | **Corregido** — ver más abajo |
| 8 | La ejecución de la garantía no es verdaderamente sin permiso cuando el conjunto de liquidadores verificados es reducido | P1 | **Abierto** |
| 9 | El indicador de grupo de nominados se afirma solo off-chain, sin verificación on-chain | P2 | **Abierto** |
| 10 | El bloqueo de la billetera del prestatario no alcanza la garantía ya pignorada | P2 | **Abierto** |
| 11 | Discrepancias entre las cadenas de permisos y NatSpec (`repo-oracle.*`, `repo-vault.*` frente a las constantes reales `repo-markets.*`) | P2 | **Corregido** — solo comentarios |
| 12 | `EwpgRepoVault.totalAssets()` itera la lista completa de mercados sin límite | P2 | **Abierto** |

---

## P0: debe corregirse o obtener aprobación antes de la producción { #p0-must-fix-or-get-sign-off-before-production }

### 1. Disyuntor de desviación de precio del oráculo (corregido) { #1-oracle-price-deviation-circuit-breaker-fixed }

**Antes:** `RegisterwerkNavOracle.pushPrice` (y `EwpgRepoFacility.updatePrice`) aceptaba cualquier
precio distinto de cero sin límite alguno respecto de la marca anterior. Una sola clave
`PUSH_PRICE` comprometida o víctima de un error de tecleo podría marcar la garantía como arbitrariamente alta —
lo que permite un endeudamiento excesivo que vacía el fondo — o arbitrariamente baja, desencadenando
ejecuciones de garantía masivas e innecesarias.

**Solución:** `RegisterwerkNavOracle.pushPrice` ahora revierte con `ExcessiveDeviation` si el nuevo precio
se desvía más de `maxDeviationBps` (predeterminado 2000 = 20 %, ajustable por el operador mediante
`setMaxDeviationBps`) respecto de la marca anterior. El primer envío de precio para un activo no tiene límite
(no hay marca previa con la que comparar). Existe un `pushPriceWithOverride` con permiso independiente (protegido por
`OVERRIDE_PRICE`, distinto del `PUSH_PRICE` ordinario) para una recotización grande y legítima, de modo
que una clave ordinaria de automatización del feed de NAV no puede eludir el disyuntor por sí sola.

**No corregido en este pase:** `EwpgRepoFacility.updatePrice` (la línea agrupada más antigua) sigue sin tener
límite de desviación — la línea se trata como una implementación de referencia congelada, y la corrección se
aplicó en la pila `EwpgRepoMarket`/oráculo más reciente que la sustituye. Si la línea permanece en uso de
producción, traslade allí el mismo disyuntor.

Pruebas: `contracts/test/lending/RegisterwerkNavOracle.t.sol` (7 pruebas).

### 2. La desactivación de la comprobación de antigüedad del oráculo es opcional, no aplicada para los mercados desplegados (solucionado) { #2-oracle-staleness-opt-in-not-enforced-for-deployed-markets-fixed }

**Antes:** `EwpgRepoMarket._currentPrice()` ya rechazaba una marca obsoleta cuando
`maxPriceAgeSeconds != 0`, pero `0` (comprobación de antigüedad desactivada) era un argumento de constructor válido, sin ninguna
protección que impidiera a un operador desplegar de ese modo un mercado real — accidentalmente, o mediante una clave de operador
comprometida que decidiera deliberadamente desactivar la única salvaguarda frente a un feed de precios congelado o retenido.

**Solución:** `EwpgRepoMarket` en sí no cambia (la construcción directa con `maxPriceAgeSeconds == 0`
sigue funcionando, intencionadamente, para las pruebas unitarias). `EwpgRepoMarketFactory.createMarket` —
la única vía que despliega un mercado real aprobado por el operador — ahora revierte con `InvalidMaxPriceAge`
si `maxPriceAgeSeconds == 0`.

Pruebas: `contracts/test/lending/EwpgRepoMarketFactory.t.sol::test_createMarket_revertsWithZeroMaxPriceAge`.

### 3. Revisión legal de préstamos con margen específico de cada jurisdicción (abierto: trabajo de abogado) { #3-jurisdiction-specific-margin-lending-legal-review-open-counsel-work }

**Hallazgo, sin cambios respecto del aviso preexistente en `defi-interoperability.md`:** pignorar un valor
como garantía de un préstamo desencadena una capa regulatoria independiente de la corrección del contrato
inteligente — reglas de segregación de custodia, licencias de préstamo con margen y (según la jurisdicción)
restricciones de rehipotecación que un simple préstamo garantizado en efectivo nunca activa.

| Jurisdicción | Regulador | Régimen pertinente | Estado |
|---|---|---|---|
| `DE_EWPG` | BaFin | Reglas de préstamo con margen de la KWG / Wertpapierleihe, custodia conforme al eWpG | Sin revisar |
| `LU_CSSF` | CSSF | Reglas de la CSSF sobre custodio/depositario en materia de rehipotecación | Sin revisar |
| `FR_AMF` | AMF | Restricciones del CMF al teneur de compte-conservation | Sin revisar |
| `LI_TVTG` | FMA | Segregación de custodia del modelo de token-contenedor conforme a la TVTG | Sin revisar |

**Ningún grado adicional de endurecimiento del contrato sustituye esto.** Este hallazgo se mantiene
sin cambios: está explícitamente fuera del alcance de una revisión de cumplimiento centrada solo en el código, y
requiere asesoría externa específica de la jurisdicción antes de operar `EwpgRepoFacility` o
`EwpgRepoMarket` contra valores reales en producción.

---

## P1 — debería arreglar { #p1-should-fix }

### 4. A `EwpgRepoVault` le falta `ReentrancyGuard` (solucionado) { #4-ewpgrepovault-missing-reentrancyguard-fixed }

**Antes:** `EwpgRepoVault` era el único contrato de la pila de préstamos (tanto `EwpgRepoFacility` como
`EwpgRepoMarket` protegen todas las funciones que mutan estado) sin `ReentrancyGuard` — sus funciones
heredadas de ERC-4626 `deposit`/`mint`/`withdraw`/`redeem`, y sus propias `allocate`/`deallocate`,
realizan todas llamadas externas a tokens sin ninguna protección de reentrada a nivel de la bóveda.

**Solución:** `EwpgRepoVault` ahora hereda `ReentrancyGuard`. `deposit`/`mint`/`withdraw`/`redeem` se
sobrescriben únicamente para añadir `nonReentrant` alrededor de la implementación de OZ (sin cambio de
lógica); `allocate`/`deallocate` recibieron el modificador directamente.

Pruebas: la suite `contracts/test/lending/EwpgRepoVault.t.sol` existente continúa pasando sin cambios
(la protección es aditiva; no hay cambios de comportamiento para legítimos llamantes).

### 5. El libro mayor de garantías puede desincronizarse del saldo del token después de una transferencia forzada (fija) { #5-collateral-ledger-can-desync-from-token-balance-after-a-forced-transfer-fixed }

**Antes:** Un `forcedTransfer` o `forceBurn` ejecutado por el emisor/agente sobre el token de garantía
(una Berichtigung conforme al §24 eWpG, o una acción de bloqueo dictada por orden judicial/AWG-GwG,
a nivel de la capa del token) puede sacar tokens del saldo de `EwpgRepoMarket` sin pasar por
`repay`/`liquidate`: la contabilidad interna del mercado, `positions[borrower].collateralAmount`,
no tiene forma de detectarlo. Si no se concilia, la garantía registrada supera lo que el mercado
puede entregar realmente, de modo que un `repay`/`liquidate` posterior o bien revierte o, peor aún,
paga de más con los fondos de otros participantes.

**Solución:** Una nueva función `EwpgRepoMarket.reconcileCollateral(borrower, attributableCollateral)`,
protegida por `CONFIGURE`, permite al operador corregir **a la baja** la garantía registrada de una
posición hasta lo que realmente retiró una transacción de transferencia forzosa concreta. La función
recibe el importe corregido como parámetro explícito — en lugar de intentar inferirlo del
`balanceOf(this)` agregado del token — porque ese saldo suma las posiciones de todos los prestatarios
del mercado, de modo que solo una conciliación off-chain de la transacción de transferencia forzosa
concreta (el mismo acto del operador que la ordenó) puede atribuir correctamente la reducción a un
prestatario determinado. La invariante aplicada on-chain es unidireccional: la llamada revierte
(`ReconciliationWouldIncreaseCollateral`) si el nuevo importe no es estrictamente inferior al importe
registrado actual, de modo que nunca puede fabricarse una garantía que nunca se pignoró.

Pruebas: `contracts/test/lending/EwpgRepoMarket.t.sol` (`test_reconcileCollateral_*`, 4 pruebas).

### 6. El backend `LendingPositionController` no tenía `@PreAuthorize` (reparado) { #6-backend-lendingpositioncontroller-had-no-preauthorize-fixed }

**Antes:** `GET /api/v1/lending/my-positions` y `/supply-positions` no llevaban ningún
`@PreAuthorize` de nivel de método ni de clase, a diferencia de cualquier otro controlador de cara al
cliente en este módulo. El alcance era puramente implícito: una llamada no autenticada resolvía un
`appUserId` nulo y devolvía silenciosamente una lista vacía en lugar de ser rechazada de plano.

**Solución:** se añadió `@PreAuthorize("isAuthenticated()")` a nivel de clase, siguiendo el patrón
utilizado por `PositionStatementController`/`SteuerbescheinigungController` para otros endpoints de
lectura de cara al cliente.

### 7. El `borrowPaused` on-chain nunca llegó al backend/frontend (corregido) { #7-on-chain-borrowpaused-never-reached-the-backendfrontend-fixed }

**Antes:** `EwpgRepoMarket.borrowPaused` (y `LendingMarketStatus.PAUSED` en el modelo de lectura del
backend) existía, pero nada establecía nunca el estado persistido en base de datos como `PAUSED`: el
indicador on-chain y el modelo de lectura estaban desconectados, de modo que un mercado en pausa
seguía mostrándose como `ACTIVE` en todas partes, y el intento de un trader de pedir prestado contra
él simplemente revertía on-chain sin ningún aviso previo.

**Solución:** `LendingMarketService.resolveEffectiveStatus` lee en vivo el indicador on-chain para
cualquier mercado cuyo estado persistido sea `ACTIVE`, reflejando `PAUSED` en cada respuesta de
lista/detalle sin mutar la fila de base de datos (una lectura en vivo, no un cambio de estado) — un
fallo de lectura on-chain recae en el estado persistido en lugar de hacer fallar todo el listado,
siguiendo el mismo patrón de mejor esfuerzo ya usado para las lecturas del factor de salud de
posiciones. El asistente de préstamo de cara al cliente ahora muestra un estado explícito de
"mercado temporalmente en pausa" en lugar de dejar que la transacción revierta.

Pruebas: `LendingMarketServiceTest` (`activeMarketReflectsOnchainBorrowPaused`,
`activeMarketStaysActiveWhenNotPaused`, `retiredMarketSkipsOnchainCheck`,
`onchainReadFailureFallsBackToPersistedStatus`).

### 8. La ejecución de la garantía no es verdaderamente sin permiso cuando el conjunto de liquidadores verificados es reducido (abierto) { #8-liquidation-not-truly-permissionless-when-the-verified-liquidator-set-is-thin-open }

**Hallazgo:** `liquidate` nominalmente no está sujeta a control de acceso en la capa `RegisterwerkGated`
(documentado como "sin permiso, como en Aave, porque el propio muro T-REX del token hace gratis el
trabajo de cumplimiento"). Eso es solo la mitad de la verdad: el muro controla al **destinatario** de
la garantía incautada (el liquidador), no solo al prestatario. Si el conjunto de direcciones
verificadas por T-REX, no congeladas y no bloqueadas por país dispuestas a ejecutar la garantía es
reducido, una posición insalubre puede no tener ningún liquidador elegible en absoluto: la posición
queda bajo el agua, perjudicando a los depositantes, sin ninguna vía alternativa para cerrarla. Un
liquidador verificado que esté cerca de `maxBalancePerInvestor` en el token de garantía también queda
bloqueado para recibir la garantía incautada, a menos que el propio liquidador esté marcado como
nominado.

**Recomendación:** diseñar una vía de ejecución de garantía por un agente de último recurso (por
ejemplo, una dirección controlada por el operador, previamente marcada como grupo de nominados,
autorizada para ejecutar la garantía y redistribuirla o depositarla en almacén de inmediato) para los
mercados en los que no pueda presuponerse que el conjunto de liquidadores verificados sea
suficientemente amplio. No implementado en este pase: es un nuevo diseño de control de acceso, no una
corrección acotada.

---

## P2: documentado, aceptable por ahora o requiere un trabajo de diseño más amplio { #p2-documented-acceptable-for-now-or-requires-larger-design-work }

### 9. El estado del grupo de nominados se afirma solo fuera de la cadena (abierto) { #9-nominee-pool-status-is-asserted-off-chain-only-open }

Todo el modelo de agrupación depende de una acción de un operador off-chain
(`EwpgModularCompliance.setNomineePool`) que marca el mercado como grupo de nominados en el token de
garantía, además de la comprobación off-chain de KYC/AML de los propios depositantes del grupo (véase
[Interoperabilidad DeFi § puente nominado/ómnibus](../platform/defi-interoperability.md#the-nomineeomnibus-bridge-nominee_pool)).
Nada en los contratos de préstamo verifica on-chain que este indicador se estableciera realmente antes
de aceptar pignoraciones: la primera pignoración que superaría el límite por inversor simplemente
revierte en la capa del token si falta el indicador, lo cual es una red de seguridad funcional pero no
ofrece ninguna señal proactiva.
**Recomendación:** un evento on-chain que correlacione el despliegue de un mercado con su indicador de
grupo de nominados (por ejemplo, que la factory lea y registre el indicador en el momento de
`createMarket`) mejoraría la auditabilidad sin cambiar el modelo de seguridad. Diferido como una mejora
de observabilidad deseable, no como una brecha en el propio modelo de cumplimiento.

### 10. El bloqueo de la billetera del prestatario no alcanza la garantía ya pignorada (abierto) { #10-borrower-wallet-freeze-doesnt-reach-already-pledged-collateral-open }

Una vez que la garantía queda pignorada en un mercado, el contrato del pool — no el prestatario — es
el titular registrado del token. Un Sperrvermerk posterior conforme al §16 eWpG, o un bloqueo
AWG/GwG, sobre la propia billetera del prestatario ya no controla esa garantía ya pignorada, dado que
la comprobación de bloqueo se ejecuta contra la dirección `from` de una transferencia, y el pool es el
`from` de cualquier movimiento posterior. Si esto satisface la intención regulatoria de un bloqueo de
billetera es en sí mismo una cuestión legal ligada al hallazgo #3 anterior, no una brecha del contrato
inteligente con una solución de código obvia (bloquear la *posición* en lugar de la billetera exigiría
un nuevo estado y un nuevo punto de control en cada contrato de préstamo). Documentado para que la
revisión legal del hallazgo #3 lo considere explícitamente.

### 11. Discrepancias entre las cadenas de permisos y NatSpec (corregido — solo comentarios) { #11-permission-string-natspec-mismatches-fixed-comment-only }

Dos discrepancias entre la constante realmente aplicada y la cadena documentada en el comentario
NatSpec (un problema de higiene de gobernanza/auditoría — la cadena incorrecta podría inducir a error a
quien otorga permisos leyendo la documentación en lugar del código):
- El comentario de `RegisterwerkNavOracle.pushPrice` decía `repo-oracle.push-price`; la constante real
  es `PUSH_PRICE = keccak256("repo-markets.push-price")`. Corregido.
- El comentario a nivel de contrato de `EwpgRepoVault` decía `repo-vault.curate`; la constante real es
  `CURATE = keccak256("repo-markets.curate-vault")`. Corregido.

Sin cambios de comportamiento: ambas constantes ya eran correctas y estaban bajo el espacio de nombres
del listado de mercado `repo-markets` según la regla de namespacing de `ManifestValidationService`;
solo el texto era incorrecto.

### 12. `EwpgRepoVault.totalAssets()` itera la lista completa de mercados (abierto) { #12-ewpgrepovaulttotalassets-iterates-the-full-market-list-open }

`totalAssets()` recorre todos los mercados agregados alguna vez (incluidos los deshabilitados) en cada
cálculo del precio de la acción — cada conversión `deposit`/`withdraw`/`mint`/`redeem` paga este coste.
Para el puñado de mercados que una bóveda de curador gestiona de forma realista esto es irrelevante,
pero un crecimiento ilimitado de mercados acabaría siendo un problema de coste de gas. Límite MVP
aceptable; un `totalAssets` acotado/paginado (o excluir del bucle los mercados deshabilitados) es un
refinamiento natural para una v2 si una bóveda llega a acercarse a docenas de mercados.

---

## Verificación { #verification }

- Contratos: `cd contracts && forge test --match-path "test/lending/*" -vv` — 45 pruebas, todas
  pasaron (0 regresiones respecto al conjunto de préstamos preexistente); suite completa
  `forge test` — 388 aprobadas, 0 fallidas, 18 omitidas.
- Backend: `cd backend && ./mvnw verify` — 436 pruebas unitarias + 30 de integración aprobadas, y se
  cumplen todas las puertas de cobertura de JaCoCo (incluidos los umbrales críticos de cumplimiento de
  `registerstatement`/adyacentes a lending).
