---
title: Ajout de nouvelles chaînes
---

# Ajout de nouvelles chaînes

Les clients de la chaîne backend peuvent être enregistrés au moment de l'exécution. L'indexation EVM nécessite également une configuration réseau de graph-node
et une cible de déploiement prise en charge avec des sources de contrat explicites.

## Types de chaîne pris en charge

| Type | Exemples |
|---|---|
| `EVM` | Ethereum, Polygon, Base, Arbitrum, Fhenix, Inco, tout compatible EVM |
| `SOLANA` | Solana Mainnet, Devnet |

## Ajout d'une chaîne EVM (procédure complète)

### 1. Enregistrer via l'API d'administration

```bash
curl -X POST http://localhost:48000/api/v1/admin/chains \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "identifier": "OPTIMISM_MAINNET",
    "displayName": "Optimism",
    "chainType": "EVM",
    "networkType": "MAINNET",
    "chainId": 10,
    "rpcUrl": "https://mainnet.optimism.io",
    "wsUrl": "wss://mainnet.optimism.io",
    "blockExplorerUrl": "https://optimistic.etherscan.io",
    "graphNodeUrl": "http://graph-node:8000/subgraphs/name",
    "graphSubgraphName": "ewpg/optimism-mainnet"
  }'
```

### 2. Déployer les contrats

```bash
forge script script/Deploy.s.sol \
  --rpc-url https://mainnet.optimism.io \
  --broadcast
```

### 3. Ajouter à la configuration de graph-node

Voir [Configuration de l'indexeur](../configuration/indexers.md) pour le TOML et les modifications de docker-compose.

### 4. Redémarrer graph-node avec le nouveau réseau

L'API d'administration des déploiements ne peut pas accepter de manifeste pour le nouveau réseau tant que graph-node n'a pas
rechargé sa configuration de chaîne :

```bash
docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
```

Vérifiez que graph-node fonctionne correctement avant de continuer.

### 5. Configurer et déployer le sous-graphe

Configurez chaque source `*_OPTIMISM` décrite dans [The Graph](../indexers/the-graph.md), puis :

```bash
SUBGRAPH_VERSION_LABEL=optimism-20260729-01 ./indexer/evm/deploy-subgraph.sh optimism
```

Le sous-graphe est une projection d'événement provisoire. Il n'établit ni la finalité de la chaîne, ni l'effet juridique, ni l'état faisant autorité du registre, ni l'identité du code déployé.

### 6. Déclencher l'actualisation du client

```bash
curl -X POST http://localhost:48000/api/v1/admin/chains/refresh \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

Le `BlockchainClientRegistry` crée immédiatement un nouveau client Web3j pour la chaîne.

## RPC de secours

Vous pouvez configurer plusieurs URL RPC pour le basculement. La configuration de la chaîne stocke `fallback_rpc_urls` sous forme de liste séparée par des virgules. Si le RPC principal échoue, le registre tente des solutions de secours dans l'ordre.

```json
{
  "rpcUrl": "https://mainnet.optimism.io",
  "fallbackRpcUrls": "https://optimism.publicnode.com,https://rpc.ankr.com/optimism"
}
```

## Gouvernance et confiance des nœuds RPC

Le nœud RPC que le registre écoute détermine l'état de la chaîne auquel il croit ; les nœuds sont donc gérés comme des clés :

- **Authentification renforcée et second approbateur.** L'ajout, la modification, l'activation, la désactivation, l'épinglage (`exclusive`) et la suppression d'un nœud (`/api/v1/admin/chains/{chainId}/nodes...`) ainsi que la réinitialisation de l'épinglage du genesis exigent un jeton d'authentification renforcée et un second approbateur (`X-Dual-Control-Token`, motif `RPC_NODE_CHANGE`). L'événement d'audit `RPC_NODE_*` consigne l'opérateur, le second approbateur ainsi que l'ancienne et la nouvelle URL ; les secrets des URL (userinfo, valeurs de requête, segments de chemin ressemblant à une clé) sont masqués.
- **https uniquement.** Les URL de nœud doivent utiliser `https`/`wss`. `http`/`ws` en clair n'est accepté que pour des hôtes de bouclage ou privés et uniquement avec `registerwerk.rpc.allow-insecure-private=true` (environnements de démonstration). `chain_config.rpc_allowed_hosts` (séparé par des virgules, `*.example.org` possible) restreint facultativement les hôtes d'une chaîne. Les règles s'appliquent à l'ajout et lors d'un changement d'URL.
- **Identité de la chaîne.** À l'ajout et à chaque cycle de santé, `eth_chainId` doit être égal à `chain_config.chain_id` épinglé et le hash du bloc genesis à `chain_config.genesis_hash` (Solana : `getGenesisHash`). Le premier nœud conforme épingle le hash genesis. Un nœud divergent est refusé à l'ajout ; ensuite il est marqué non sain avec le motif `CHAIN_MISMATCH` et n'est jamais utilisé, même en dernier recours. Après une réinitialisation légitime d'un devnet, l'épinglage est effacé par `POST /api/v1/admin/chains/{chainId}/nodes/genesis-pin/reset` (authentification renforcée et second approbateur) ; le cycle suivant épingle le nouveau genesis.
- **Le meilleur bloc est une médiane.** Le retard est mesuré par rapport à la hauteur médiane des candidats au routage (la plus basse des deux), et non au maximum. Un nœud en avance de plus de `registerwerk.rpc.max-plausible-jump-blocks` (1000 par défaut) sur cette référence est mis en quarantaine (`IMPLAUSIBLE_HEIGHT`).
- **Basculement rapide.** Un nœud devient non sain dès la première sonde échouée et a besoin de deux sondes réussies consécutives pour revenir (`health_reason` `RECOVERING`). Avec au moins deux nœuds routables, les lectures en échec de transport sont rejouées sur le nœud suivant dans le même appel (trois tentatives au plus) ; le nœud fautif passe en dernier pendant 30 secondes. Les transactions ne sont diffusées qu'à un seul nœud. Après l'acceptation d'une diffusion par un nœud, les lectures privilégient ce nœud pendant deux minutes (lecture de ses propres écritures). Les lectures lending à appels multiples (cotations, actualisation des positions) s'exécutent sur un seul numéro de bloc, et un facteur de santé lending n'est fiable que si le nœud utilisé a au plus un bloc de retard.
- **Confirmation par une seconde source.** Avant qu'une transaction modifiant le registre soit finalisée, au moins deux nœuds sains doivent concorder avec le reçu : la même transaction (hash de bloc, contrat destinataire et expéditeur via `eth_getTransactionByHash`) et le même reçu via `eth_getTransactionReceipt` (statut, numéro et hash de bloc, gaz utilisé et liste complète des logs). Les fonctions qui modifient le registre forment une liste explicite de noms de fonction dans le backend (transferts forcés, burn, mint, gels dont le `setAddressFrozen` ordonné par un tribunal, pause, claims, registre d'identités, modules de conformité et leurs limites comme `setMaxBalance`, NAV `setNavPerShare`, propriété, rôles, écosystème et registre de dApps) ; une fonction non classée est traitée comme modifiant le registre. `registerwerk.blockchain.tx.second-source-methods` n'ajoute que des noms de fonction exacts (ou `*`). Un désaccord suspend la transaction (`registerwerk.confirmation.second_source_mismatch`, journal ERROR). Avec moins de deux nœuds sains, la transaction poursuit hors mode production et `registerwerk.confirmation.single_source` la compte ; **en mode production (`REGISTERWERK_PRODUCTION_MODE=true`) elle est suspendue** en `PENDING` (`registerwerk.confirmation.single_source_held`, alerte `RpcSingleSourceHeld`, puis `RpcSecondSourceHoldOverdue`) jusqu'à ce qu'un second nœud indépendant soit sain. Les frais du nœud principal sont recoupés avec un second nœud : frais = `min(A, B x 1,5)`.
- **Les paramètres de finalité** proviennent uniquement de `chain_config` et de `registerwerk.blockchain.tx.*`. Un nœud chaincache reste une source pour les hash de blocs, mais son niveau SAFE/FINALIZED auto-déclaré est plafonné par la profondeur ou les tags configurés. Le niveau de finalité juridiquement suffisant pour une inscription selon le modèle de chaîne reste une décision ouverte (T4-08).
- **L'identifiant de chaîne doit être fixé.** La signature refuse toute transaction sur une chaîne EVM dont `chain_config.chain_id` est vide (`chain id unpinned`). Au démarrage, le backend journalise une ERREUR listant les chaînes EVM activées qui ont des déploiements CONFIRMED mais pas de chain id, et expose la jauge `registerwerk_evm_chains_unpinned` (alerte `EvmChainIdUnpinned`). Il n'y a volontairement pas de rattrapage automatique : renseignez explicitement l'identifiant du réseau (`PATCH /api/v1/admin/chains/{id}` avec `chainId`) avant la prochaine transaction.
