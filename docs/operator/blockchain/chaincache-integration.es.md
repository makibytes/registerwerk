---
title: Integración de chaincache
---

# Integración de chaincache

[chaincache](https://github.com/makibytes/chaincache) es un producto hermano: una pasarela RPC
con su propio seguimiento de cadena canónica y finalidad. Registerwerk puede conectarse a una
instancia de chaincache de la misma manera que a cualquier otro nodo RPC — como una URL en la
lista de nodos —, pero al hacerlo obtiene retractaciones de reorg basadas en push y sin lagunas, y
un nivel `SAFE` real, en lugar de la finalidad tosca y basada en sondeo que ofrece una conexión
directa a un nodo. Esta página describe qué es realmente esa mejora, y la configuración
(deliberadamente mínima) que requiere.

## Un workload de chaincache por cadena

El modelo de despliegue de chaincache es **un proceso Spring Boot por cadena**, no una única
instancia que sirve a todas las cadenas — `chaincache.runtime.allow-multiple-chains: false` es el
propio valor por defecto de chaincache, y se niega a arrancar con cero o más de una cadena
configurada. "Multi-cadena" significa N workloads, cada uno su propio desplegable, cada uno
sirviendo exactamente una cadena. La infraestructura compartida — PostgreSQL, monitorización,
ingress — está diseñada para compartirse *entre* esos workloads: cada fila que escribe un workload
está delimitada a su cadena, de modo que dos workloads pueden apuntar a una única instancia de
Postgres sin colisionar. La pila de demostración lo hace de verdad: `chaincache-sepolia` y
`chaincache-base` son dos contenedores independientes que comparten una única instancia
`chaincache-postgres`.

Esto cambia lo que significa "conectar Registerwerk a chaincache" a nivel de red: no existe un
único nombre de host de chaincache al que apuntar el nodo de cada cadena. Cada `RpcNode` de tipo
`CHAINCACHE` apunta al workload específico que sirve *su* cadena — `managementUrl` es el
host:puerto propio de ese workload, y `remoteChainKey` es la clave de cadena con la que se
configuró ese workload (casi siempre la única clave de cadena que conoce, ya que cada workload
sirve una única cadena).

Kafka explícitamente **no** forma parte de esta superficie de integración. chaincache tiene su
propio relé Kafka opcional para otros consumidores posteriores (`chaincache.kafka.enabled`), pero
Registerwerk nunca lo consume — los dos protocolos que Registerwerk realmente habla con chaincache
son el proxy JSON-RPC/web3j (`/{chain}/rpc`) y el WebSocket de eventos duraderos propio de
chaincache (`/{chain}/ws`). Si se ve `chaincache.kafka.enabled: true` en un workload, eso sirve a
otro consumidor, no a Registerwerk.

## Por qué es un tipo de nodo, no un archivo de configuración

Un diseño anterior trataba la adopción de chaincache como "ningún cambio de código en
Registerwerk — es solo una URL en una tabla", bajo la teoría de que la invisibilidad era el
objetivo. Para un producto cuyo propósito incluye demostrar lo que un registro gana con chaincache,
la invisibilidad es justo lo contrario del objetivo: `RpcNode` tiene un `kind`
(`DIRECT_RPC` | `CHAINCACHE`), y la interfaz de operador indica, por conexión, qué garantías ofrece
realmente. Un nodo RPC directo ofrece finalidad tosca y basada en sondeo, que puede pasar por alto
un reorg de corta duración y no tiene un nivel `SAFE` real sin una etiqueta de bloque `safe`; una
conexión chaincache expone además retractaciones basadas en push, sin lagunas y repetibles, y un
nivel `SAFE` real.

## Detección automática — no hay nada que configurar a mano

Un operador añade un nodo siempre de la misma manera, sin importar qué resulte ser: pegar una URL,
una etiqueta opcional. Si esa URL es una conexión chaincache se detecta automáticamente, nunca se
declara:

1. La URL se comprueba contra la convención de enrutamiento de chaincache, `/<chainKey>/rpc` —
   una URL con esa forma produce un `managementUrl` candidato (esquema+host+puerto) y un
   `remoteChainKey` candidato (el último segmento de la ruta).
2. El candidato se verifica con una llamada real `GET <managementUrl>/api/capabilities`,
   comprobando una entrada cuyo `chainKey` coincida. Solo cuando hay coincidencia se registra el
   nodo realmente como `CHAINCACHE` — una URL que solo *parece* tener forma de chaincache pero no
   responde como tal (una coincidencia, un endpoint RPC de terceros real cuya ruta termina por
   casualidad en `.../rpc`, o chaincache temporalmente inaccesible) se trata como `DIRECT_RPC`,
   nunca se deja en un estado "desconocido". A una coincidencia confirmada le sigue una segunda
   llamada, de mejor esfuerzo, `GET <managementUrl>/api/chains` para los recuentos de proveedores
   ascendentes de ese workload (véase
   [Qué se sondea y se muestra](#que-se-sondea-y-se-muestra)) — un fallo aquí solo degrada a
   recuentos de nodos ausentes, nunca deshace la coincidencia confirmada.
3. Un trabajo en segundo plano repite esta comprobación para cada nodo habilitado
   aproximadamente una vez por minuto, en ambas direcciones — un nodo `DIRECT_RPC` cuya URL
   empieza a responder como chaincache se promueve; un nodo que falla al alcanzar chaincache tres
   veces consecutivas, o es alcanzable pero ya no lista el `remoteChainKey` esperado, vuelve a
   `DIRECT_RPC`. Esta histéresis de tres fallos (inmediata ante un "ya no sirve esta cadena"
   confirmado, tolerante ante un único contratiempo transitorio) existe específicamente para que
   una sola comprobación fallida no derribe el flujo de eventos duraderos de una cadena. Existe
   una acción manual "volver a comprobar ahora" por nodo para un operador que no quiera esperar
   al siguiente ciclo.
4. Una URL que resuelve a una autoridad que acaba de fallar una comprobación se omite durante una
   hora (una caché negativa) en lugar de volver a comprobarse en cada ciclo — esto es lo que evita
   que un endpoint RPC de terceros real que coincide por casualidad con el patrón `/<key>/rpc`
   reciba una comprobación saliente repetida y ruidosa.

No existe en ningún lugar del producto un selector de tipo — un operador no puede declarar el tipo
de un nodo, solo descubrir cuál es.

`ChainConfig.finalitySource` (`RPC_SELF_PROBE` | `CHAINCACHE`) sigue el mismo principio: se
recalcula automáticamente según si la cadena tiene actualmente un nodo `CHAINCACHE` habilitado,
cada vez que se añade, elimina, habilita, deshabilita o vuelve a detectar un nodo. Tampoco existe
un campo para que un operador la establezca directamente.

## Autenticación

El propio valor por defecto de chaincache es `chaincache.auth.enabled: true`, que exige un token
portador con rol `USER`/`ADMIN`/`OPERATOR` en `/api/**` y `/{chain}/api/**` (la propia ruta del
proxy RPC, `/{chain}/rpc` y `/{chain}/ws`, permanece abierta según el propio valor por defecto de
chaincache — `chaincache.auth.rpc-enabled: false` —, ya que se pretende que esa ruta sea
alcanzable igual que cualquier endpoint RPC). Registerwerk genera su propio token HS256 de corta
duración (`chain.internal.ChaincacheTokenFactory`, un token de 5 minutos con
`roles: ["OPERATOR"]`, en caché hasta poco antes de expirar) a partir de
`registerwerk.chaincache.jwt-secret` — un secreto **distinto** de `JWT_DEV_SECRET`; nunca
reutilizar aquí la propia clave de firma de inicio de sesión de operador de Registerwerk, ya que
cualquiera que posea una credencial de chaincache podría entonces también falsificar sesiones de
operador de Registerwerk. Ese secreto debe coincidir con el `chaincache.jwt.secret` propio del
workload objetivo.

Si el secreto falta o es incorrecto, las sondas de capacidades y las conexiones al flujo duradero
fallan con 401/403. Esto deliberadamente **no** se trata como "esto no es una instancia de
chaincache" — un nodo que responde con 401/403 permanece exactamente como estaba (un nodo
`CHAINCACHE` confirmado sigue siendo `CHAINCACHE`, un nodo recién añadido sigue siendo
`DIRECT_RPC`), y un único registro `WARN` nombra la solución
(`registerwerk.chaincache.jwt-secret`) en lugar de repetirla en cada ciclo de sondeo. Un secreto
incorrecto o ausente nunca puede reescribir silenciosamente el modelo de datos.

`registerwerk_chaincache_capability_probe_failures_total{management_url,reason}` distingue este
caso (`reason="unauthorized"`) de `chain_missing` (alcanzable, no sirve esta cadena) y
`unreachable` (red/tiempo de espera/5xx) — véase [Observabilidad](#observabilidad) más abajo.

## Qué se sondea y se muestra

Una vez detectado, `GET /api/capabilities` se vuelve a sondear según el mismo calendario que la
nueva detección del tipo, y su respuesta se almacena como `capabilities` del nodo: modelo de
finalidad, profundidades de confirmación safe/finalized, qué espacios de nombres RPC están
configurados, si `debug_traceBlockByHash` está realmente disponible en el proveedor ascendente,
disponibilidad del flujo duradero, y la propia postura de Kafka de ese workload. Una coincidencia
confirmada incorpora además los recuentos de proveedores ascendentes de `GET /api/chains`
(`configuredNodeCount`/`availableNodeCount` — cuántos proveedores RPC ascendentes ha configurado
ese workload para la cadena, y cuántos responden actualmente). El panel expandible de la lista de
nodos del operador, en una fila de tipo chaincache, muestra todo esto — además de qué workload
(`managementUrl` + `remoteChainKey`) sirve realmente la cadena, y si Registerwerk mantiene
actualmente una conexión de eventos duraderos activa hacia él — justo al lado de los datos de
salud más sencillos de un nodo directo; la brecha de capacidades es deliberada y visible, nunca
oculta.

## El flujo duradero

Cuando la `finalitySource` de una cadena es `CHAINCACHE`,
`blockchain.internal.ChaincacheDurableStreamManager` abre un WebSocket persistente hacia
`<managementUrl>/<remoteChainKey>/ws` y emite `chaincache_subscribeDurable` con un `consumerId`
con la forma `registerwerk:<instanceId>:<remoteChainKey>` — estable a través de los reinicios de
la misma instancia de Registerwerk (para que su cursor se reanude en lugar de empezar de cero),
distinto por réplica mediante `registerwerk.chaincache.instance-id` (para que las réplicas no
compartan un cursor y dividan silenciosamente el flujo de eventos entre ellas). **No hay ninguna
llamada de reanudación separada que hacer**: chaincache persiste el cursor de cada consumidor en
el servidor (`durable_consumer_cursor`, indexado por `consumerId` + flujo) y lo reanuda
automáticamente en cuanto ese mismo `consumerId` se suscribe de nuevo — `chaincache_resume` es un
alias de despacho para la misma llamada `subscribeDurable`, no un paso independiente que
Registerwerk deba emitir por sí mismo.

Cada evento duradero lleva una `sequence` monótonamente creciente, un `kind` (`BLOCK` o
`RETRACTION`) y — en el caso de una retractación — un `retractsEventId` (`"block:..."` frente a
`"log:..."`, solo el nivel de bloque importa para el seguimiento de la cadena canónica) más un
`payload` que lleva la corrección real: `commonAncestor`, la altura a partir de la cual todo lo
posterior queda huérfano. Esto es lo que hace que la detección de reorg de chaincache sea sin
lagunas y basada en push: Registerwerk no tiene que sondear en busca de una retractación,
chaincache la señala en el momento en que ocurre, de forma repetible (un evento perdido se puede
recuperar al reconectar mediante el cursor persistido, nunca se pierde silenciosamente). Los
eventos se confirman con `chaincache_ack` a medida que se procesan.

La ruta de detección de reorg puramente por RPC (`ReorgGuard`, descrita en
[Resiliencia de los indexadores](../indexers/resilience.md)) permanece intacta y sigue
funcionando para cada cadena `RPC_SELF_PROBE` — el flujo duradero es una señal adicional, de
mayor fidelidad, para las cadenas que lo han adoptado, no un reemplazo para las que no lo han
hecho. Una cadena `CHAINCACHE` también obtiene su reverificación de la ventana de finalidad desde
el propio endpoint de chaincache `GET /{remoteChainKey}/api/blocks/{number}/finality` en lugar de
mediante sondeo RPC — cualquier fallo aquí (404, 5xx, tiempo de espera, 401) recae en el
autosondeo RPC en lugar de fabricar un reorg falso, de modo que una chaincache temporalmente
inalcanzable degrada el seguimiento de finalidad en lugar de romperlo por completo.

## Configuración mínima

La pila de demostración ejecuta dos workloads de chaincache en paralelo con un sencillo nodo RPC
directo, de modo que la lista de nodos del operador muestra una comparación real desde el
principio: `chaincache-sepolia` sirve la devnet local de Anvil (`DEPTH_BASED`,
safe=3/finalized=6), y `chaincache-base` sirve proveedores RPC públicos reales de Base Sepolia
(`TAG_BASED`, `required-provider-agreement: 2`). Toda la configuración del lado de chaincache
para el workload de devnet local es:

```yaml
# Servicio Compose propio de chaincache-sepolia
environment:
  SPRING_PROFILES_ACTIVE: demo
  CHAINCACHE_CHAINS_SEPOLIA_RPC_NODES_0_PROVIDER: anvil
  CHAINCACHE_CHAINS_SEPOLIA_RPC_NODES_0_HTTP: http://anvil:8545
  CHAINCACHE_CHAINS_SEPOLIA_RPC_NODES_0_WS: ws://anvil:8545
  CHAINCACHE_CHAINS_SEPOLIA_RPC_EXPECTED_CHAIN_ID: "0xaa36a7"
  CHAINCACHE_CHAINS_SEPOLIA_CHAIN_FINALITY_MODEL: DEPTH_BASED
  CHAINCACHE_AUTH_ENABLED: "true"
  CHAINCACHE_JWT_SECRET: ${CHAINCACHE_JWT_SECRET}
```

Vale la pena señalar dos cosas explícitamente, ambas errores reales con los que se topó esta
integración y que se corrigieron:

!!! note "La clave de cadena debe ser una sola palabra"
    La vinculación flexible de variables de entorno de Spring Boot para propiedades
    `Map<String, ComplexBean>` (que es lo que es `chaincache.chains.<key>.*`) no vincula de
    forma fiable una clave de mapa de varias palabras/con varios guiones bajos
    (`sepolia-devnet`, `local_devnet`) solo a partir de variables de entorno — únicamente una
    clave de una sola palabra (`sepolia`, `anvil`) se vincula correctamente. Esta es una
    limitación real del `Binder` de Spring Boot, no un error de chaincache; las claves de cadena
    de la pila de demostración son deliberadamente palabras únicas por este motivo.

!!! note "expected-chain-id es hexadecimal, no decimal"
    `RpcProviderIdentityValidator` compara `expected-chain-id` con la respuesta `eth_chainId`
    del proveedor ascendente, que siempre es hexadecimal con prefijo `0x`. Configurarlo como una
    cadena decimal (`"11155111"` en lugar de `"0xaa36a7"`) hace que nunca pueda coincidir, y con
    `identity-validation-required: true` (el propio valor por defecto de chaincache) el workload
    no arrancará en absoluto hasta que se corrija esto.

Del lado de Registerwerk: nada. Añadir un nodo RPC con la URL
`http://chaincache-sepolia:8080/sepolia/rpc` a través del flujo normal de añadir nodo (o dejar
que `DemoDataSeeder` lo siembre, como hace la pila de demostración) — el tipo, la
`finalitySource` y las capabilities siguen automáticamente en el plazo de un ciclo de detección
en cuanto ese workload sea alcanzable y esté autenticado.

## Despliegue: infraestructura gestionada compartida, releases independientes

El propio [chart de Helm](https://github.com/makibytes/chaincache) de chaincache despliega una
release por cadena, cada una apuntando a una instancia de Cloud SQL compartida y una `HTTPRoute`
de Gateway API de Kubernetes compartida — la misma forma que la pila de demostración reproduce
localmente con una única instancia `chaincache-postgres` compartida detrás de dos contenedores.
El propio chart de Registerwerk **no** despliega chaincache (es un producto versionado y
desplegado de forma independiente); lleva un bloque de valores `chaincache:` que nombra las URL de
workload por cadena a las que conectarse y una referencia al secreto JWT compartido, más una regla
de salida `NetworkPolicy` que permite el tráfico hacia el namespace de chaincache cuando ambos
charts se ejecutan en el mismo clúster. Un ejemplo concreto: dos releases de Helm de chaincache
(`chaincache-sepolia`, `chaincache-base`) en un namespace `chaincache`, ambas apuntando a una
instancia de Cloud SQL a través del mismo patrón de sidecar `Cloud SQL Auth Proxy` que documenta
el propio README de chaincache, ambas raspadas por la misma instancia de Prometheus que la pila de
monitorización de Registerwerk ya ejecuta; la lista de valores `chaincache.workloads` del propio
chart de Registerwerk nombra ambas `managementUrl`, de modo que un arranque equivalente a
`DemoDataSeeder` (o un "añadir nodo" manual) pueda conectarlas.

### Actualizaciones de versión mayor de Flyway / PostgreSQL

Las propias migraciones de chaincache son una única línea base de instalación limpia más archivos
`V{n}` incrementales, la misma convención que usa Registerwerk. Un salto de versión mayor de
PostgreSQL (la pila de demostración pasó de 15 a 18.6) no es algo que Flyway maneje por sí solo —
necesita o bien un `pg_upgrade` en el sitio o un volcado/restauración, y la propia ruta del
directorio de datos cambia entre las etiquetas de imagen mayores de Postgres
(`/var/lib/postgresql/data` frente a `/var/lib/postgresql` a partir de la versión 18) — montar el
volumen antiguo en la ruta esperada por la nueva imagen arranca silenciosamente un clúster nuevo y
vacío en lugar de fallar de forma ruidosa. Tratar un salto de versión mayor de PostgreSQL como su
propio paso de migración deliberado, nunca como un efecto secundario de un bump de etiqueta de
imagen.

### Lista de comprobación de producción

- `chaincache.runtime.identity-validation-required: true`, con `expected-chain-id` establecido
  como hexadecimal con prefijo `0x` para cada cadena configurada — una identidad de cadena no
  validada o mal configurada significa que un workload podría servir silenciosamente los datos
  de la cadena equivocada.
- `chaincache.cache.local-snapshot-enabled: false` y
  `chaincache.request.local-stats-enabled: false` en cada réplica — la postura que esta
  integración reproduce localmente en la pila de demostración, correspondiente a lo que necesita
  un despliegue replicado horizontalmente (el estado local por réplica no tiene sentido en cuanto
  hay más de un pod detrás de un workload).
- `chaincache.auth.enabled: true`, con un `registerwerk.chaincache.jwt-secret` que **no** sea el
  mismo valor que `JWT_DEV_SECRET` ni ninguna otra clave de firma de Registerwerk.
- El endpoint de comprobación de salud usado para la orquestación debería ser
  `/actuator/health/readiness`, no liveness — un workload puede estar vivo pero aún no listo
  para servir (por ejemplo, mientras todavía establece sus conexiones RPC ascendentes), y
  enrutar tráfico hacia él durante esa ventana produce fallos de sondeo evitables del lado de
  Registerwerk.
- `chaincache.auth.prometheus-public: true` solo cuando el raspado ocurre a través de un límite
  de red en el que Prometheus ya confía (por ejemplo, la misma red interna del clúster, nunca un
  puerto publicado) — esto existe específicamente para que `/actuator/prometheus` no requiera el
  propio token portador de Registerwerk, que Prometheus no lleva.

## Observabilidad

La propia instancia de Prometheus de Registerwerk raspa directamente ambos workloads de
chaincache (etiqueta `chaincache_workload` que los distingue) y alerta ante workload caído, tasa
de error RPC ascendente elevada, flapping de WebSocket y ráfagas de reorg usando las propias
métricas de Micrometer de chaincache (`chaincache.rpc.errors`, `chaincache.rpc.ws.disconnects`,
`chaincache.chain.reorganizations`, `chaincache.rpc.node.latency` — esta última solo expone
`_count`/`_sum`, sin histograma de percentiles, por lo que alertar sobre ella significa latencia
media, no p95). Del lado de Registerwerk,
`registerwerk_chaincache_stream_connected{chain}` y
`registerwerk_chaincache_stream_last_event_timestamp_seconds{chain}` respaldan dos alertas más
(flujo desconectado durante 5+ minutos; ningún evento recibido durante 10+ minutos a pesar de una
conexión abierta — el segundo caso normalmente significa que la propia cadena ha dejado de
producir bloques, no que la integración esté rota). Un panel de Grafana
(`monitoring/grafana/dashboards/chaincache.json`) cubre, por workload, la
salud/latencia/reorgs/desconexiones ascendentes, además de la fila de salud del flujo del lado de
Registerwerk.

## Enlace profundo a chaincheck

[chaincheck](https://github.com/makibytes/chaincheck) es el tercer producto hermano — un monitor
independiente de la flota de nodos, no fusionado en ninguno de los otros dos. Cuando
`environment.chaincheckUrl` está configurado en el frontend de operador, el panel de capacidades
expandible de cada nodo de tipo chaincache incluye un enlace "Ver en chaincheck" hacia él. Esto es
puramente un enlace profundo — Registerwerk no consulta la API de chaincheck ni depende de que sea
alcanzable para nada de lo que se muestra en la propia lista de nodos.
