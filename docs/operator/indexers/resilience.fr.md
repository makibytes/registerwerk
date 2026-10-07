---
title: Résilience et récupération
---

# Résilience de l'indexeur

Cette page décrit comment le registre détecte les lacunes de l'indexeur, détecte et corrige les réorganisations de chaîne (reorgs) et se rétablit après des pannes. Ces procédures n'établissent pas d'exactitude juridique — voir `docs/operator/indexers/the-graph.md` pour ce que `synced: true` d'un subgraph signifie et ne signifie pas. Pour ce qui se passe *en aval* d'un reorg détecté — le journal des effets, la compensation automatique et la porte de politique qui peut geler un actif dans l'attente d'un examen — voir [Politique de finalité et compensation des reorgs](finality-and-compensation.md).

!!! note "Corrigé d'après l'implémentation réelle"
    Une version antérieure de cette page décrivait un schéma `chain_head_block`/`latest_indexed_block`, une échelle de niveaux de santé `DEGRADED`/`CRITICAL` et un point d'entrée `POST /backfill` — rien de tout cela n'existe dans cette base de code. Cette page décrit désormais ce qui est réellement implémenté.

!!! note "De deux à trois niveaux"
    Cette page décrivait à l'origine un `token_transfer.finality_status` à deux niveaux : `PROVISIONAL`/`FINAL`/`ORPHANED`. Cette colonne a été étendue au `finality.api.FinalityLevel` à trois niveaux (`PROVISIONAL`/`SAFE`/`FINALIZED`/`ORPHANED`) partagé avec les produits frères chaincache et chaincheck — `FINAL` est devenu `FINALIZED`, et un nouveau niveau `SAFE` se place entre PROVISIONAL et FINALIZED pour les chaînes qui exposent un point de contrôle intermédiaire (p. ex. l'étiquette de bloc `safe` d'Ethereum, `ACCEPTED_ON_L2` de Starknet). Chaque exemple de cette page a été mis à jour avec la colonne et l'énumération actuelles.

## Suivi de l'état de l'indexeur

Le backend tient une table `indexer_state`, une ligne par `(chain_config_id, indexer_type)` :

```sql
SELECT chain_config_id, indexer_type, status,
       last_synced_block, last_final_block, last_synced_at,
       consecutive_errors, last_error
FROM indexer_state
ORDER BY last_synced_at ASC NULLS FIRST;
```

- `last_synced_block` — le curseur de tête/provisoire : le plus haut bloc dont l'indexeur a lu des transferts, que ces lignes aient ou non été finalisées depuis.
- `last_final_block` — le curseur confirmé : le plus haut bloc dont les lignes `token_transfer` ont toutes franchi la profondeur de confirmation configurée et ont été vérifiées par rapport à un hash/statut canonique récupéré à nouveau (EVM, Starknet). Toujours `<= last_synced_block`. Null pour les chaînes finales à l'écriture (Solana/Stellar/Canton — voir ci-dessous), qui n'ont jamais de fenêtre non réglée à suivre.
- `status` — `ACTIVE`, `PAUSED` ou `ERROR`. Un indexeur en `ERROR` avec `consecutive_errors >= 10` (5 pour Canton) cesse de s'exécuter jusqu'à une réinitialisation manuelle (voir [Récupération manuelle](#manual-recovery)) — il n'y a aucune auto-réparation automatique au-delà de ce point.

## Détection et récupération des reorgs de chaîne

Chaque chaîne EVM (via graph-node) et chaque chaîne Starknet revérifie sa fenêtre encore non réglée (PROVISIONAL ou SAFE) à chaque cycle de synchronisation, via `ReorgGuard` :

- **EVM** — `token_transfer.block_hash` est enregistré pour les lignes situées dans la profondeur de confirmation configurée (`registerwerk.blockchain.tx.confirmations-by-chain`) ; `ReorgGuard` récupère à nouveau le hash canonique de chacun de ces blocs via `_meta(block: {number})` de graph-node et le compare. Un bloc qui concorde toujours est promu d'un cran (PROVISIONAL → SAFE → FINALIZED, avec une profondeur de confirmation étiquetée `safe` distincte de celle `finalized`) ; une divergence marque chaque ligne à partir du point de bifurcation comme `ORPHANED` (jamais supprimée — c'est un registre réglementé soumis à une exigence de piste d'audit) et ramène `last_synced_block`/`last_final_block` à `fork_block - 1`, de sorte que le cycle suivant réindexe la plage concernée. Une chaîne dont `ChainConfig.finalitySource` vaut `CHAINCACHE` (voir [Intégration de chaincache](../blockchain/chaincache-integration.md)) obtient cette revérification de `ChaincacheFinalityProbe` — un appel au propre `GET /{chain}/api/blocks/{number}/finality` de la charge chaincache de cette chaîne — au lieu de la récupération RPC ci-dessus ; tout échec de la sonde (injoignable, 401, 404, 5xx) retombe sur le chemin RPC plutôt que de fabriquer un faux reorg, de sorte qu'une brève indisponibilité de chaincache dégrade le suivi de la finalité vers le comportement RPC simple au lieu de le casser. Indépendamment de la sonde qui répond à cette requête, la chaîne reçoit aussi le flux d'événements durable, poussé, de chaincache (`ChaincacheDurableStreamManager`) comme source *supplémentaire* et sans lacune d'observations `BLOCK`/`RETRACTION` alimentant le même registre `block_finality` décrit ci-dessous — les deux sont complémentaires, non exclusives : le flux durable peut observer une rétractation avant que cette revérification par interrogation ne l'aurait détectée, et le chemin par interrogation continue de fonctionner même si la connexion au flux durable est brièvement coupée.
- **Starknet** — aucune primitive de hash de bloc n'est utilisée ; la finalité de chaque ligne non réglée est plutôt revérifiée via le champ `status` de `starknet_getBlockWithTxHashes` : `ACCEPTED_ON_L2` promeut vers SAFE, `ACCEPTED_ON_L1` vers FINALIZED ; un bloc `REJECTED`/`REVERTED` déclenche le même chemin d'orphelinage et de retour en arrière que pour EVM.
- **Solana** — la finalité est établie à l'écriture : les transferts ne sont indexés qu'avec `commitment: "finalized"`, et le champ `err` de la signature est vérifié pour qu'une transaction échouée ne soit jamais indexée comme un transfert réussi. Il n'y a pas de fenêtre non réglée distincte à revérifier.
- **Stellar / Canton** — la clôture du ledger (Stellar/Horizon) et la validation du synchronizer (Canton) sont finales dès qu'elles sont observées ; même raisonnement que pour Solana.

Chaque bloc que la fenêtre non réglée d'une chaîne touche un jour — ceux réellement revérifiés ci-dessus — est aussi enregistré dans `block_finality` (une ligne par `(chain_config_id, block_number)`, propriété du module `finality`, alimentée par `ReorgGuard`). C'est un registre distinct de `token_transfer` lui-même : c'est la source de vérité que consultent `FinalityGate` et le mécanisme de compensation des effets (voir [Politique de finalité et compensation des reorgs](finality-and-compensation.md)), de sorte qu'ils n'ont jamais besoin de parcourir `token_transfer` ni d'importer le module indexeur. `token_transfer.finality_status` reste un cache dénormalisé du même fait, commode pour interroger directement les transferts.

`token_transfer.finality_status ∈ {PROVISIONAL, SAFE, FINALIZED, ORPHANED}` peut être interrogé directement :

```sql
SELECT chain_config_id, finality_status, count(*)
FROM token_transfer
WHERE finality_status <> 'FINALIZED'
GROUP BY chain_config_id, finality_status;
```

Un nombre non nul de `ORPHANED` est attendu de façon transitoire juste après un vrai reorg ; un nombre qui ne diminue pas aux cycles suivants signifie que la plage concernée n'est pas réindexée avec succès — vérifiez `indexer_state.last_error` pour cette chaîne. Un nombre `SAFE` durable (des lignes qui ne progressent pas vers `FINALIZED`) signifie généralement que le modèle de finalité de la chaîne attend une profondeur de confirmation ou une étiquette de bloc que le nœud RPC configuré ne rapporte pas — voir la section sur le modèle de finalité de `docs/operator/blockchain/adding-chains.md`.

**Limite connue :** il n'existe pas de politique de RPC de confiance/quorum — la réponse `_meta`/statut d'un seul point d'entrée RPC configuré est crue telle quelle pour la détection des reorgs. Un nœud RPC défaillant ou en retard peut lui-même produire un faux signal de reorg ; recoupez avec la configuration RPC de `docs/operator/blockchain/adding-chains.md` avant de traiter une alerte de reorg comme un événement de chaîne confirmé.

## Surveillance du retard de l'indexeur

`IndexerMonitorService` s'exécute toutes les 5 minutes et publie deux jauges Prometheus :

- `registerwerk_indexer_last_sync_timestamp_seconds{chain_config_id, indexer_type}` — secondes d'époque Unix de la dernière synchronisation réussie. Alertez avec `time() - <metric> > threshold`.
- `registerwerk_indexer_lag_blocks{chain_config_id, indexer_type}` — blocs entre `last_synced_block` et le plus haut `latest_block_number` rapporté par un `rpc_node` activé et sain de cette chaîne (en réutilisant les données de tête déjà en cache de `RpcNodeHealthService`, pas un nouvel appel RPC). Absente — et non nulle — pour une chaîne sans nœud sain ou sans bloc encore synchronisé : une série manquante signifie donc « pas de données », non « pas de retard ».

Il publie aussi un événement d'audit `INDEXER_STALE` chaque fois qu'un indexeur est en `ERROR` ou n'a pas synchronisé depuis plus de 2 heures. Les règles Prometheus réelles figurent dans `monitoring/alerts/registerwerk.yml`, groupe `registerwerk.critical` (`IndexerStaleCritical`/`IndexerStaleWarning`) et groupe `registerwerk.observability` (`IndexerLagBlocksHigh`, alerte à plus de 1000 blocs pendant 10 minutes ou plus) — ce sont de vraies règles évaluées dans ce dépôt, non des exemples.

## Récupération manuelle { #manual-recovery }

Un indexeur ayant atteint `consecutive_errors >= 10` (5 pour Canton) cesse de synchroniser jusqu'à sa réinitialisation.

```bash
# List every indexer's current state
curl -H "Authorization: Bearer $OPERATOR_JWT" http://localhost:48080/api/v1/indexers

# Clear the error state — the next scheduled tick resumes from the existing cursor, no restart required
curl -X POST -H "Authorization: Bearer $OPERATOR_JWT" \
  "http://localhost:48080/api/v1/indexers/<indexer-state-id>/reset"

# Force a full re-sync from genesis instead (only if the existing cursor itself is untrustworthy —
# e.g. after a manual chain-state correction; this re-processes the chain's entire history)
curl -X POST -H "Authorization: Bearer $OPERATOR_JWT" \
  "http://localhost:48080/api/v1/indexers/<indexer-state-id>/reset?fullResync=true"
```

Les deux actions exigent `REGISTRY_ADMIN` et sont auditées (`INDEXER_RESET`). Le SQL direct équivalent (p. ex. pour un chemin scripté/d'urgence sans l'API) reste :

```sql
SELECT id, chain_config_id, indexer_type, last_synced_block, last_synced_at, status, consecutive_errors, last_error
FROM indexer_state;

UPDATE indexer_state SET status = 'ACTIVE', consecutive_errors = 0, last_error = NULL WHERE id = '<uuid>';
```

## Déduplication

`token_transfer` a des contraintes `UNIQUE NULLS NOT DISTINCT` compatibles avec le partitionnement sur `(chain_config_id, tx_hash, log_index, occurred_at)` pour EVM/Starknet et sur `(chain_config_id, tx_hash, slot, occurred_at)` pour Solana. Resynchroniser à partir d'un bloc antérieur est sans danger, car chaque service de synchronisation vérifie aussi l'identité de la transaction avant d'insérer : une plage retraitée est ignorée et non dupliquée.

## Modes de défaillance et récupération

| Composant | Défaillance | Récupération |
|---|---|---|
| graph-node | Cesse d'indexer / signale `hasIndexingErrors` | Le backend dégrade ce cycle en « tout écrire en PROVISIONAL, sauter la revérification des reorgs » au lieu de faire échouer la synchronisation ; examinez graph-node directement |
| RPC EVM/Starknet/Stellar | Connexion perdue | `consecutive_errors` s'incrémente ; reprise au curseur à la reconnexion ; statut `ERROR` après 10 échecs consécutifs |
| Solana Yellowstone gRPC | Le flux se coupe | Le repli par interrogation (`SolanaTransferSyncService`) comble les lacunes via sa propre tâche cron de 10 minutes, quel que soit l'état du flux |
| Flux du ledger Canton | Le flux se coupe | Statut `ERROR` après 5 échecs consécutifs (seuil plus bas que pour les autres chaînes — voir `CantonTransferSyncService`) |

## Réindexation du subgraph (EVM uniquement)

Si un subgraph a des erreurs fatales et ne peut pas se rétablir automatiquement, ne supprimez pas le déploiement actif. Rendez et déployez une nouvelle version sous le nom de graph principal configuré :

```bash
SUBGRAPH_VERSION_LABEL=recovery-YYYYMMDDHHMM ./indexer/evm/deploy-subgraph.sh mainnet
```

Attendez que la nouvelle version atteigne la tête de la chaîne, puis comparez indépendamment sa plage d'événements avant de permettre qu'on s'y fie en aval. Conservez la configuration et les artefacts précédents ; si un retour en arrière est nécessaire, redéployez la configuration précédemment approuvée sous un nouveau libellé de version plutôt que de supprimer de façon destructive l'historique de l'une ou l'autre version.
