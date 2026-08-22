---
title: Integrazione chaincache
---

# Integrazione chaincache

[chaincache](https://github.com/makibytes/chaincache) è un prodotto gemello: un gateway RPC con
un proprio tracciamento della catena canonica e della finalità. Registerwerk può connettersi a
un'istanza chaincache nello stesso modo in cui si connette a qualsiasi altro nodo RPC — come URL
nell'elenco dei nodi — ma così facendo ottiene ritrattazioni di reorg push-based e senza lacune, e
un vero livello `SAFE`, invece della finalità grossolana e basata su polling che offre una
connessione diretta a un nodo. Questa pagina descrive cosa sia realmente questo upgrade, e la
configurazione (deliberatamente minima) che richiede.

## Un workload chaincache per catena

Il modello di deployment di chaincache è **un processo Spring Boot per catena**, non un'unica
istanza che serve tutte le catene — `chaincache.runtime.allow-multiple-chains: false` è il default
proprio di chaincache, e il avvio viene rifiutato con zero o più di una catena configurata.
"Multi-catena" significa N workload, ciascuno il proprio deployable, ciascuno che serve
esattamente una catena. L'infrastruttura condivisa — PostgreSQL, monitoraggio, ingress — è
progettata per essere condivisa *tra* questi workload: ogni riga che un workload scrive è
delimitata alla propria catena, quindi due workload possono puntare a un'unica istanza Postgres
senza collisioni. Lo stack demo lo realizza per davvero: `chaincache-sepolia` e `chaincache-base`
sono due container indipendenti che condividono un'unica istanza `chaincache-postgres`.

Questo cambia cosa significhi "connettere Registerwerk a chaincache" a livello di rete: non esiste
un unico hostname chaincache verso cui puntare il nodo di ogni catena. Ogni `RpcNode` di tipo
`CHAINCACHE` punta al workload specifico che serve la *sua* catena — `managementUrl` è l'host:porta
proprio di quel workload, e `remoteChainKey` è la chiave di catena con cui quel workload è stato
configurato (quasi sempre l'unica chiave di catena che conosce, dato che ogni workload serve
un'unica catena).

Kafka esplicitamente **non** fa parte di questa superficie di integrazione. chaincache ha il
proprio relay Kafka opzionale per altri consumatori a valle (`chaincache.kafka.enabled`), ma
Registerwerk non lo consuma mai — i due protocolli che Registerwerk parla effettivamente con
chaincache sono il proxy JSON-RPC/web3j (`/{chain}/rpc`) e il WebSocket di eventi durevoli proprio
di chaincache (`/{chain}/ws`). Se si vede `chaincache.kafka.enabled: true` su un workload, questo
serve un altro consumatore, non Registerwerk.

## Perché è un tipo di nodo, non un file di configurazione

Un design precedente trattava l'adozione di chaincache come "nessuna modifica al codice di
Registerwerk — è solo un URL in una tabella", secondo la teoria che l'invisibilità fosse
l'obiettivo. Per un prodotto il cui scopo include dimostrare cosa un registro guadagni da
chaincache, l'invisibilità è l'esatto opposto dell'obiettivo: `RpcNode` ha un `kind`
(`DIRECT_RPC` | `CHAINCACHE`), e l'interfaccia operatore indica, per connessione, quali garanzie
offra realmente. Un nodo RPC diretto fornisce finalità grossolana e basata su polling, che può
perdere un reorg di breve durata e non ha un vero livello `SAFE` senza un tag di blocco `safe`;
una connessione chaincache espone in aggiunta ritrattazioni push-based, senza lacune e ripetibili,
e un vero livello `SAFE`.

## Rilevamento automatico — non c'è nulla da configurare manualmente

Un operatore aggiunge un nodo sempre nello stesso modo, indipendentemente da cosa risulti essere:
incollare un URL, un'etichetta opzionale. Se quell'URL sia una connessione chaincache viene
rilevato automaticamente, mai dichiarato:

1. L'URL viene verificato rispetto alla convenzione di routing di chaincache, `/<chainKey>/rpc` —
   un URL con quella forma produce un candidato `managementUrl` (schema+host+porta) e un candidato
   `remoteChainKey` (l'ultimo segmento del percorso).
2. Il candidato viene verificato con una chiamata reale `GET <managementUrl>/api/capabilities`,
   controllando una voce il cui `chainKey` corrisponda. Solo in caso di corrispondenza il nodo
   viene effettivamente registrato come `CHAINCACHE` — un URL che *sembra* solo avere la forma di
   chaincache ma non risponde come tale (una coincidenza, un vero endpoint RPC di terze parti il
   cui percorso termina per caso con `.../rpc`, o chaincache temporaneamente non raggiungibile)
   viene trattato come `DIRECT_RPC`, mai lasciato in uno stato "sconosciuto". Una corrispondenza
   confermata è seguita da una seconda chiamata, con impegno ragionevole,
   `GET <managementUrl>/api/chains` per i conteggi dei provider upstream di quel workload (vedi
   [Cosa viene sondato e mostrato](#cosa-viene-sondato-e-mostrato)) — un fallimento qui degrada
   solo a conteggi dei nodi mancanti, non annulla mai la corrispondenza confermata.
3. Un job in background riesegue questo controllo per ogni nodo abilitato circa una volta al
   minuto, in entrambe le direzioni — un nodo `DIRECT_RPC` il cui URL inizia a rispondere come
   chaincache viene promosso; un nodo che non riesce a raggiungere chaincache tre volte
   consecutive, o è raggiungibile ma non elenca più il `remoteChainKey` atteso, ritorna a
   `DIRECT_RPC`. Questa isteresi a tre fallimenti (immediata su un "non serve più questa catena"
   confermato, tollerante a un singolo intoppo transitorio) esiste specificamente perché un
   singolo controllo fallito non interrompa lo stream di eventi durevoli di una catena.
   Un'azione manuale "ricontrolla ora" esiste per nodo per un operatore che non vuole attendere
   il prossimo ciclo.
4. Un URL che risolve a un'autorità che ha appena fallito un controllo viene saltato per un'ora
   (una cache negativa) invece di essere ricontrollato a ogni ciclo — questo è ciò che impedisce
   a un vero endpoint RPC di terze parti che corrisponde per caso al pattern `/<key>/rpc` di
   essere colpito da un controllo in uscita ripetuto e rumoroso.

Non esiste da nessuna parte nel prodotto un selettore di tipo — un operatore non può dichiarare il
tipo di un nodo, solo scoprire quale sia.

`ChainConfig.finalitySource` (`RPC_SELF_PROBE` | `CHAINCACHE`) segue lo stesso principio: viene
ricalcolata automaticamente in base al fatto che la catena abbia attualmente un nodo `CHAINCACHE`
abilitato, ogni volta che un nodo viene aggiunto, rimosso, abilitato, disabilitato o ri-rilevato.
Non esiste nemmeno un campo che un operatore possa impostare direttamente.

## Autenticazione

Il default proprio di chaincache è `chaincache.auth.enabled: true`, che richiede un token bearer
con ruolo `USER`/`ADMIN`/`OPERATOR` su `/api/**` e `/{chain}/api/**` (il percorso del proxy RPC
stesso, `/{chain}/rpc` e `/{chain}/ws`, resta aperto per il default proprio di chaincache —
`chaincache.auth.rpc-enabled: false` — poiché quel percorso è pensato per essere raggiungibile
come qualsiasi endpoint RPC). Registerwerk genera il proprio token HS256 di breve durata
(`chain.internal.ChaincacheTokenFactory`, un token di 5 minuti con `roles: ["OPERATOR"]`, messo
in cache fino a poco prima della scadenza) da `registerwerk.chaincache.jwt-secret` — un segreto
**distinto** da `JWT_DEV_SECRET`; non riutilizzare mai qui la chiave di firma di login operatore
propria di Registerwerk, poiché chiunque detenga una credenziale chaincache potrebbe allora anche
falsificare sessioni operatore di Registerwerk. Quel segreto deve corrispondere al
`chaincache.jwt.secret` proprio del workload di destinazione.

Se il segreto manca o è errato, i controlli di capacità e le connessioni allo stream durevole
falliscono con 401/403. Questo non viene deliberatamente trattato come "non è un'istanza
chaincache" — un nodo che risponde con 401/403 resta esattamente come era (un nodo `CHAINCACHE`
confermato resta `CHAINCACHE`, un nodo appena aggiunto resta `DIRECT_RPC`), e un unico log `WARN`
indica la correzione (`registerwerk.chaincache.jwt-secret`) invece di ripeterla a ogni ciclo di
controllo. Un segreto errato o mancante non può mai riscrivere silenziosamente il modello dati.

`registerwerk_chaincache_capability_probe_failures_total{management_url,reason}` distingue questo
caso (`reason="unauthorized"`) da `chain_missing` (raggiungibile, non serve questa catena) e
`unreachable` (rete/timeout/5xx) — vedi [Osservabilità](#osservabilita) sotto.

## Cosa viene sondato e mostrato

Una volta rilevato, `GET /api/capabilities` viene ri-sondato secondo lo stesso calendario del
ri-rilevamento del tipo, e la sua risposta viene memorizzata come `capabilities` del nodo: modello
di finalità, profondità di conferma safe/finalized, quali namespace RPC sono configurati, se
`debug_traceBlockByHash` sia effettivamente disponibile upstream, disponibilità dello stream
durevole, e la postura Kafka propria di quel workload. Una corrispondenza confermata integra
inoltre i conteggi dei provider upstream da `GET /api/chains`
(`configuredNodeCount`/`availableNodeCount` — quanti provider RPC upstream quel workload ha
configurato per la catena, e quanti rispondono attualmente). Il pannello espandibile dell'elenco
nodi dell'operatore, su una riga di tipo chaincache, mostra tutto questo — più quale workload
(`managementUrl` + `remoteChainKey`) serva effettivamente la catena, e se Registerwerk mantenga
attualmente una connessione di eventi durevoli attiva verso di esso — direttamente accanto ai dati
di salute più semplici di un nodo diretto; il divario di capacità è deliberato e visibile, mai
nascosto.

## Lo stream durevole

Quando la `finalitySource` di una catena è `CHAINCACHE`,
`blockchain.internal.ChaincacheDurableStreamManager` apre un WebSocket persistente verso
`<managementUrl>/<remoteChainKey>/ws` ed emette `chaincache_subscribeDurable` con un `consumerId`
nella forma `registerwerk:<instanceId>:<remoteChainKey>` — stabile attraverso i riavvii della
stessa istanza Registerwerk (così il suo cursore riprende invece di ricominciare da capo),
distinto per replica tramite `registerwerk.chaincache.instance-id` (così le repliche non
condividono un cursore e non dividono silenziosamente lo stream di eventi tra loro). **Non c'è
nessuna chiamata di ripresa separata da fare**: chaincache persiste il cursore di ogni consumatore
lato server (`durable_consumer_cursor`, indicizzato per `consumerId` + stream) e lo riprende
automaticamente non appena lo stesso `consumerId` si iscrive di nuovo — `chaincache_resume` è un
alias di dispatch per la stessa chiamata `subscribeDurable`, non un passo distinto che
Registerwerk debba emettere da sé.

Ogni evento durevole porta una `sequence` monotona crescente, un `kind` (`BLOCK` o `RETRACTION`)
e — per una ritrattazione — un `retractsEventId` (`"block:..."` contro `"log:..."`, solo il
livello blocco conta per il tracciamento della catena canonica) più un `payload` che porta la
correzione reale: `commonAncestor`, l'altezza a partire dalla quale tutto ciò che segue è
orfano. Questo è ciò che rende il rilevamento di reorg di chaincache senza lacune e push-based:
Registerwerk non deve sondare per una ritrattazione, chaincache la segnala nel momento in cui
avviene, in modo ripetibile (un evento mancato è recuperabile alla riconnessione tramite il
cursore persistito, mai perso silenziosamente). Gli eventi vengono confermati con `chaincache_ack`
man mano che vengono elaborati.

Il percorso di rilevamento reorg puramente RPC (`ReorgGuard`, descritto in
[Resilienza degli indicizzatori](../indexers/resilience.md)) resta intatto e continua a funzionare
per ogni catena `RPC_SELF_PROBE` — lo stream durevole è un segnale aggiuntivo, di fedeltà
superiore, per le catene che lo hanno adottato, non un sostituto per quelle che non lo hanno
fatto. Una catena `CHAINCACHE` ottiene anche la propria riverifica della finestra di finalità
dall'endpoint proprio di chaincache `GET /{remoteChainKey}/api/blocks/{number}/finality` invece
che dal polling RPC — qualsiasi fallimento qui (404, 5xx, timeout, 401) ricade sul self-probing
RPC invece di fabbricare un falso reorg, così una chaincache temporaneamente irraggiungibile
degrada il tracciamento della finalità invece di romperlo del tutto.

## Configurazione minima

Lo stack demo esegue due workload chaincache fianco a fianco con un semplice nodo RPC diretto,
così l'elenco nodi dell'operatore mostra un confronto reale fin da subito: `chaincache-sepolia`
serve la devnet Anvil locale (`DEPTH_BASED`, safe=3/finalized=6), e `chaincache-base` serve veri
provider RPC pubblici Base Sepolia (`TAG_BASED`, `required-provider-agreement: 2`). L'intera
configurazione lato chaincache per il workload devnet locale è:

```yaml
# Servizio Compose proprio di chaincache-sepolia
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

Due cose vale la pena segnalare esplicitamente, entrambe veri bug che questa integrazione ha
incontrato e risolto:

!!! note "La chiave di catena deve essere una singola parola"
    Il binding permissivo delle variabili d'ambiente di Spring Boot per le proprietà
    `Map<String, ComplexBean>` (che è ciò che è `chaincache.chains.<key>.*`) non lega in modo
    affidabile una chiave map multi-parola/con più underscore (`sepolia-devnet`, `local_devnet`)
    dalle sole variabili d'ambiente — solo una chiave di una singola parola (`sepolia`, `anvil`)
    si lega correttamente. Questa è una vera limitazione del `Binder` di Spring Boot, non un bug
    di chaincache; le chiavi di catena dello stack demo sono deliberatamente parole singole per
    questo motivo.

!!! note "expected-chain-id è esadecimale, non decimale"
    `RpcProviderIdentityValidator` confronta `expected-chain-id` con la risposta `eth_chainId`
    dell'upstream, che è sempre esadecimale con prefisso `0x`. Configurarlo come stringa
    decimale (`"11155111"` invece di `"0xaa36a7"`) fa sì che non possa mai corrispondere, e con
    `identity-validation-required: true` (default proprio di chaincache) il workload non si
    avvierà affatto finché non viene corretto.

Lato Registerwerk: nulla. Aggiungere un nodo RPC con URL
`http://chaincache-sepolia:8080/sepolia/rpc` tramite il normale flusso di aggiunta nodo (o
lasciare che `DemoDataSeeder` lo semini, come fa lo stack demo) — tipo, `finalitySource` e
capabilities seguono automaticamente entro un ciclo di rilevamento non appena quel workload è
raggiungibile e autenticato.

## Deployment: infrastruttura gestita condivisa, release indipendenti

Il [chart Helm](https://github.com/makibytes/chaincache) proprio di chaincache distribuisce una
release per catena, ciascuna puntata verso un'istanza Cloud SQL condivisa e una `HTTPRoute`
Gateway API Kubernetes condivisa — la stessa forma che lo stack demo riproduce localmente con
un'unica istanza `chaincache-postgres` condivisa dietro due container. Il chart proprio di
Registerwerk **non** distribuisce chaincache (è un prodotto versionato e distribuito
indipendentemente); porta un blocco di valori `chaincache:` che nomina gli URL dei workload per
catena a cui connettersi e un riferimento al segreto JWT condiviso, più una regola di uscita
`NetworkPolicy` che consente il traffico verso il namespace di chaincache quando entrambi i chart
girano nello stesso cluster. Un esempio concreto: due release Helm chaincache
(`chaincache-sepolia`, `chaincache-base`) in un namespace `chaincache`, entrambe puntate verso
un'istanza Cloud SQL tramite lo stesso pattern di sidecar `Cloud SQL Auth Proxy` che documenta il
README proprio di chaincache, entrambe scrappate dalla stessa istanza Prometheus che lo stack di
monitoraggio di Registerwerk esegue già; l'elenco di valori `chaincache.workloads` del chart
proprio di Registerwerk nomina entrambi i `managementUrl`, così un bootstrap equivalente a
`DemoDataSeeder` (o un "aggiungi nodo" manuale) può collegarli.

### Aggiornamenti di versione maggiore Flyway / PostgreSQL

Le migrazioni proprie di chaincache sono un'unica baseline di installazione pulita più file
`V{n}` incrementali, la stessa convenzione che usa Registerwerk. Un salto di versione maggiore di
PostgreSQL (lo stack demo è passato da 15 a 18.6) non è qualcosa che Flyway gestisce da solo —
serve o un `pg_upgrade` sul posto o un dump/restore, e il percorso della directory dati cambia
esso stesso tra i tag immagine Postgres maggiori (`/var/lib/postgresql/data` contro
`/var/lib/postgresql` dalla versione 18 in poi) — montare il vecchio volume al percorso atteso
dalla nuova immagine avvia silenziosamente un cluster nuovo e vuoto invece di fallire
rumorosamente. Trattare un salto di versione maggiore PostgreSQL come un proprio passo di
migrazione deliberato, mai come un effetto collaterale di un bump del tag immagine.

### Checklist di produzione

- `chaincache.runtime.identity-validation-required: true`, con `expected-chain-id` impostato
  come esadecimale con prefisso `0x` per ogni catena configurata — un'identità di catena non
  validata o mal configurata significa che un workload potrebbe servire silenziosamente i dati
  della catena sbagliata.
- `chaincache.cache.local-snapshot-enabled: false` e
  `chaincache.request.local-stats-enabled: false` su ogni replica — la postura che questa
  integrazione riproduce localmente nello stack demo, corrispondente a ciò di cui ha bisogno un
  deployment replicato orizzontalmente (lo stato locale per replica non ha senso non appena c'è
  più di un pod dietro un workload).
- `chaincache.auth.enabled: true`, con un `registerwerk.chaincache.jwt-secret` che **non** sia
  lo stesso valore di `JWT_DEV_SECRET` o di qualsiasi altra chiave di firma di Registerwerk.
- L'endpoint di healthcheck usato per l'orchestrazione dovrebbe essere
  `/actuator/health/readiness`, non liveness — un workload può essere vivo ma non ancora pronto
  a servire (ad es. mentre sta ancora stabilendo le proprie connessioni RPC upstream), e
  instradare traffico verso di esso durante quella finestra produce fallimenti di controllo
  evitabili lato Registerwerk.
- `chaincache.auth.prometheus-public: true` solo quando lo scrape avviene attraverso un confine
  di rete di cui Prometheus si fida già (ad es. la stessa rete interna al cluster, mai una porta
  pubblicata) — questo esiste specificamente perché `/actuator/prometheus` non richieda il
  token bearer proprio di Registerwerk, che Prometheus non porta.

## Osservabilità

L'istanza Prometheus propria di Registerwerk scrappa direttamente entrambi i workload chaincache
(etichetta `chaincache_workload` che li distingue) e allerta su workload non disponibile, tasso di
errore RPC upstream elevato, flapping WebSocket e raffiche di reorg usando le metriche Micrometer
proprie di chaincache (`chaincache.rpc.errors`, `chaincache.rpc.ws.disconnects`,
`chaincache.chain.reorganizations`, `chaincache.rpc.node.latency` — quest'ultima espone solo
`_count`/`_sum`, nessun istogramma di percentile, quindi allertare su di essa significa latenza
media, non p95). Lato Registerwerk, `registerwerk_chaincache_stream_connected{chain}` e
`registerwerk_chaincache_stream_last_event_timestamp_seconds{chain}` sostengono altre due allerte
(stream disconnesso da 5+ minuti; nessun evento ricevuto da 10+ minuti nonostante una connessione
aperta — il secondo caso di solito significa che la catena stessa ha smesso di produrre blocchi,
non che l'integrazione sia rotta). Una dashboard Grafana
(`monitoring/grafana/dashboards/chaincache.json`) copre, per workload, salute/latenza/reorg/
disconnessioni upstream, più la riga di salute dello stream lato Registerwerk.

## Deep-link verso chaincheck

[chaincheck](https://github.com/makibytes/chaincheck) è il terzo prodotto gemello — un monitor
indipendente della flotta di nodi, non integrato in nessuno degli altri due. Quando
`environment.chaincheckUrl` è configurato nel frontend operatore, il pannello di capacità
espandibile di ogni nodo di tipo chaincache include un link "Visualizza in chaincheck" verso di
esso. Questo è puramente un deep link — Registerwerk non interroga l'API di chaincheck e non
dipende dalla sua raggiungibilità per nulla mostrato nell'elenco nodi stesso.
