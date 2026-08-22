---
title: chaincache-Integration
---

# chaincache-Integration

[chaincache](https://github.com/makibytes/chaincache) ist ein Schwesterprodukt: ein RPC-Gateway
mit eigener Canonical-Chain-/Finalitätsverfolgung. Registerwerk kann sich mit einer
chaincache-Instanz genauso verbinden wie mit jedem anderen RPC-Knoten — als URL in der
Knotenliste —, erhält dabei aber push-basierte, lückenlose Reorg-Rücknahmen und eine echte
`SAFE`-Stufe statt der groben, poll-basierten Finalität einer direkten Knotenverbindung. Diese
Seite beschreibt, was dieses Upgrade tatsächlich ist, und die (bewusst minimale) Konfiguration,
die es benötigt.

## Ein chaincache-Workload pro Chain

Das Deployment-Modell von chaincache ist **ein Spring-Boot-Prozess pro Chain**, nicht eine einzige
Instanz, die alle Chains bedient — `chaincache.runtime.allow-multiple-chains: false` ist der
eigene Standard von chaincache, und der Start wird mit null oder mehr als einer konfigurierten
Chain verweigert. „Multi-Chain" bedeutet N Workloads, jeder ein eigenes Deployable, jeder
zuständig für genau eine Chain. Geteilte Infrastruktur — PostgreSQL, Monitoring, Ingress — ist so
konzipiert, dass sie über diese Workloads hinweg geteilt werden kann: jede Zeile, die ein Workload
schreibt, ist Chain-gebunden, sodass zwei Workloads auf dieselbe PostgreSQL-Instanz zeigen können,
ohne zu kollidieren. Der Demo-Stack setzt das real um: `chaincache-sepolia` und `chaincache-base`
sind zwei unabhängige Container, die sich eine `chaincache-postgres`-Instanz teilen.

Das ändert, was „Registerwerk mit chaincache verbinden" auf Netzwerkebene bedeutet: es gibt keinen
einzelnen chaincache-Hostnamen, auf den jede Chain ihren Knoten zeigen lässt. Jeder `RpcNode` vom
Typ `CHAINCACHE` zeigt auf genau den Workload, der *seine* Chain bedient — `managementUrl` ist der
eigene Host:Port dieses Workloads, und `remoteChainKey` ist der Chain-Key, mit dem dieser Workload
konfiguriert wurde (fast immer der einzige Chain-Key, den er kennt, da jeder Workload genau eine
Chain bedient).

Kafka ist ausdrücklich **nicht** Teil dieser Integrationsfläche. chaincache hat sein eigenes,
optionales Kafka-Fan-out für andere nachgelagerte Konsumenten (`chaincache.kafka.enabled`), aber
Registerwerk konsumiert es nie — die beiden Protokolle, die Registerwerk tatsächlich mit chaincache
spricht, sind der JSON-RPC/web3j-Proxy (`/{chain}/rpc`) und chaincaches eigener
Durable-Event-WebSocket (`/{chain}/ws`). Wenn `chaincache.kafka.enabled: true` auf einem Workload
gesetzt ist, bedient das einen anderen Konsumenten, nicht Registerwerk.

## Warum das ein Knotentyp ist, keine Konfigurationsdatei

Ein früheres Design behandelte die chaincache-Nutzung als „keine Code-Änderung in Registerwerk
nötig — es ist nur eine URL in einer Tabelle", nach der Theorie, Unsichtbarkeit sei der Punkt. Für
ein Produkt, dessen Zweck unter anderem darin besteht zu zeigen, was ein Register durch chaincache
gewinnt, ist Unsichtbarkeit das Gegenteil des Punkts: `RpcNode` hat einen `kind`
(`DIRECT_RPC` | `CHAINCACHE`), und die Betreiberoberfläche zeigt pro Verbindung an, welche
Garantien sie tatsächlich bietet. Ein direkter RPC-Knoten liefert poll-basierte, grobe Finalität,
die einen kurzlebigen Reorg verpassen kann und ohne `safe`-Block-Tag keine echte `SAFE`-Stufe hat;
eine chaincache-Verbindung bietet zusätzlich push-basierte, lückenlose, wiederholbare Rücknahmen
und eine echte `SAFE`-Stufe.

## Auto-Erkennung — es gibt nichts von Hand zu konfigurieren

Ein Betreiber fügt einen Knoten immer auf die gleiche Weise hinzu, unabhängig davon, was er sich
später als herausstellt: URL einfügen, optionales Label. Ob diese URL eine chaincache-Verbindung
ist, wird automatisch erkannt, nicht deklariert:

1. Die URL wird gegen chaincaches Routing-Konvention `/<chainKey>/rpc` geprüft — eine so geformte
   URL ergibt eine Kandidaten-`managementUrl` (Schema+Host+Port) und einen Kandidaten-
   `remoteChainKey` (das letzte Pfadsegment).
2. Der Kandidat wird mit einem echten `GET <managementUrl>/api/capabilities`-Aufruf verifiziert,
   geprüft auf einen Eintrag, dessen `chainKey` passt. Nur bei einem Treffer wird der Knoten
   tatsächlich als `CHAINCACHE` erfasst — eine URL, die nur *aussieht* wie chaincache-geformt,
   aber nicht so antwortet (ein Zufall, ein echter Drittanbieter-RPC-Endpunkt, dessen Pfad
   zufällig auf `.../rpc` endet, oder chaincache, das vorübergehend nicht erreichbar ist), wird
   als `DIRECT_RPC` behandelt, nicht in einem „unbekannten" Zustand belassen. Auf einen
   bestätigten Treffer folgt ein zweiter, best-effort `GET <managementUrl>/api/chains`-Aufruf für
   die Upstream-Provider-Zähler dieses Workloads (siehe
   [Was geprüft und angezeigt wird](#was-gepruft-und-angezeigt-wird)) — ein Fehlschlag dabei
   führt nur zu fehlenden Knotenzählern, macht den bestätigten Treffer nie rückgängig.
3. Ein Hintergrundjob wiederholt diese Prüfung für jeden aktivierten Knoten etwa einmal pro
   Minute, in beide Richtungen — ein `DIRECT_RPC`-Knoten, dessen URL beginnt, als chaincache zu
   antworten, wird hochgestuft; ein Knoten, der chaincache dreimal in Folge nicht erreicht, oder
   erreichbar ist, aber den erwarteten `remoteChainKey` nicht mehr listet, fällt auf `DIRECT_RPC`
   zurück. Diese Drei-Fehlschläge-Hysterese (sofort bei einem bestätigten „bedient diese Chain
   nicht mehr", tolerant gegenüber einem einzelnen vorübergehenden Ausfall) existiert speziell
   damit ein verlorener Prüfversuch nicht den Durable-Event-Stream einer Chain abreißt. Für einen
   Betreiber, der nicht auf den nächsten Takt warten will, gibt es pro Knoten eine manuelle
   „Jetzt neu prüfen"-Aktion.
4. Eine URL, die auf eine Autorität auflöst, die gerade eine Prüfung nicht bestanden hat, wird
   für eine Stunde übersprungen (ein negativer Cache), statt bei jedem Takt erneut geprüft zu
   werden — das verhindert, dass ein echter Drittanbieter-RPC-Endpunkt, der zufällig dem
   `/<key>/rpc`-Muster entspricht, wiederholt mit rauschenden ausgehenden Prüfversuchen belastet
   wird.

Es gibt nirgends im Produkt eine Auswahl für den Knotentyp — ein Betreiber kann den Typ eines
Knotens nicht deklarieren, sondern nur herausfinden, welcher er ist.

`ChainConfig.finalitySource` (`RPC_SELF_PROBE` | `CHAINCACHE`) folgt demselben Prinzip: sie wird
automatisch neu berechnet, ausgehend davon, ob die Chain gerade einen aktivierten
`CHAINCACHE`-Knoten hat, jedes Mal wenn ein Knoten hinzugefügt, entfernt, aktiviert, deaktiviert
oder neu erkannt wird. Auch dafür gibt es kein Feld, das ein Betreiber direkt setzen könnte.

## Authentifizierung

Der eigene Standard von chaincache ist `chaincache.auth.enabled: true`, was ein Bearer-Token mit
Rolle `USER`/`ADMIN`/`OPERATOR` auf `/api/**` und `/{chain}/api/**` verlangt (der RPC-Proxy-Pfad
selbst, `/{chain}/rpc` und `/{chain}/ws`, bleibt nach chaincaches eigenem Standard offen —
`chaincache.auth.rpc-enabled: false` —, da dieser Pfad genauso erreichbar sein soll wie jeder
andere RPC-Endpunkt). Registerwerk erzeugt sein eigenes kurzlebiges HS256-Token
(`chain.internal.ChaincacheTokenFactory`, ein 5-Minuten-Token mit `roles: ["OPERATOR"]`,
zwischengespeichert bis kurz vor Ablauf) aus `registerwerk.chaincache.jwt-secret` — ein Secret, das
sich **unterscheidet** von `JWT_DEV_SECRET`; niemals hier den eigenen
Betreiber-Login-Signierschlüssel von Registerwerk wiederverwenden, da sonst jeder, der ein
chaincache-Credential besitzt, auch Registerwerk-Betreiber-Sitzungen fälschen könnte. Dieses Secret
muss mit dem `chaincache.jwt.secret` des Ziel-Workloads übereinstimmen.

Fehlt das Secret oder ist es falsch, schlagen Capability-Prüfungen und Durable-Stream-Verbindungen
mit 401/403 fehl. Das wird bewusst **nicht** als „das ist keine chaincache-Instanz" behandelt — ein
Knoten, der mit 401/403 antwortet, bleibt genau so, wie er war (ein bestätigter `CHAINCACHE`-Knoten
bleibt `CHAINCACHE`, ein neu hinzugefügter Knoten bleibt `DIRECT_RPC`), und ein einziges
`WARN`-Log nennt die Lösung (`registerwerk.chaincache.jwt-secret`), statt sie bei jedem Prüftakt
zu wiederholen. Ein falsches oder fehlendes Secret kann das Datenmodell nie stillschweigend
verändern.

`registerwerk_chaincache_capability_probe_failures_total{management_url,reason}` unterscheidet
diesen Fall (`reason="unauthorized"`) von `chain_missing` (erreichbar, bedient diese Chain nicht)
und `unreachable` (Netzwerk/Timeout/5xx) — siehe [Observability](#observability-metriken-und-alarme) unten.

## Was geprüft und angezeigt wird

Nach der Erkennung wird `GET /api/capabilities` im selben Takt wie die Typ-Neuerkennung erneut
geprüft, und die Antwort wird als `capabilities` des Knotens gespeichert: Finalitätsmodell,
Safe-/Finalized-Bestätigungstiefen, welche RPC-Namespaces konfiguriert sind, ob
`debug_traceBlockByHash` upstream tatsächlich verfügbar ist, Durable-Stream-Verfügbarkeit und die
eigene Kafka-Relay-Haltung dieses Workloads. Ein bestätigter Treffer bindet zusätzlich die
Upstream-Provider-Zähler von `GET /api/chains` ein (`configuredNodeCount`/`availableNodeCount` —
wie viele Upstream-RPC-Provider dieser Workload für die Chain konfiguriert hat und wie viele
gerade antworten). Das ausklappbare Panel der Betreiber-Knotenliste bei einer chaincache-Zeile
zeigt all das — plus welcher Workload (`managementUrl` + `remoteChainKey`) die Chain tatsächlich
bedient und ob Registerwerk gerade eine aktive Durable-Event-Verbindung dorthin hält — direkt
neben den schlichteren Gesundheitsdaten eines direkten Knotens; die Fähigkeitslücke ist bewusst
und sichtbar, nicht verborgen.

## Der Durable Stream

Wenn `finalitySource` einer Chain `CHAINCACHE` ist, öffnet
`blockchain.internal.ChaincacheDurableStreamManager` eine dauerhafte WebSocket-Verbindung zu
`<managementUrl>/<remoteChainKey>/ws` und sendet `chaincache_subscribeDurable` mit einer
`consumerId` der Form `registerwerk:<instanceId>:<remoteChainKey>` — stabil über Neustarts
derselben Registerwerk-Instanz hinweg (damit ihr Cursor fortgesetzt wird, statt von vorn zu
beginnen), unterschieden pro Replika über `registerwerk.chaincache.instance-id` (damit Repliken
nicht einen Cursor teilen und den Event-Stream stillschweigend zwischen sich aufsplitten). **Es
gibt keinen separaten Resume-Aufruf zu tätigen**: chaincache speichert den Cursor jedes Konsumenten
serverseitig (`durable_consumer_cursor`, geschlüsselt nach `consumerId` + Stream) und setzt ihn
automatisch fort, sobald sich dieselbe `consumerId` erneut anmeldet — `chaincache_resume` ist ein
Dispatch-Alias für denselben `subscribeDurable`-Aufruf, kein eigener Schritt, den Registerwerk
selbst auslösen müsste.

Jedes Durable Event trägt eine monoton steigende `sequence`, eine `kind` (`BLOCK` oder
`RETRACTION`) und — bei einer Rücknahme — eine `retractsEventId` (`"block:..."` vs. `"log:..."`,
nur die Block-Ebene ist für die Canonical-Chain-Verfolgung relevant) sowie eine `payload` mit der
eigentlichen Korrektur: `commonAncestor`, die Höhe, ab der alles danach als verwaist gilt. Das
macht die Reorg-Erkennung von chaincache lückenlos und push-basiert: Registerwerk muss nicht auf
eine Rücknahme pollen, chaincache meldet sie in dem Moment, in dem sie geschieht, wiederholbar (ein
verpasstes Event lässt sich beim Reconnect über den gespeicherten Cursor nachholen, geht nicht
stillschweigend verloren). Events werden mit `chaincache_ack` bestätigt, sobald sie verarbeitet
sind.

Der reine RPC-Reorg-Erkennungspfad (`ReorgGuard`, beschrieben in
[Indexer-Resilienz](../indexers/resilience.md)) bleibt unangetastet und läuft weiterhin für jede
`RPC_SELF_PROBE`-Chain — der Durable Stream ist ein zusätzliches, hochwertigeres Signal für Chains,
die sich dafür entschieden haben, kein Ersatz für Chains, die das nicht getan haben. Eine
`CHAINCACHE`-Chain bezieht ihre Neuverifikation des Finalitätsfensters außerdem aus chaincaches
eigenem `GET /{remoteChainKey}/api/blocks/{number}/finality`-Endpunkt statt aus RPC-Polling —
jeder Fehlschlag dabei (404, 5xx, Timeout, 401) fällt auf RPC-Self-Probing zurück, statt einen
falschen Reorg zu erzeugen, sodass eine vorübergehend nicht erreichbare chaincache-Instanz die
Finalitätsverfolgung nur graduell verschlechtert, statt sie ganz zu brechen.

## Minimale Konfiguration

Der Demo-Stack betreibt zwei chaincache-Workloads nebeneinander sowie einen schlichten direkten
RPC-Knoten, sodass die Betreiber-Knotenliste sofort einen echten Vergleich zeigt:
`chaincache-sepolia` bedient das lokale Anvil-Devnet (`DEPTH_BASED`, safe=3/finalized=6), und
`chaincache-base` bedient echte öffentliche Base-Sepolia-RPC-Provider (`TAG_BASED`,
`required-provider-agreement: 2`). Die gesamte chaincache-seitige Konfiguration für den lokalen
Devnet-Workload ist:

```yaml
# chaincache-sepolias eigener Compose-Service
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

Zwei Dinge sind es wert, explizit genannt zu werden — beides echte Bugs, auf die diese Integration
gestoßen ist und die sie behoben hat:

!!! note "Der Chain-Key muss ein einzelnes Wort sein"
    Das nachsichtige Env-Var-Binding von Spring Boot für `Map<String, ComplexBean>`-Properties
    (was `chaincache.chains.<key>.*` ist) bindet einen mehrteiligen/mit Unterstrichen versehenen
    Map-Key (`sepolia-devnet`, `local_devnet`) nicht zuverlässig allein aus Umgebungsvariablen —
    nur ein einzelnes Wort (`sepolia`, `anvil`) bindet korrekt. Das ist eine echte Einschränkung
    des Spring-Boot-`Binder`, kein chaincache-Bug; die Chain-Keys des Demo-Stacks sind deshalb
    bewusst einzelne Wörter.

!!! note "expected-chain-id ist hexadezimal, nicht dezimal"
    `RpcProviderIdentityValidator` vergleicht `expected-chain-id` gegen die
    `eth_chainId`-Antwort des Upstreams, die immer `0x`-präfixiertes Hexadezimal ist. Wird sie
    als dezimale Zeichenkette konfiguriert (`"11155111"` statt `"0xaa36a7"`), kann sie niemals
    übereinstimmen, und mit `identity-validation-required: true` (chaincaches eigenem Standard)
    startet der Workload dann gar nicht erst.

Registerwerk-seitig: nichts. Einen RPC-Knoten mit der URL
`http://chaincache-sepolia:8080/sepolia/rpc` über den normalen Knoten-hinzufügen-Ablauf anlegen
(oder ihn von `DemoDataSeeder` seeden lassen, wie der Demo-Stack es tut) — Typ, `finalitySource`
und Capabilities folgen automatisch innerhalb eines Erkennungstakts, sobald dieser Workload
erreichbar und authentifiziert ist.

## Deployment: geteilte verwaltete Infrastruktur, unabhängige Releases

Der eigene [Helm-Chart](https://github.com/makibytes/chaincache) von chaincache deployt ein
Release pro Chain, jedes zeigend auf eine geteilte Cloud-SQL-Instanz und eine geteilte
Kubernetes-Gateway-API-`HTTPRoute` — dieselbe Form, die der Demo-Stack lokal mit einer geteilten
`chaincache-postgres`-Instanz hinter zwei Containern durchspielt. Der eigene Chart von
Registerwerk deployt chaincache **nicht** (es ist ein unabhängig versioniertes, unabhängig
deploytes Produkt); er trägt einen `chaincache:`-Values-Block, der die Workload-URLs pro Chain
benennt, mit denen sich verbunden werden soll, und eine Referenz auf das geteilte JWT-Secret, plus
eine `NetworkPolicy`-Egress-Regel, die Traffic in chaincaches Namespace erlaubt, wenn beide Charts
im selben Cluster laufen. Ein durchgespieltes Beispiel: zwei chaincache-Helm-Releases
(`chaincache-sepolia`, `chaincache-base`) in einem `chaincache`-Namespace, beide zeigend auf eine
Cloud-SQL-Instanz über denselben `Cloud SQL Auth Proxy`-Sidecar, den chaincaches eigenes README
dokumentiert, beide gescraped von derselben Prometheus-Instanz, die der Monitoring-Stack von
Registerwerk ohnehin betreibt; die `chaincache.workloads`-Values-Liste des eigenen Charts von
Registerwerk benennt beide `managementUrl`s, sodass ein `DemoDataSeeder`-äquivalentes Bootstrapping
(oder ein manuelles „Knoten hinzufügen") sie verdrahten kann.

### Flyway-/PostgreSQL-Hauptversions-Upgrades

Die eigenen Migrationen von chaincache sind eine einzelne Clean-Install-Baseline plus
inkrementelle `V{n}`-Dateien, dieselbe Konvention, die Registerwerk verwendet. Ein
PostgreSQL-Hauptversionssprung (der Demo-Stack wechselte von 15 auf 18.6) ist nichts, was Flyway
von selbst handhabt — er braucht entweder ein `pg_upgrade` an Ort und Stelle oder ein
Dump/Restore, und der Datenverzeichnispfad selbst ändert sich zwischen Postgres-Image-Hauptversion
(`/var/lib/postgresql/data` vs. `/var/lib/postgresql` ab Version 18) — das alte Volume am vom
neuen Image erwarteten Pfad zu mounten, startet stillschweigend einen frischen leeren Cluster,
statt laut zu scheitern. Einen PostgreSQL-Hauptversionssprung als eigenen bewussten
Migrationsschritt behandeln, nie als Nebeneffekt eines Image-Tag-Bumps.

### Produktions-Checkliste

- `chaincache.runtime.identity-validation-required: true`, mit `expected-chain-id` gesetzt als
  `0x`-präfixiertes Hexadezimal für jede konfigurierte Chain — eine unvalidierte oder falsch
  konfigurierte Chain-Identität bedeutet, dass ein Workload stillschweigend die Daten der
  falschen Chain bedienen könnte.
- `chaincache.cache.local-snapshot-enabled: false` und
  `chaincache.request.local-stats-enabled: false` auf jeder Replika — die Haltung, die diese
  Integration lokal im Demo-Stack durchspielt, passend zu dem, was ein horizontal repliziertes
  Deployment braucht (lokaler Zustand pro Replika ergibt keinen Sinn, sobald mehr als ein Pod
  hinter einem Workload steht).
- `chaincache.auth.enabled: true`, mit einem `registerwerk.chaincache.jwt-secret`, das **nicht**
  derselbe Wert wie `JWT_DEV_SECRET` oder ein anderer Signierschlüssel von Registerwerk ist.
- Der für Orchestrierung verwendete Healthcheck-Endpunkt sollte `/actuator/health/readiness`
  sein, nicht Liveness — ein Workload kann am Leben sein, aber noch nicht bereit, zu bedienen
  (z. B. während er noch seine Upstream-RPC-Verbindungen aufbaut), und Traffic in dieses Fenster
  zu routen erzeugt vermeidbare Prüf-Fehlschläge auf Registerwerk-Seite.
- `chaincache.auth.prometheus-public: true` nur, wenn der Scrape über eine Netzwerkgrenze
  geschieht, der Prometheus bereits vertraut (z. B. dasselbe Cluster-interne Netzwerk, nie ein
  veröffentlichter Port) — das existiert speziell, damit `/actuator/prometheus` kein
  Bearer-Token von Registerwerk benötigt, das Prometheus nicht mitführt.

## Observability (Metriken und Alarme)

Die eigene Prometheus-Instanz von Registerwerk scraped beide chaincache-Workloads direkt
(`chaincache_workload`-Label unterscheidet sie) und alarmiert bei ausgefallenem Workload, erhöhter
Upstream-RPC-Fehlerrate, WebSocket-Flapping und Reorg-Bursts anhand der eigenen
Micrometer-Metriken von chaincache (`chaincache.rpc.errors`, `chaincache.rpc.ws.disconnects`,
`chaincache.chain.reorganizations`, `chaincache.rpc.node.latency` — letztere gibt nur
`_count`/`_sum` aus, kein Perzentil-Histogramm, Alarmierung darauf bedeutet also
Durchschnittslatenz, nicht p95). Auf Registerwerk-Seite untermauern
`registerwerk_chaincache_stream_connected{chain}` und
`registerwerk_chaincache_stream_last_event_timestamp_seconds{chain}` zwei weitere Alarme (Stream
seit 5+ Minuten getrennt; seit 10+ Minuten kein Event empfangen trotz offener Verbindung — der
zweite Fall bedeutet meist, dass die Chain selbst aufgehört hat, Blöcke zu produzieren, nicht dass
die Integration defekt ist). Ein Grafana-Dashboard
(`monitoring/grafana/dashboards/chaincache.json`) deckt pro Workload
Upstream-Gesundheit/-Latenz/-Reorgs/-Disconnects sowie die Registerwerk-seitige
Stream-Gesundheitszeile ab.

## Deep-Link zu chaincheck

[chaincheck](https://github.com/makibytes/chaincheck) ist das dritte Schwesterprodukt — ein
unabhängiger Node-Fleet-Monitor, nicht in eines der beiden anderen integriert. Wenn
`environment.chaincheckUrl` im Betreiber-Frontend konfiguriert ist, enthält das ausklappbare
Capability-Panel jedes chaincache-Knotens einen „In chaincheck ansehen"-Link dorthin. Das ist rein
ein Deep Link — Registerwerk fragt chaincheck nicht über dessen API ab und hängt für nichts, was
in der Knotenliste selbst angezeigt wird, von dessen Erreichbarkeit ab.
