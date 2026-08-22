---
title: Intégration chaincache
---

# Intégration chaincache

[chaincache](https://github.com/makibytes/chaincache) est un produit frère : une passerelle RPC
avec son propre suivi de chaîne canonique et de finalité. Registerwerk peut se connecter à une
instance chaincache de la même manière qu'à n'importe quel autre nœud RPC — comme une URL dans la
liste des nœuds — mais ce faisant, il obtient une rétractation de reorg poussée et sans lacune,
ainsi qu'un vrai palier `SAFE`, au lieu de la finalité grossière et basée sur le sondage qu'offre
une connexion directe à un nœud. Cette page décrit ce qu'est réellement cette amélioration, et la
configuration (délibérément minimale) qu'elle requiert.

## Un workload chaincache par chaîne

Le modèle de déploiement de chaincache est **un processus Spring Boot par chaîne**, pas une seule
instance desservant toutes les chaînes — `chaincache.runtime.allow-multiple-chains: false` est le
propre défaut de chaincache, et il refuse de démarrer avec zéro ou plus d'une chaîne configurée.
« Multi-chaîne » signifie N workloads, chacun son propre déployable, chacun desservant exactement
une chaîne. L'infrastructure partagée — PostgreSQL, supervision, ingress — est conçue pour être
partagée *entre* ces workloads : chaque ligne qu'un workload écrit est cantonnée à sa chaîne, si
bien que deux workloads peuvent pointer vers une seule instance Postgres sans collision. La pile de
démo le fait réellement : `chaincache-sepolia` et `chaincache-base` sont deux conteneurs
indépendants partageant une seule instance `chaincache-postgres`.

Cela change ce que « connecter Registerwerk à chaincache » signifie au niveau réseau : il n'existe
pas un nom d'hôte chaincache unique vers lequel pointer le nœud de chaque chaîne. Chaque `RpcNode`
de type `CHAINCACHE` pointe vers le workload spécifique qui dessert *sa* chaîne — `managementUrl`
est l'hôte:port propre à ce workload, et `remoteChainKey` est la clé de chaîne avec laquelle ce
workload a été configuré (presque toujours la seule clé de chaîne qu'il connaît, puisque chaque
workload dessert une seule chaîne).

Kafka ne fait explicitement **pas** partie de cette surface d'intégration. chaincache dispose de
son propre relais Kafka optionnel pour d'autres consommateurs en aval
(`chaincache.kafka.enabled`), mais Registerwerk ne le consomme jamais — les deux protocoles que
Registerwerk parle réellement avec chaincache sont le proxy JSON-RPC/web3j (`/{chain}/rpc`) et le
WebSocket d'événements durables propre à chaincache (`/{chain}/ws`). Si vous voyez
`chaincache.kafka.enabled: true` sur un workload, cela dessert un autre consommateur, pas
Registerwerk.

## Pourquoi c'est un type de nœud, pas un fichier de configuration

Une conception antérieure traitait l'adoption de chaincache comme « aucun changement de code côté
Registerwerk — c'est juste une URL dans une table », selon la théorie que l'invisibilité était le
but recherché. Pour un produit dont l'objet inclut de démontrer ce qu'un registre gagne grâce à
chaincache, l'invisibilité est exactement le contraire du but : `RpcNode` possède un `kind`
(`DIRECT_RPC` | `CHAINCACHE`), et l'interface opérateur indique, par connexion, quelles garanties
elle offre réellement. Un nœud RPC direct fournit une finalité grossière et basée sur le sondage,
qui peut manquer un reorg de courte durée et n'a pas de véritable palier `SAFE` sans tag de bloc
`safe` ; une connexion chaincache expose en plus des rétractations poussées, sans lacune et
rejouables, ainsi qu'un vrai palier `SAFE`.

## Détection automatique — rien à configurer à la main

Un opérateur ajoute un nœud toujours de la même manière, quel que soit ce qu'il s'avère être :
coller une URL, un libellé optionnel. Le fait que cette URL soit une connexion chaincache est
détecté automatiquement, jamais déclaré :

1. L'URL est vérifiée par rapport à la convention de routage de chaincache, `/<chainKey>/rpc` —
   une URL de cette forme donne un candidat `managementUrl` (schéma+hôte+port) et un candidat
   `remoteChainKey` (le dernier segment du chemin).
2. Le candidat est vérifié par un appel réel `GET <managementUrl>/api/capabilities`, en cherchant
   une entrée dont le `chainKey` correspond. Ce n'est qu'en cas de correspondance que le nœud est
   effectivement enregistré comme `CHAINCACHE` — une URL qui a seulement *l'air* d'être formée
   comme chaincache mais qui ne répond pas comme telle (une coïncidence, un vrai point de
   terminaison RPC tiers dont le chemin se termine par hasard par `.../rpc`, ou chaincache
   temporairement indisponible) est traitée comme `DIRECT_RPC`, jamais laissée dans un état «
   inconnu ». Une correspondance confirmée est suivie d'un second appel, à titre d'effort
   raisonnable, `GET <managementUrl>/api/chains` pour les compteurs de fournisseurs amont de ce
   workload (voir [Ce qui est sondé et affiché](#ce-qui-est-sonde-et-affiche)) — un échec à ce
   niveau ne fait que priver de compteurs de nœuds, il n'annule jamais la correspondance
   confirmée.
3. Une tâche de fond relance cette vérification pour chaque nœud activé environ une fois par
   minute, dans les deux sens — un nœud `DIRECT_RPC` dont l'URL commence à répondre comme
   chaincache est promu ; un nœud qui échoue à joindre chaincache trois fois de suite, ou qui est
   joignable mais ne liste plus le `remoteChainKey` attendu, retombe en `DIRECT_RPC`. Cette
   hystérésis à trois échecs (immédiate sur un « ne dessert plus cette chaîne » confirmé,
   tolérante à un incident transitoire isolé) existe spécifiquement pour qu'un sondage manqué ne
   coupe pas le flux d'événements durables d'une chaîne. Une action manuelle « revérifier
   maintenant » existe par nœud pour un opérateur qui ne veut pas attendre le prochain cycle.
4. Une URL qui résout vers une autorité venant d'échouer à un sondage est ignorée pendant une
   heure (un cache négatif) plutôt que re-sondée à chaque cycle — c'est ce qui empêche un vrai
   point de terminaison RPC tiers correspondant par hasard au motif `/<key>/rpc` d'être sollicité
   par un sondage sortant répété et bruyant.

Il n'existe nulle part dans le produit de sélecteur de type — un opérateur ne peut pas déclarer le
type d'un nœud, seulement découvrir lequel il est.

`ChainConfig.finalitySource` (`RPC_SELF_PROBE` | `CHAINCACHE`) suit le même principe : elle est
recalculée automatiquement selon que la chaîne possède actuellement un nœud `CHAINCACHE` activé,
à chaque fois qu'un nœud est ajouté, retiré, activé, désactivé ou re-détecté. Il n'existe pas non
plus de champ permettant à un opérateur de la définir directement.

## Authentification

Le propre défaut de chaincache est `chaincache.auth.enabled: true`, exigeant un jeton porteur avec
le rôle `USER`/`ADMIN`/`OPERATOR` sur `/api/**` et `/{chain}/api/**` (le chemin du proxy RPC
lui-même, `/{chain}/rpc` et `/{chain}/ws`, reste ouvert selon le propre défaut de chaincache —
`chaincache.auth.rpc-enabled: false` — puisque ce chemin est censé être joignable de la même
manière que n'importe quel point de terminaison RPC). Registerwerk génère son propre jeton HS256
de courte durée (`chain.internal.ChaincacheTokenFactory`, un jeton de 5 minutes portant
`roles: ["OPERATOR"]`, mis en cache jusqu'à peu avant son expiration) à partir de
`registerwerk.chaincache.jwt-secret` — un secret **distinct** de `JWT_DEV_SECRET` ; ne jamais
réutiliser ici la propre clé de signature de connexion opérateur de Registerwerk, car quiconque
détiendrait un identifiant chaincache pourrait alors aussi forger des sessions opérateur
Registerwerk. Ce secret doit correspondre au `chaincache.jwt.secret` propre du workload ciblé.

Si le secret est absent ou incorrect, les sondages de capacités et les connexions au flux durable
échouent avec un 401/403. Ceci n'est délibérément **pas** traité comme « ce n'est pas une instance
chaincache » — un nœud qui répond par 401/403 reste exactement comme il était (un nœud
`CHAINCACHE` confirmé reste `CHAINCACHE`, un nœud nouvellement ajouté reste `DIRECT_RPC`), et un
seul journal `WARN` nomme la correction (`registerwerk.chaincache.jwt-secret`) plutôt que de la
répéter à chaque cycle de sondage. Un secret erroné ou absent ne peut jamais réécrire
silencieusement le modèle de données.

`registerwerk_chaincache_capability_probe_failures_total{management_url,reason}` distingue ce cas
(`reason="unauthorized"`) de `chain_missing` (joignable, ne dessert pas cette chaîne) et
`unreachable` (réseau/délai dépassé/5xx) — voir [Observabilité](#observabilite) ci-dessous.

## Ce qui est sondé et affiché

Une fois détecté, `GET /api/capabilities` est re-sondé selon le même calendrier que la
re-détection du type, et sa réponse est stockée comme `capabilities` du nœud : modèle de finalité,
profondeurs de confirmation safe/finalized, quels espaces de noms RPC sont configurés, si
`debug_traceBlockByHash` est réellement disponible en amont, disponibilité du flux durable, et la
propre posture Kafka de ce workload. Une correspondance confirmée intègre en plus les compteurs de
fournisseurs amont de `GET /api/chains` (`configuredNodeCount`/`availableNodeCount` — combien de
fournisseurs RPC amont ce workload a configurés pour la chaîne, et combien répondent actuellement).
Le panneau extensible de la liste des nœuds opérateur, sur une ligne de type chaincache, montre
tout cela — plus quel workload (`managementUrl` + `remoteChainKey`) dessert réellement la chaîne,
et si Registerwerk maintient actuellement une connexion d'événements durables active vers lui —
directement à côté des données de santé plus sobres d'un nœud direct ; l'écart de capacités est
délibéré et visible, jamais dissimulé.

## Le flux durable

Lorsque la `finalitySource` d'une chaîne est `CHAINCACHE`,
`blockchain.internal.ChaincacheDurableStreamManager` ouvre un WebSocket persistant vers
`<managementUrl>/<remoteChainKey>/ws` et émet `chaincache_subscribeDurable` avec un `consumerId`
de la forme `registerwerk:<instanceId>:<remoteChainKey>` — stable à travers les redémarrages d'une
même instance Registerwerk (pour que son curseur reprenne plutôt que de repartir de zéro),
distinct par réplique via `registerwerk.chaincache.instance-id` (pour que les répliques ne
partagent pas un curseur et ne scindent pas silencieusement le flux d'événements entre elles).
**Il n'y a aucun appel de reprise séparé à effectuer** : chaincache persiste le curseur de chaque
consommateur côté serveur (`durable_consumer_cursor`, indexé par `consumerId` + flux) et le
reprend automatiquement dès que ce même `consumerId` se réabonne — `chaincache_resume` est un
alias de dispatch vers le même appel `subscribeDurable`, pas une étape distincte que Registerwerk
devrait déclencher lui-même.

Chaque événement durable porte une `sequence` monotone croissante, un `kind` (`BLOCK` ou
`RETRACTION`) et — pour une rétractation — un `retractsEventId` (`"block:..."` contre
`"log:..."`, seul le niveau bloc compte pour le suivi de la chaîne canonique) ainsi qu'un
`payload` portant la correction réelle : `commonAncestor`, la hauteur à partir de laquelle tout ce
qui suit est orphelin. C'est ce qui rend la détection de reorg de chaincache sans lacune et
poussée : Registerwerk n'a pas à sonder pour une rétractation, chaincache la signale au moment où
elle survient, de manière rejouable (un événement manqué est récupérable à la reconnexion via le
curseur persisté, jamais perdu silencieusement). Les événements sont acquittés avec
`chaincache_ack` au fur et à mesure de leur traitement.

Le chemin de détection de reorg par RPC pur (`ReorgGuard`, décrit dans
[Résilience des indexeurs](../indexers/resilience.md)) reste intact et continue de fonctionner
pour chaque chaîne `RPC_SELF_PROBE` — le flux durable est un signal supplémentaire, de plus haute
fidélité, pour les chaînes qui l'ont adopté, pas un remplacement pour celles qui ne l'ont pas
fait. Une chaîne `CHAINCACHE` obtient également sa revérification de fenêtre de finalité depuis le
propre point de terminaison `GET /{remoteChainKey}/api/blocks/{number}/finality` de chaincache
plutôt que par sondage RPC — tout échec à ce niveau (404, 5xx, délai dépassé, 401) retombe sur le
sondage RPC plutôt que de fabriquer un faux reorg, de sorte qu'une chaincache temporairement
injoignable dégrade le suivi de finalité au lieu de le casser complètement.

## Configuration minimale

La pile de démo exécute deux workloads chaincache côte à côte avec un nœud RPC direct simple, de
sorte que la liste des nœuds opérateur montre une comparaison réelle dès le départ :
`chaincache-sepolia` dessert le devnet Anvil local (`DEPTH_BASED`, safe=3/finalized=6), et
`chaincache-base` dessert de vrais fournisseurs RPC publics Base Sepolia (`TAG_BASED`,
`required-provider-agreement: 2`). Toute la configuration côté chaincache pour le workload devnet
local est :

```yaml
# Service Compose propre de chaincache-sepolia
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

Deux points méritent d'être signalés explicitement, tous deux de véritables bugs rencontrés et
corrigés par cette intégration :

!!! note "La clé de chaîne doit être un seul mot"
    La liaison souple des variables d'environnement de Spring Boot pour les propriétés
    `Map<String, ComplexBean>` (ce qu'est `chaincache.chains.<key>.*`) ne lie pas de manière
    fiable une clé de map à plusieurs mots/tirets bas (`sepolia-devnet`, `local_devnet`) à partir
    des seules variables d'environnement — seule une clé d'un seul mot (`sepolia`, `anvil`) se
    lie correctement. C'est une véritable limitation du `Binder` de Spring Boot, pas un bug de
    chaincache ; les clés de chaîne de la pile de démo sont délibérément des mots uniques pour
    cette raison.

!!! note "expected-chain-id est en hexadécimal, pas décimal"
    `RpcProviderIdentityValidator` compare `expected-chain-id` à la réponse `eth_chainId` de
    l'amont, qui est toujours de l'hexadécimal préfixé par `0x`. La configurer comme une chaîne
    décimale (`"11155111"` au lieu de `"0xaa36a7"`) fait qu'elle ne peut jamais correspondre, et
    avec `identity-validation-required: true` (le propre défaut de chaincache), le workload ne
    démarrera pas du tout tant que ce n'est pas corrigé.

Côté Registerwerk : rien. Ajouter un nœud RPC avec l'URL
`http://chaincache-sepolia:8080/sepolia/rpc` via le flux normal d'ajout de nœud (ou laisser
`DemoDataSeeder` le semer, comme le fait la pile de démo) — le type, la `finalitySource` et les
capacités suivent automatiquement en l'espace d'un cycle de détection dès que ce workload est
joignable et authentifié.

## Déploiement : infrastructure gérée partagée, releases indépendantes

Le propre [chart Helm](https://github.com/makibytes/chaincache) de chaincache déploie une release
par chaîne, chacune pointant vers une instance Cloud SQL partagée et une `HTTPRoute` Gateway API
Kubernetes partagée — la même forme que la pile de démo reproduit localement avec une seule
instance `chaincache-postgres` partagée derrière deux conteneurs. Le propre chart de Registerwerk
ne déploie **pas** chaincache (c'est un produit versionné et déployé indépendamment) ; il porte un
bloc de valeurs `chaincache:` nommant les URL de workload par chaîne auxquelles se connecter et
une référence au secret JWT partagé, plus une règle de sortie `NetworkPolicy` autorisant le trafic
vers l'espace de noms de chaincache lorsque les deux charts s'exécutent dans le même cluster. Un
exemple concret : deux releases Helm chaincache (`chaincache-sepolia`, `chaincache-base`) dans un
espace de noms `chaincache`, toutes deux pointant vers une instance Cloud SQL via le même schéma de
side-car `Cloud SQL Auth Proxy` que documente le propre README de chaincache, toutes deux scrapées
par la même instance Prometheus que le stack de supervision de Registerwerk exécute déjà ; la
liste de valeurs `chaincache.workloads` du propre chart de Registerwerk nomme les deux
`managementUrl`, de sorte qu'un amorçage équivalent à `DemoDataSeeder` (ou un « ajouter un nœud »
manuel) puisse les câbler.

### Mises à niveau de version majeure Flyway / PostgreSQL

Les propres migrations de chaincache sont une seule référence d'installation propre plus des
fichiers `V{n}` incrémentaux, la même convention que Registerwerk utilise. Un saut de version
majeure PostgreSQL (la pile de démo est passée de 15 à 18.6) n'est pas quelque chose que Flyway
gère de lui-même — il faut soit un `pg_upgrade` sur place, soit un dump/restore, et le chemin du
répertoire de données change lui-même entre les tags d'image Postgres majeurs
(`/var/lib/postgresql/data` contre `/var/lib/postgresql` à partir de la version 18) — monter
l'ancien volume au chemin attendu par la nouvelle image démarre silencieusement un cluster neuf et
vide au lieu d'échouer bruyamment. Traiter un saut de version majeure PostgreSQL comme sa propre
étape de migration délibérée, jamais comme un effet secondaire d'un bump de tag d'image.

### Liste de contrôle production

- `chaincache.runtime.identity-validation-required: true`, avec `expected-chain-id` défini en
  hexadécimal préfixé par `0x` pour chaque chaîne configurée — une identité de chaîne non
  validée ou mal configurée signifie qu'un workload pourrait silencieusement desservir les
  données de la mauvaise chaîne.
- `chaincache.cache.local-snapshot-enabled: false` et
  `chaincache.request.local-stats-enabled: false` sur chaque réplique — la posture que cette
  intégration reproduit localement dans la pile de démo, correspondant à ce dont a besoin un
  déploiement répliqué horizontalement (l'état local par réplique n'a pas de sens dès qu'il y a
  plus d'un pod derrière un workload).
- `chaincache.auth.enabled: true`, avec un `registerwerk.chaincache.jwt-secret` qui n'est
  **pas** la même valeur que `JWT_DEV_SECRET` ou toute autre clé de signature de Registerwerk.
- Le point de terminaison de vérification de santé utilisé pour l'orchestration devrait être
  `/actuator/health/readiness`, pas liveness — un workload peut être vivant sans être encore
  prêt à servir (par exemple en train d'établir ses connexions RPC amont), et router du trafic
  vers lui pendant cette fenêtre produit des échecs de sondage évitables côté Registerwerk.
- `chaincache.auth.prometheus-public: true` uniquement lorsque le scrape traverse une frontière
  réseau à laquelle Prometheus fait déjà confiance (par exemple le même réseau interne au
  cluster, jamais un port publié) — cela existe spécifiquement pour que
  `/actuator/prometheus` n'exige pas le jeton porteur propre de Registerwerk, que Prometheus ne
  porte pas.

## Observabilité

La propre instance Prometheus de Registerwerk scrape directement les deux workloads chaincache
(étiquette `chaincache_workload` les distinguant) et alerte sur workload indisponible, taux
d'erreur RPC amont élevé, flapping WebSocket et rafales de reorg en utilisant les propres
métriques Micrometer de chaincache (`chaincache.rpc.errors`, `chaincache.rpc.ws.disconnects`,
`chaincache.chain.reorganizations`, `chaincache.rpc.node.latency` — cette dernière n'expose que
`_count`/`_sum`, aucun histogramme de percentile, donc alerter dessus signifie latence moyenne,
pas p95). Côté Registerwerk, `registerwerk_chaincache_stream_connected{chain}` et
`registerwerk_chaincache_stream_last_event_timestamp_seconds{chain}` sous-tendent deux alertes
supplémentaires (flux déconnecté depuis 5+ minutes ; aucun événement reçu depuis 10+ minutes
malgré une connexion ouverte — le second cas signifie généralement que la chaîne elle-même a
cessé de produire des blocs, pas que l'intégration est cassée). Un tableau de bord Grafana
(`monitoring/grafana/dashboards/chaincache.json`) couvre, par workload, la santé/latence/reorgs/
déconnexions amont, ainsi que la ligne de santé du flux côté Registerwerk.

## Lien profond vers chaincheck

[chaincheck](https://github.com/makibytes/chaincheck) est le troisième produit frère — un
moniteur de parc de nœuds indépendant, non fusionné dans l'un ou l'autre des deux autres. Lorsque
`environment.chaincheckUrl` est configuré dans le frontend opérateur, le panneau de capacités
extensible de chaque nœud de type chaincache inclut un lien « Voir dans chaincheck » vers celui-ci.
Il s'agit purement d'un lien profond — Registerwerk n'interroge pas l'API de chaincheck et ne
dépend pas de sa joignabilité pour quoi que ce soit d'affiché dans la liste de nœuds elle-même.
