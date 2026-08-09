---
title: Libro de registro de revisión de aseguramiento de Registerwerk
description: El registro de control propuesto para una futura revisión multidisciplinaria de Registerwerk — no es evidencia de que se haya realizado ninguna revisión.
---

# Libro de registro de revisión de aseguramiento de Registerwerk { #registerwerk-assurance-review-ledger }

Última actualización: 2026-07-29

> **No se ha realizado ninguna revisión de las descritas en este documento.** No se ha convocado, designado ni
> consultado ningún panel de dominio ni ninguna junta de TI. Cada entrada a continuación fue escrita por un colaborador automatizado
> como una estructura de revisión *propuesta* y una autoevaluación del repositorio. Léalo
> como un plan para una revisión futura, nunca como evidencia de que esta haya tenido lugar. Un cambio de código completado
> no es una certificación legal. Los elementos que dependen de los términos del instrumento, de una licencia de operador, de evidencia
> externa, de la configuración de la implementación o de asesoría jurídica cualificada permanecen sin decidir.

Este documento propone el registro de control para una futura revisión multidisciplinaria de Registerwerk:
qué se revisaría, por quién y qué evidencia requeriría cada veredicto.

## Protocolo de decisión propuesto { #proposed-decision-protocol }

Los siguientes paneles se proponen, pero no cuentan con personal asignado. Los paneles de dominio cubrirían la emisión y liquidación de bonos, pagos, delitos financieros y cumplimiento normativo, criptoactivos y negociación, auditoría y repos/préstamos. Una junta de TI cubriría el diseño e implementación de software, arquitectura, SRE, frontend y criptografía.

Según la propuesta, la junta de TI puntuaría las propuestas de 0 a 2 en fidelidad al invariante legal, corrección del ledger, arquitectura, seguridad/privacidad, ciclo de vida de los datos, UX/accesibilidad, operabilidad y verificación. Una propuesta sería:

- aprobada con 14-16 puntos y sin ningún cero en las primeras cinco dimensiones;
- aprobada con cambios con 9-13 puntos;
- descartada con 0-8 puntos.

La junta podría vetar el acceso entre tenants, claves inseguras, dinero en coma flotante, liquidación no idempotente, migraciones irreversibles, control dual debilitado, entrada ilimitada, ausencia de gestión de finalidad/reorganización, conciliación no observable, o un invariante legal sin criterios de aceptación.

Los estados propuestos para este libro de registro son `PENDING`, `BLOCKED_DECISION`, `APPROVED`, `IN_PROGRESS`, `VERIFIED`, `DISMISSED` y `RESIDUAL_RISK`. Ninguno ha sido asignado por un revisor.

## Cobertura de la revisión { #review-coverage }

Cada celda es `Not performed`. La columna de alcance registra lo que *cubriría* una revisión. La autoevaluación
automatizada se rastrea por separado en `docs/claims/registry.json`, donde lleva el estado
`SELF_ASSESSED_UNREVIEWED`.

| Fase | Piezas | Revisión de dominio | Revisión de TI | Implementación |
|---|---|---|---|---|
| Inventario | Backend de Spring y 31 módulos de dominio; contratos EVM, Cairo y DAML; indexadores EVM/Solana/Canton/Starknet/Stellar; aplicaciones Angular de operador e inversor más UI compartida; retransmisor de tokens confidenciales; Kong, Compose, Helm y monitorización; documentación | No realizado | No realizado | Solo línea base |
| 0 — invariantes | Modelo de actor/capacidad, perímetro del instrumento, autoridad de registro, unidades de activos y dinero, finalidad, afirmaciones, puertas de liberación | No realizado | No realizado | Parcial, solo autoevaluado |
| 1 — autoridad y cumplimiento | Autenticación, autorización, organizaciones, KYC/AML, filtrado, Travel Rule, aprobaciones de jurisdicción, auditoría, privacidad, puerta de operación central | Pendiente | Pendiente | Pendiente |
| 2 — emisión y liquidación | Ciclo de vida/implementación de activos, estándares de token, contratos de identidad/cumplimiento, registro, pagos, DvP, custodia, indexadores, operaciones societarias | Pendiente | Pendiente | Pendiente |
| 3 — mercados e informes | Marketplace/negociación, repo/préstamos, oráculo y NAV, servicing de bonos, MiFIR, DAC8/KStTG, DORA y perfiles de jurisdicción | Pendiente | Pendiente | Pendiente |
| 4 — interfaces de usuario | UI de operador, UI de inversor, UI compartida, contratos de API, accesibilidad y presentación segura de transacciones | Pendiente | Pendiente | Pendiente |
| 5 — operaciones | CI, dependencias, contenedores, Kong, Helm, secretos, políticas de red, monitorización, copia de seguridad/restauración, SLO y runbooks | Pendiente | Pendiente | Pendiente |
| Cierre | Pruebas completas, evidencia de repetición/conciliación, conciliación de afirmaciones, notas de migración y aprobación de riesgo residual | Pendiente | Pendiente | Pendiente |

## Modelo canónico de la fase 0 { #phase-0-canonical-model }

### Autoridad y finalidad { #authority-and-finality }

Cada instrumento debe tener una decisión de perímetro versionada que designe el registro legal, el ledger técnico, la dirección de proyección, el encargado del registro, y la evidencia requerida para el efecto legal. La elección del estándar de token no debe clasificar el instrumento.

El sistema no debe comprimir estas dimensiones en un único indicador `SETTLED`:

`INITIATED → EXECUTED → TECHNICALLY_FINAL → CASH_CONFIRMED → REGISTER_POSTED → RECONCILED → LEGALLY_EFFECTIVE`

Para los instrumentos eWpG alemanes, el registro actual del titular en la base de datos es solo el registro legal afirmado, pendiente de una política de autoridad específica del instrumento y aprobada por asesoría jurídica. Una transacción en cadena, por sí sola, no debe describirse como un nuevo registro legal. Luxemburgo, Francia y Liechtenstein necesitan sus propias decisiones sobre el instrumento, en lugar de heredar el modelo alemán. La base de la distinción alemana es el texto oficial vigente de la [eWpG](https://www.gesetze-im-internet.de/ewpg/BJNR142310021.html); las decisiones relevantes de producto y de operador para Francia y Luxemburgo deben verificarse frente a la [guía del régimen piloto DLT de la AMF](https://www.amf-france.org/en/news-publications/depth/pilot-regime) y el [marco de valores desmaterializados de Luxemburgo](https://www.cssf.lu/en/Document/law-of-6-april-2013/).

### Convenciones de unidades { #unit-conventions }

| Valor | Convención canónica |
|---|---|
| Cantidad registrada | Unidades de valores con un `quantityScale` explícito; la conversión a unidades base de cadena requiere `tokenDecimals` declarado |
| Divisa | Unidades principales ISO-4217 en los modelos del backend, más exponente de divisa explícito y redondeo |
| Valor nominal del bono | Unidades principales de divisa por unidad entera de valor |
| Precio de emisión | Fracción adimensional del valor nominal; `1.00` significa 100 % |
| Cupón fijo | Tipo decimal anual; el cupón usa principal × tipo anual × fracción de base de cálculo contractual, redondeado por beneficiario |
| Precio de operación | Unidades principales de divisa por unidad entera de valor, con divisa explícita |
| Pago en token | Unidades base exactas de token tras una conversión de decimales verificada |
| NAV de ERC-4626 | Punto fijo WAD; `1e18` significa una unidad base subyacente por unidad base de participación |
| Precio de repo | Unidades base de token de préstamo por token de garantía entero de cero decimales |
| Tipo/índice de repo | WAD; el LTV, el factor de reserva y la bonificación de ejecución de la garantía usan puntos básicos |
| Tiempo | Calendario/huso horario legal para fechas contractuales; instante UTC y evidencia de bloque canónica para eventos en cadena |

### Línea base de afirmaciones { #claim-baseline }

| Afirmación | Hallazgo | Disposición requerida |
|---|---|---|
| «Totalmente conforme» en DE/LU/FR/LI | Falsa como afirmación incondicional | Sustituir por decisiones acotadas, con evidencia y con vencimiento, por instrumento y por operador |
| Todo emisor/receptor supera el KYC antes de las acciones de valor | Falsa | Puerta de operación central del lado del servidor, más evidencia de documento revisado/titular real/filtrado |
| La base de datos o la blockchain tiene autoridad universal | Documentación contradictoria | Seleccionar la autoridad por instrumento; distinguir el registro legal del ledger técnico y de la proyección |
| El reporting de MiFIR está listo para producción | Marcador de posición | Poner la salida en cuarentena hasta que existan población de datos, el esquema RTS 22, la corrección/deduplicación y la gestión de acuses de recibo |
| La exportación DAC8 está lista | Falsa/obsoleta para la implementación alemana actual | Reconstruir en torno a la diligencia debida del usuario declarable, la residencia fiscal/el TIN, los flujos, el enrutamiento por jurisdicción, las correcciones y las decisiones sobre la KStTG |
| Vías de pago conformes con MiCAR | Falsa | Tratarlas como atestaciones del operador hasta que se verifiquen la evidencia del emisor, la clasificación, la autorización y el reembolso |
| Automatización de incidentes DORA | Marcador de posición | Mantener los registros manuales etiquetados como tales; implementar evidencia de detección, clasificación, enrutamiento y envío antes de afirmar que existe automatización |
| Los PII están cifrados en reposo | Falsa para las columnas de personas físicas | Corregir la afirmación o implementar cifrado a nivel de campo/aplicación con ciclo de vida de claves y migración |
| Todas las cadenas/estándares están implementados | Falsa | Starknet/Stellar y cualquier otra integración esqueleto deben etiquetarse como marcador de posición |
| El DvP dentro de la misma cadena es atómico | Verificado solo para tokens de transferencia exacta y una sola transacción | Añadir comprobaciones de tramo exacto, evidencia de finalidad/reorganización y conciliación con el registro legal |

## Registro de propuestas de la fase 0 (autoevaluado, sin revisar) { #phase-0-proposal-register-self-assessed-unreviewed }

| ID | Propuesta | Autoevaluación | Estado de seguimiento | Evidencia registrada / bloqueador |
|---|---|---|---|---|
| M0-3525-A | Corregir la transferencia en forma de dirección de ERC-3525 para que el origen disminuya y el destino aumente exactamente una vez | Propuesto (sin revisar) | SELF_ASSESSED | Solo evidencia de conservación del contrato: pruebas de regresión más la suite Foundry completa, 449 superadas / 31 omitidas; esto no demuestra la conciliación indexada ni la del registro legal |
| M0-3525-B | Aplicar la política de pausa/congelación/lista blanca a las transferencias de propiedad de tokens completos | Cambios propuestos, observados (sin revisar) | IN_PROGRESS | Aplicar todas las comprobaciones a través del hook de propiedad de ERC-721, preservar la semántica de mint/burn de dirección cero y el bypass de operación forzosa, y probar ambas API de transferencia además del fallo del formulario de dirección atómico |
| M0-3525-C | Aplicar límites globales y por ranura con semántica explícita de acumulado frente a en circulación | Bloqueado — se requiere decisión | BLOCKED_DECISION | Decidir la semántica de acumulado frente a en circulación, el margen para burn/redención/burn forzoso, la jerarquía de límites, y el comportamiento de enmienda y de reducción; conciliar la emisión heredada/pendiente por ranura |
| M0-7540-A | Deshabilitar los métodos síncronos heredados `deposit`, `mint`, `withdraw` y `redeem`; anunciar máximos en cero | Propuesto (sin revisar) | SELF_ASSESSED | Todas las rutas síncronas revierten, los máximos son cero, las pruebas de solicitud pasan; la suite Foundry completa terminó con código 0 |
| M0-7540-B | Vincular el cumplimiento a metadatos de precio de ejercicio del NAV inmutables y oportunos | Bloqueado — se requiere decisión | BLOCKED_DECISION | Decidir precio a plazo/histórico, calendario/huso horario de corte, antigüedad máxima, precio de ejercicio elegible, corrección/sustitución y autoridad de valoración; las solicitudes heredadas permanecen como `UNVERIFIED_STRIKE` |
| M0-4626 | Aplicar el modelo de metadatos/actualidad del NAV y de solvencia de reservas | Bloqueado — se requiere decisión | BLOCKED_DECISION | Decidir entre el modelo síncrono respaldado en efectivo o el modelo asíncrono de cartera gestionada, las reservas/custodia elegibles, el colchón de liquidez, las comisiones y la forma de reembolso |
| M0-REPO-A | Quemar las participaciones escaladas redondeadas al alza al retirar el activo y rechazar movimientos de valor de participación cero | Propuesto (sin revisar) | SELF_ASSESSED | Prueba de límite por encima del índice 1e18 más invariantes del repo: 3 superadas con 256 ejecuciones / 5.120 llamadas cada una |
| M0-REPO-B | Impedir que eliminar/volver a añadir valore un mercado más de una vez | Propuesto (sin revisar) | SELF_ASSESSED | Prueba de regresión de volver a añadir y suite Foundry completa superada; `marketCount` ahora permanece único |
| M0-REPO-C | Hacer que el suministro, el endeudamiento, el reembolso, la liquidación y las salidas totales sean conservadores en participaciones y seguros frente a desbordamiento | Cambios propuestos, observados (sin revisar) | PROPOSED | Usar `mulDiv` seguro frente a desbordamiento, rechazar unidades contables cero, registrar la deuda de forma conservadora, basar el movimiento parcial de efectivo/garantía en los deltas reales de deuda, y hacer explícitas las salidas totales; los mercados vivos e inmutables aún requieren evidencia de inventario/liquidación/sustitución |
| M0-REPO-RISK | Cadencia/anulación del oráculo, relación LLTV/bonificación, factor de cierre y cascada de deuda incobrable | Bloqueado — se requiere decisión | BLOCKED_DECISION | Decidir el quórum de oráculo/cadencia/anulación, la relación LLTV/bonificación, el factor de cierre/regla de antigüedad, la cascada de pérdidas y los términos legales/de custodia de la garantía; no modificar esto en el lote aritmético |
| M0-DVP | Verificación exacta del tramo de transferencia, identificadores de operación con plazo determinado y estados de finalidad del backend | Cambios propuestos, observados (sin revisar) | PROPOSED | Solo lote técnico: deltas de saldo de ambas cuentas, identificador separado por dominio/sal, y ciclo de vida provisional de evento/recibo; los derechos de cancelación, el umbral de finalidad de cadena y la vía de resolución legal siguen siendo decisiones de producto |
| M0-BOND | Normalizar decimales, vencimiento, derechos de fecha de registro y reembolso basado en cantidad | Bloqueado — se requiere decisión | BLOCKED_DECISION | Decidir la base de cálculo de días, el calendario/huso horario de negociación, la autoridad de fecha de registro/ex-cupón, el redondeo, la retención/depósito en suspenso, el impago/amortización anticipada/enmienda y los términos de reembolso parcial; poner en cuarentena el escritorio actual como solo de referencia |
| M0-LEDGER | Hacer monótonas las transiciones de liquidación, restaurar el inventario exactamente una vez, y exigir evidencia independiente de efectivo y de entrega | Cambios propuestos, observados (sin revisar) | PROPOSED | Modelo aditivo de estado/transición/evidencia/reserva; el `SETTLED` heredado pasa a no verificado, las referencias del comprador no pueden promover el estado, y `LEGALLY_EFFECTIVE` permanece inalcanzable sin una política de autoridad configurada |
| M0-INDEXER-A | Reparar la paridad configurada de firmas de handler, los eventos de despliegue de la fábrica y la representación de direcciones por componente | Propuesto (sin revisar) | SELF_ASSESSED | Resultado técnico limitado: 16 ABI de contrato / 71 handlers configurados, renderizador de direcciones, codegen, compilación WASM y paso del wrapper de solo validación; esto no demuestra la identidad del código desplegado |
| M0-INDEXER-B | Añadir cursores provisionales/finales, reversión ante reorganizaciones y conciliación directa con la cadena | Cambios propuestos, observados (sin revisar) | PROPOSED | Construir la fontanería de conciliación provisional/huérfano/rebobinado y de puntos de control con denegación por defecto (fail closed); ningún evento pasa a `FINAL` hasta que exista una política de cadena aprobada por separado y una configuración de RPC de confianza |
| M0-INDEXER-C | Rastrear el valor de ERC-3525 por token/propietario/ranura, el ciclo de vida duradero de la solicitud de ERC-7540 incluida la cancelación, y el estado de flujo de caja escalado/de bóveda de repo | Cambios propuestos, observados (sin revisar) | SELF_ASSESSED | Las 25 entidades tienen un estado de proyección enumerado; los historiales incompletos observados por primera vez permanecen en `INCOMPLETE`; RepoVault es el flujo de caja neto firmado de los activos, no el principal; la puerta estática completa pasa. No existe prueba de repetición ni de finalidad |
| M0-INDEXER-D1 | Dar soporte a todas las instancias configuradas de BondDesk/AMM/RepoVault, actualizar la documentación de migración del operador, y hacer que la puerta de pruebas compile los mapeos | Cambios propuestos, observados (sin revisar) | SELF_ASSESSED | Todas las instancias son explícitas; `NONE` es una afirmación del operador; la implementación en vivo exige una etiqueta nueva; la recarga de graph-node precede a la implementación; los bloques por fuente y la reversión no destructiva están documentados y revisados de forma cruzada |
| M0-INDEXER-D2 | Verificar el bytecode por RPC y la identidad/hash del código de runtime aprobado antes del despliegue | Bloqueado — se requiere decisión | BLOCKED_DECISION | Requiere un inventario autorizado por cadena, artefactos/hashes de runtime/proxy/admin aprobados, expectativas de clave y política de rotación; las comprobaciones sintácticas de dirección no son verificación de identidad |
| F0-001 | Perímetro de instrumento versionado, capacidades legales, autorizaciones regulatorias y política de autoridad del ledger | Bloqueado — se requiere decisión | BLOCKED_DECISION | Decisiones de asesoría jurídica/operador por jurisdicción e instrumento; F0-002 puede añadir una carcasa de esquema, pero no debe sembrar ninguna autorización general activa (blanket allow) |
| F0-002 | `AssetOperationGate` central, aplicado en servicios y en rutas HTTP | Cambios propuestos, observados (sin revisar) | PROPOSED | Instantáneas de decisión versionadas, acotadas y con vencimiento/revocables a nivel de capa de servicio; una política ausente/obsoleta/no reconocida deniega, sin efectos secundarios en BD/cadena, y registra la correlación de política/motivo/auditoría |
| F0-003 | Evidencia de KYC de documento revisado, titular real, jurisdicción y filtrado reciente | Bloqueado — se requiere decisión | BLOCKED_DECISION | Decidir listas de verificación, revisión/aceptación, cadencia, EDD, integridad/origen del titular real y conservación; los documentos heredados cargados permanecen sin revisar y la puerta de operación deniega |
| F0-004 | Términos económicos, escalas, divisas, calendarios y redondeos explícitos e inmutables | Cambios propuestos, observados (sin revisar) | PROPOSED | Construir únicamente el esquema inmutable/versionado y el marco de conversión/cálculo exacto; migrar los términos actuales como `LEGACY_UNVERIFIED` y no inventar convenciones de bonos/NAV |
| F0-005 | Estado de liquidación multidimensional y modelo de evidencia | Cambios propuestos, observados (sin revisar) | PROPOSED | Mismo límite seguro que M0-LEDGER; `LEGALLY_EFFECTIVE` permanece inalcanzable sin F0-001, y el `SETTLED` heredado se convierte en `LEGACY_SETTLED_UNVERIFIED` |
| F0-006 | Instrucción/acuerdo autorizado y ledger cronológico de cambios de registro | Bloqueado — se requiere decisión | BLOCKED_DECISION | Decidir la autoridad de instrucción/acuerdo/corrección, firmas/evidencia, secuenciación y reversión por tipo de asiento/jurisdicción; un historial genérico de solo apéndice no puede autorizar una mutación |
| F0-007 | Finalidad de cadena y conciliación de bytecode/administrador/configuración desplegados | Bloqueado — se requiere decisión | BLOCKED_DECISION | M0-INDEXER-B puede añadir fontanería provisional, pero las políticas de finalidad/punto de control, RPC de confianza/quórum, runtime/proxy/admin/propietario/clave, y de dependencia legal, no están resueltas |
| F0-008 | Liquidación de pago/DvP verificable; deshabilitar en producción las mutaciones canónicas simuladas | Cambios propuestos, observados (sin revisar) | IN_PROGRESS | Configuración/esquema predeterminados a liquidación inicial e inmediata en falso; las referencias de las partes son metadatos no verificados; combinar tramos de DvP exactos con evidencia de adaptador independiente, y ninguna mutación de titular sin efectivo y entrega verificados |
| F0-009 | Instantánea de derecho bloqueada y pagos de operaciones societarias verificados de forma independiente | Bloqueado — se requiere decisión | BLOCKED_DECISION | Decidir la autoridad de fecha de registro/ex-cupón, huso horario/calendario, impuestos/retenciones, depósito en suspenso de titulares bloqueados, correcciones y pagos impagados; los derechos heredados permanecen sin verificar |
| F0-010 | Interruptor de emergencia (kill switch) para préstamos hasta que existan controles legales/de garantía y conciliación | Cambios propuestos, observados (sin revisar) | IN_PROGRESS | Exposición de backend/UI desactivada por defecto y con denegación por defecto (fail closed); los mercados nuevos pausan por defecto el suministro y el endeudamiento, mientras que el retiro/reembolso que reduce el riesgo sigue disponible; los mercados antiguos requieren evidencia de inventario/pausa/liquidación/sustitución |
| F0-011 | Poner en cuarentena las salidas de MiFIR y DAC8/KStTG como borrador/no validadas | Cambios propuestos, observados (sin revisar) | SELF_ASSESSED | Desactivado por defecto y prohibido si se habilita en producción; espacios de nombres de prototipo y `DRAFT_UNVALIDATED`; estados/eventos de solo transporte; pasan 20 pruebas unitarias/de migración específicas, incluida la de PostgreSQL sembrado V17→V18. Los esquemas oficiales, la población de datos, el enrutamiento, los acuses de recibo y la aprobación jurídica siguen siendo bloqueadores |
| F0-012 | Registro de afirmaciones legible por máquina con evidencia, alcance, propietario, vencimiento y aplicación en CI | Cambios propuestos, observados (sin revisar) | SELF_ASSESSED | Esquema/validador cerrado, registro canónico y hashes exactos de texto/archivo, comparación de base solo-apéndice, comprobaciones de vencimiento/independencia, una única excepción de migración inmutable en lista permitida, escaneo del repositorio con denegación por defecto, y evidencia de CI de aplicación obligatoria — todo ello autoevaluado por un colaborador automatizado, sin revisión externa. Ejecución actual: verificador/regresiones, ERC-3525 (17/17), reporting del backend (20/20, incluida la migración de PostgreSQL), y paso completo de las puertas estáticas/codegen/WASM del subgrafo. Esto es gobernanza, no certificación legal |

## Evidencia de referencia { #baseline-evidence }

| Superficie | Resultado de referencia | Hallazgo |
|---|---|---|
| Backend `./mvnw verify -B` | La línea base pasó fuera del sandbox restringido; la suite combinada de unidad/migración de F0-011 pasa 20/20 | Los trabajos programados continúan tras el desmontaje de la aplicación de prueba, generan errores grandes en la base de datos, y retrasan el cierre del fork; el JaCoCo real es de aproximadamente 45,0 % de línea / 38,6 % de rama frente a una puerta de 36 % / 23 %, y una documentación contradictoria del 70 % |
| Foundry `forge test -q` | 449 superadas, 31 omitidas tras el primer lote aprobado; repetición independiente terminó con código 0 | Las pruebas de regresión ahora cubren la conservación de la transferencia en forma de dirección de ERC-3525, el bypass síncrono de ERC-7540, el redondeo del retiro del repo, y la valoración única de mercado |
| Cairo `snforge test` | 29/29 superadas | La superficie de Cairo todavía necesita revisión de dominio/seguridad |
| Retransmisor confidencial | Lint/build de TypeScript 6 y 33/33 pruebas Vitest superadas | Migración a Express 5 y ESM completada; la auditoría no reporta hallazgos |
| Subgrafo EVM | 16 ABI / 71 handlers, 25 entidades de proyección, renderizador multiinstancia, codegen y builds superados | La auditoría de producción está limpia; una ruta ascendente de Graph CLI a `decompress` queda aislada por la allowlist ejecutable de `SECURITY-EXCEPTIONS.md` |
| Apps Angular de operador/inversor | Lint/build de Angular 22 superados; 124 pruebas del operador y 125 del cliente pasan en Vitest | El runtime zoneless nativo y Angular build/Vitest sustituyen a Karma |
| Documentación MkDocs | Build estricto en cinco idiomas y pruebas de navegador superados | Mermaid, cambio de tema y conservación de origen/puerto al cambiar idioma están cubiertos; auditoría de producción limpia |
| DAML | No ejecutado | `dpm` no está disponible en el entorno actual |

## Bloqueadores conocidos de despliegue y operaciones { #known-deployment-and-operations-blockers }

- Helm combina un único volumen de monedero `ReadWriteOnce` con entre 3 y 10 réplicas con anti-afinidad.
- El ingress enruta directamente al backend y omite Kong, mientras que la política de red no admite la ruta del ingress-controller.
- Las claves secretas de PostgreSQL referenciadas por Helm no coinciden.
- Los JWT del frontend se almacenan en `localStorage`; las cabeceras de refuerzo de la respuesta están incompletas.
- Promtail, las métricas de Kong, las alertas de copia de seguridad y los supuestos sobre el pushgateway no forman una ruta de monitorización funcional.
- Una única clave de despliegue en bruto no tiene documentado un traspaso a multifirma/timelock.
- No hay cobertura de CI para el código de frontend compartido, el retransmisor (relayer), Cairo, DAML, varios indexadores, la documentación, Compose/Kong, ni Helm.

Estos siguen siendo bloqueadores de publicación hasta que su veredicto de fase y su evidencia de verificación queden registrados aquí.
