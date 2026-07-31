---
title: Résilience et récupération
---

# Résilience de l'indexeur

Cette page décrit comment le registre détecte les lacunes de l'indexeur, récupère après des pannes et compare les plages d'événements provisoires. Ces procédures n'établissent pas la finalité de la chaîne ni l'exactitude juridique.

## Suivi de l'état de l'indexeur

Le backend maintient une table `indexer_state` qui enregistre le dernier bloc indexé avec succès pour chaque chaîne :

```sql
SELECT chain_id, network_name, latest_indexed_block,
       chain_head_block,
       (chain_head_block - latest_indexed_block) AS lag_blocks,
       last_updated_at
FROM indexer_state
ORDER BY lag_blocks DESC;
```

Le backend interroge l'API d'état d'indexation de graph-node toutes les 30 secondes et met à jour cette table.

## Détection d'écart

Un écart se produit lorsque l'indexeur prend du retard sur la tête de chaîne. Le backend classe le décalage comme suit :

| Décalage (blocs) | Statut | Action |
|-------------|--------|--------|
| 0–5 | OK | Fonctionnement normal |
| 6–20 | WARN | Avertissement enregistré, l'alerte Prometheus se déclenche |
| 21–100 | DEGRADED | Le tableau de bord affiche un avertissement, e-mail envoyé à l'opérateur |
| 100+ | CRITICAL | `/actuator/health` renvoie `DOWN`, l'alerte PagerDuty se déclenche |

## Procédures de récupération

### Récupération de graph-node (EVM)

Si graph-node prend du retard en raison d'une interruption RPC :

1. Vérifiez les journaux de graph-node pour détecter les erreurs :

   ```bash
   docker compose logs --tail=100 graph-node | grep -i "error\|panic"
   ```

2. Vérifiez que le point de terminaison RPC est accessible :

   ```bash
   curl -X POST $ETH_MAINNET_RPC \
     -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
   ```

3. Si le RPC est en panne, mettez à jour `.env` avec un RPC de secours et redémarrez :

   ```bash
   docker compose restart graph-node
   ```

4. Surveillez la progression de la récupération sur `http://localhost:8030`.

### Réindexation du sous-graphe

Si un sous-graphe comporte des erreurs fatales et ne peut pas récupérer automatiquement, ne supprimez pas le déploiement actif. Générez et déployez une nouvelle version sous le nom de sous-graphe mainnet configuré :

```bash
# Configure every *_MAINNET singleton and multi-instance list with its deployment block,
# then render, validate and deploy a uniquely labelled fresh version. Graph Node retains
# the prior version while ewpg/ethereum-mainnet indexes the replacement.
SUBGRAPH_VERSION_LABEL=recovery-YYYYMMDDHHMM ./indexer/evm/deploy-subgraph.sh mainnet
```

Attendez que la nouvelle version atteigne la tête de chaîne, puis comparez sa plage d'événements de manière indépendante avant d'autoriser toute dépendance en aval. Conservez la configuration et les artefacts précédents. Si une restauration est nécessaire, redéployez cette configuration précédemment approuvée sous une nouvelle étiquette de version ; cela crée une nouvelle version au lieu de supprimer de manière destructive l'un ou l'autre des deux historiques.

### Récupération de l'indexeur Solana

Si l'indexeur Solana a manqué des événements lors d'une panne de gRPC :

1. Vérifiez le dernier emplacement traité avec succès dans la table d'état de l'indexeur
2. La solution de repli par sondage de l'indexeur retraite automatiquement les emplacements lorsqu'il se reconnecte
3. Si l'écart est trop important (>10 000 emplacements), déclenchez un remplissage manuel :

   ```bash
   curl -X POST http://localhost:3001/backfill \
     -H "Content-Type: application/json" \
     -d '{"fromSlot": 285600000, "toSlot": 285614923}'
   ```

## Alertes de surveillance

Configurez les règles d'alerte Prometheus dans `monitoring/alerts.yml` :

```yaml
groups:
  - name: indexer
    rules:
      - alert: IndexerLagHigh
        expr: indexer_lag_blocks > 20
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Indexer lag > 20 blocks on {{ $labels.chain }}"

      - alert: IndexerLagCritical
        expr: indexer_lag_blocks > 100
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Indexer lag CRITICAL on {{ $labels.chain }}"

      - alert: GraphNodeDown
        expr: up{job="graph-node"} == 0
        for: 1m
        labels:
          severity: critical
```

## Comparaison de plages d'événements planifiée (non implémentée)

Registerwerk n'expose actuellement pas de point de terminaison d'administration `verify-consistency`. Un contrôle de récupération planifié va :

1. Interroger le sous-graphe pour tous les événements de transfert dans la plage de blocs
2. Récupérer directement les mêmes événements depuis la chaîne via `eth_getLogs`
3. Comparer les deux ensembles et signaler toute divergence

Tant que ce contrôle n'est pas implémenté et testé, les opérateurs doivent effectuer une comparaison de plage d'événements contrôlée de manière indépendante avant de reprendre toute dépendance à ces données. Même alors, des ensembles d'événements concordants n'établiraient un accord que pour la plage vérifiée — pas la finalité de la chaîne, l'état juridique du registre, l'effet juridique, le règlement, ou l'identité du code déployé.

# Résilience et récupération

## Modes de défaillance et récupération

| Composant | Défaillance | Récupération |
|---|---|---|
| graph-node | Arrête l'indexation | Au redémarrage, reprend à partir de `last_indexed_block` |
| Nœud RPC EVM | Connexion perdue | `GRAPH_ETHEREUM_REQUEST_RETRIES=10` ; RPC de secours configurables |
| Backend ↔ graph-node | Impossible d'atteindre GraphQL | `consecutive_errors` s'incrémente ; reprend à partir du curseur lors de la reconnexion |
| gRPC Yellowstone | Interruption du flux | Le backend se reconnecte ; le job de sondage comble les lacunes |
| RPC Solana | Le sondage échoue | `indexer_state.status = ERROR` ; alerte de surveillance après 2h |

## Moniteur d'indexeur

`IndexerMonitorService` vérifie toutes les 5 minutes si `indexer_state.last_synced_at` date de plus de 2 heures. Si c'est le cas, il publie un événement d'audit `INDEXER_STALE`.

## Récupération manuelle

Si un indexeur prend un retard significatif :

```bash
# Check current cursor
SELECT chain_config_id, indexer_type, last_synced_block, last_synced_at, status
FROM indexer_state;

# Reset cursor to force full re-sync (use with care)
UPDATE indexer_state SET last_synced_block = 0 WHERE chain_config_id = '<uuid>';
```

Puis redémarrez le service de synchronisation concerné ou le backend.

## Déduplication

Tous les événements sont stockés avec une contrainte UNIQUE sur `(chain_config_id, tx_hash, log_index)`. La resynchronisation à partir d'un bloc antérieur est sûre — les doublons sont ignorés silencieusement (`ON CONFLICT DO NOTHING`).
