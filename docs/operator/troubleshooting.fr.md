---
title: Dépannage
---

# Dépannage

Cette page couvre les problèmes les plus courants rencontrés lors du fonctionnement du registre eWpG, ainsi que leurs causes profondes et leurs solutions.

## Erreurs Blockchain / RPC

### « L'appel RPC à la blockchain a échoué » dans les journaux backend

**Symptôme** : les journaux backend affichent `BlockchainException: RPC call failed for chain mainnet` et les appels API renvoient HTTP 502.

**Cause** : le point de terminaison RPC configuré est inaccessible ou renvoie des erreurs.

**Solution** :

1. Testez manuellement le point de terminaison RPC :

   ```bash
   curl -X POST $ETH_MAINNET_RPC \
     -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
   ```

2. S'il renvoie une erreur, passez à une sauvegarde RPC dans `.env` et redémarrez le backend
3. Consultez la page d'état de votre fournisseur RPC pour connaître les incidents en cours
4. Pensez à ajouter une URL RPC de secours dans la configuration de la chaîne

### La transaction de déploiement de jeton ne confirme jamais

**Symptôme** : Après avoir cliqué sur « Deploy to Blockchain », l'état reste sur « Deploying » indéfiniment.

**Cause** : La transaction de déploiement a été soumise mais n'a jamais été confirmée (par exemple, prix du gas trop bas, congestion du réseau).

**Solution** :

1. Notez le hachage de la transaction sur la page de détails de l'émission
2. Recherchez-le dans l'explorateur de blocs : est-il en attente ou abandonné ?
3. En cas d'attente, attendez que la congestion du réseau se dissipe, ou utilisez `cast` pour accélérer :

   ```bash
   cast send --gas-price 150gwei <tx-hash> --rpc-url $RPC_URL --private-key $DEPLOYER_KEY
   ```

4. En cas d'abandon, le backend réessaiera automatiquement toutes les 5 minutes (jusqu'à 3 fois)
5. Si toutes les tentatives échouent, l'émission revient au statut APPROVED — cliquez à nouveau sur Deploy

---

## Lacunes de l'indexeur

### Le sous-graphe ne se synchronise pas

**Symptôme** : Le tableau de bord affiche la chaîne comme « DEGRADED » ou « CRITICAL ». Les journaux de graph-node indiquent que l'indexation est bloquée.

**Solution** :

1. Vérifiez les journaux de graph-node :

   ```bash
   docker compose logs --tail=50 graph-node | grep -i "error\|failed\|panic"
   ```

2. Vérifiez l'état du RPC — souvent provoqué par la limitation de débit imposée par le fournisseur RPC à graph-node
3. Ajoutez un deuxième fournisseur RPC comme solution de secours dans `graph-node.toml`
4. Redémarrez graph-node :

   ```bash
   docker compose restart graph-node
   ```

5. Si le sous-graphe est bloqué avec une erreur fatale, redéployez-le (voir [The Graph](./indexers/the-graph.md))

### Événements de transfert manquants dans le registre

**Symptôme** : Un transfert visible sur l'explorateur de blocs n'apparaît pas dans le registre.

**Cause** : L'indexeur était derrière la tête de chaîne au moment du transfert, ou le sous-graphe a été redéployé à partir d'un bloc de démarrage postérieur au transfert.

**Solution** :

1. Vérifiez l'état actuel de l'indexeur :

   ```bash
   curl http://localhost:48080/api/v1/admin/chains \
     -H "Authorization: Bearer $OPERATOR_JWT" \
     | jq '.[].latestIndexedBlock'
   ```

2. Si l'indexeur a rattrapé son retard et que l'événement est toujours manquant, effectuez une comparaison indépendante et contrôlée des événements du sous-graphe avec `eth_getLogs` pour la plage concernée.

3. Si un écart est confirmé, redéployez le sous-graphe à partir d'un bloc antérieur à l'événement manquant

---

## Erreurs de téléchargement KYC

### Erreur « Document trop volumineux »

**Symptôme** : Le téléchargement du document KYC échoue avec « la taille du fichier dépasse la limite ».

**Cause** : La taille limite par défaut du document est de 20 Mo (plugin `request-size-limiting` de Kong).

**Solution** : Compressez le document avant de le télécharger. Si le document original dépasse 20 Mo, demandez au client de fournir une version compressée. En tant qu'opérateur, vous pouvez augmenter la limite dans `gateway/kong.yml` :

```yaml
plugins:
  - name: request-size-limiting
    config:
      allowed_payload_size: 50  # MB
```

### « Échec du téléchargement S3 » dans les journaux backend

**Symptôme** : les journaux backend affichent `S3UploadException` lorsqu'un document KYC est enregistré.

**Cause** : les informations d'identification S3 sont incorrectes, le compartiment n'existe pas, ou la politique IAM n'autorise pas `PutObject`.

**Solution** :

1. Vérifiez les informations d'identification S3 dans `.env`
2. Testez l'accès S3 :

   ```bash
   aws s3 ls s3://your-kyc-bucket
   ```

3. Assurez-vous que la politique IAM inclut `s3:PutObject`, `s3:GetObject` et `s3:DeleteObject` pour le compartiment

---

## Erreurs d'authentification

### « Échec de la validation JWT » — réponses 401

**Symptôme** : les requêtes API renvoient 401 avec `JWT validation failed` même si le jeton semble valide.

**Cause** : l'émetteur du jeton ne correspond pas à `JWT_ISSUER_URI`, ou le point de terminaison JWKS est inaccessible.

**Solution** :

1. Décodez le JWT sur [jwt.io](https://jwt.io) et vérifiez que la revendication `iss` correspond à votre `JWT_ISSUER_URI`
2. Vérifiez que le backend peut atteindre le point de terminaison JWKS :

   ```bash
   docker exec registerwerk-backend-1 \
     curl ${JWT_ISSUER_URI}/.well-known/jwks.json
   ```

3. Si le point de terminaison JWKS est inaccessible depuis l'intérieur du réseau Docker, définissez explicitement `JWT_JWKS_URI`

### Les utilisateurs ne peuvent plus se connecter après un changement d'IdP

**Symptôme** : Après avoir basculé une entité cliente vers un IdP personnalisé, ses utilisateurs reçoivent « Accès refusé ».

**Solution** :

1. Vérifiez que l'URI de redirection de l'IdP du client est correctement définie
2. Testez l'intégration IdP via **Entités → [entité] → Fournisseur d'identité → Test**
3. Vérifiez les journaux backend pour l'erreur OIDC spécifique (généralement `redirect_uri_mismatch` ou `invalid_client`)

---

## Problèmes de base de données

### Le backend ne démarre pas — erreur de migration Flyway

**Symptôme** : le conteneur backend se ferme au démarrage avec `FlywayException: Validate failed`.

**Cause** : un fichier de migration a été modifié après son application, ou les migrations sont dans le désordre.

**Solution** :

```bash
# Check current migration state
docker exec registerwerk-postgres-1 \
  psql -U ewpg -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;"
```

Si une migration affiche `success = false`, corrigez le SQL de la migration et redémarrez. Ne modifiez jamais les fichiers de migration qui ont déjà été appliqués en production.

### Disque plein sur le volume PostgreSQL

**Symptôme** : le backend renvoie des erreurs 500. Les journaux Postgres affichent `FATAL: could not write to file`.

**Solution** :

1. Identifiez les plus grandes tables :

   ```sql
   SELECT relname, pg_size_pretty(pg_total_relation_size(relid))
   FROM pg_catalog.pg_statio_user_tables
   ORDER BY pg_total_relation_size(relid) DESC LIMIT 10;
   ```

2. La table `audit_log` est partitionnée par plage mensuelle. Supprimez les anciennes partitions si le disque est critique :

   ```sql
   DROP TABLE audit_log_y2024m01;
   ```

3. Étendez le volume Docker et redémarrez PostgreSQL

# Dépannage

## Le backend ne démarre pas

**Symptôme** : `Connection refused` sur `localhost:48080`

1. Vérifiez la connectivité de la base de données : `docker compose logs postgres`
2. Vérifiez les migrations Flyway : recherchez `FlywayException` dans les journaux backend
3. Vérifiez que les variables d'environnement requises sont définies (en particulier `DB_PASSWORD`, `JWT_ISSUER_URI`)

## Les jetons n'apparaissent pas dans l'historique

**Symptôme** : `GET /api/v1/assets/{id}/history` renvoie une réponse vide

1. Vérifiez que graph-node est en cours d'exécution : `curl http://localhost:8020/health`
2. Vérifiez que le sous-graphe est déployé : `curl http://localhost:8000/subgraphs/name/ewpg/ethereum-sepolia`
3. Vérifiez l'état de l'indexeur : `SELECT * FROM indexer_state;`
4. Vérifiez que `graphNodeUrl` et `graphSubgraphName` sont définis sur la configuration de la chaîne

## Le déploiement ERC-3643 échoue

**Symptôme** : `POST /api/v1/assets/{id}/deployments` renvoie 500

1. Vérifiez que le portefeuille du déployeur contient de l'ETH pour le gas
2. Vérifiez que `REGISTRY_WALLET_PRIVATE_KEY` est défini
3. Vérifiez que le sous-module T-REX est initialisé : `ls contracts/lib/erc3643/`
4. Recherchez une erreur `Web3j` dans les journaux backend

## Kong renvoie 401 Unauthorized

Kong ne valide pas les JWT — un 401 provient toujours du **backend**, même sur les requêtes
transmises via Kong. Vérifiez, dans l'ordre :

1. `JWT_ISSUER_URI` correspond à l'`iss` que votre fournisseur OIDC renvoie réellement
2. `JWT_AUDIENCE` correspond à l'`aud` du jeton — une incompatibilité ici est la cause la plus courante
3. Pour un jeton d'opérateur, cet `iss` est `registerwerk-local` ; les jetons locaux qui en sont dépourvus
   sont rejetés par conception, donc un jeton fabriqué à la main sans `iss` renverra toujours 401
4. Décodez le jeton pour inspecter ses revendications

Si le jeton est valide et que vous obtenez un **403**, le jeton est correct et c'est le *rôle* qui ne l'est pas —
un problème entièrement différent. Voir [Rôles et autorisations](customers/roles.md).

## Jeton d'intégration expiré

Régénérez-le via l'API :
```bash
curl -X POST http://localhost:48000/api/v1/onboarding/tokens \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -d '{"legalEntityId": "<uuid>", "recipientEmail": "admin@acme.de"}'
```

Les anciens jetons sont invalidés automatiquement (index unique partiel `WHERE used_at IS NULL`).

## Le transfert de jeton confidentiel échoue sur Fhenix

1. Assurez-vous que le client utilise `fhevmjs` pour chiffrer le montant
2. Vérifiez que l'ONCHAINID de l'investisseur a une attestation KYC valide
3. Vérifiez que l'adresse de l'investisseur est en liste blanche dans l'IdentityRegistry du jeton
4. Le testnet Fhenix peut avoir des comptes limités par le faucet — vérifiez le solde de gas
