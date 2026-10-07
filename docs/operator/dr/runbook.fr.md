---
title: Runbook de reprise après sinistre
description: Runbook opérationnel provisoire pour la restauration de Postgres et du backend, la vérification de la chaîne d'audit et la classification des incidents DORA — en attente d'approbation et de test par l'opérateur.
---

# Runbook de reprise après sinistre

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    This is a draft operational runbook, not evidence of an approved continuity plan, tested RTO/RPO,
    legally correct incident classification, or authority notification. The operator must approve,
    exercise, and reconcile it with current legal, regulatory, contractual, and infrastructure requirements.

**Service:** Registerwerk eWpG Registry  
**RTO :** mesuré 2.3 s sur la base quasi vide de l'exercice (2026-10-07, `scripts/pitr-drill.sh`) ; non mesuré sur des données de taille production, donc aucun RTO n'est promis  
**RPO :** objectif ≤ 15 minutes avec l'archivage WAL activé (`archive_timeout` 300 s) ; le dernier exercice (2026-10-07) a mesuré 286.8 s entre la dernière ligne validée et son segment WAL archivé. Sans archivage WAL (base intégrée dev/test), le RPO est la dernière sauvegarde de base quotidienne, jusqu'à 24 heures  
**Owner:** Registry Operations Team  
**Classification DORA :** voir le tableau illustratif de la section 1

---

## 1. Classification de la gravité des incidents (DORA Art. 17)

| Gravité | Critères | Action |
|---|---|---|
| MINOR | Service unique indisponible, aucune perte de données | Alerte interne |
| MAJOR | Panne multi-services, impact potentiel sur les données | Évaluer l'obligation de déclaration au titre de l'art. 19 DORA |
| CRITICAL | Panne totale OU atteinte à l'intégrité des données | Évaluer l'obligation de déclaration au titre de l'art. 19 DORA |

Ce tableau est une classification illustrative, pas une qualification juridique. Vérifiez-le au regard de l'art. 19 DORA et
des actes délégués sur la classification des incidents (règlement délégué (UE) 2024/1772) et sur le contenu et les délais des
déclarations (règlement délégué (UE) 2025/301). Les seuils de durée et les délais de déclaration ne sont volontairement pas figés
ici : ils relèvent de la politique de l'opérateur (décision reportée T9-06), et les délais légaux courent à partir des moments
que ces actes définissent, non à partir de la « détection ».

`POST /api/v1/dora/incidents` records an internal incident; it does not file a DORA report. Any
authority, deadline, form, and channel below is a review input that must be verified externally:
- DE: BaFin (bafin.de) Referat IT-Risikoaufsicht
- LU: CSSF via CSS portal
- FR: AMF / ACPR via ONEGATE
- LI: FMA via LIMA portal

---

## 2. Restauration complète de Postgres (PITR WAL-G, RPO = dernier segment WAL archivé)

!!! note "Exercé pour de vrai"
    La section 2a est exercée par `scripts/pitr-drill.sh` sur des conteneurs jetables (jamais sur la base de démonstration) : sauvegarde de base, archive WAL continue, perte totale du volume de données, restauration à une heure cible choisie, vérification ligne à ligne. Dernier passage 2026-10-07 : PASSED, échantillon de RPO 286.8 s, RTO 2.3 s (dont `wal-g backup-fetch` 1.5 s) sur une base quasi vide. Le RTO croît avec la taille de la sauvegarde de base et le WAL à rejouer ; à re-mesurer sur des données de taille production avant tout engagement.

!!! warning "Uniquement avec l'archivage WAL activé"
    La restauration à un instant donné exige une archive WAL : le overlay optionnel `docker-compose.wal.yml` (WAL-G), un PostgreSQL géré avec PITR, ou CloudNativePG avec Barman Cloud (voir [Sauvegardes et récupération](../maintenance/backups.md)). La base intégrée dev/test et la démo Compose standard n'archivent rien ; le point de restauration y est la dernière sauvegarde de base ou le dernier pg_dump.

### 2a. Restauration à un instant donné depuis WAL-G (chemin principal) { #2a-point-in-time-restore-from-wal-g-primary-path }

Mécanique PostgreSQL 18 : `PGDATA` vaut `/var/lib/postgresql/18/docker` sur l'image officielle (qui déclare `VOLUME /var/lib/postgresql`), la récupération se demande par un fichier `recovery.signal` vide et la cible se règle dans `postgresql.auto.conf`. Il n'y a plus de `recovery.conf`.

```bash
# 0. Stop writers; keep the failed volume for forensics if it still exists
docker compose stop backend

# 1. Fresh, empty data volume; the archive volume (or the same S3 settings) is reused as is
docker volume create pgdata-restore

# 2. Fetch the base backup and write the recovery settings (what scripts/pitr-drill.sh does)
docker run --rm -i --user postgres -e RECOVERY_TARGET='2026-01-01 12:00:00+00' \
  -v pgdata-restore:/var/lib/postgresql -v <project>_pgarchive:/wal-archive \
  --entrypoint sh registerwerk/postgres-wal:18.6 -s <<'EOS'
set -eu
. /usr/local/bin/walg-env.sh                 # PGDATA + the WALG_* archive location
install -d -m 0700 "$PGDATA"
wal-g backup-fetch "$PGDATA" LATEST </dev/null
touch "$PGDATA/recovery.signal"
cat >> "$PGDATA/postgresql.auto.conf" <<CONF
restore_command = 'wal-g wal-fetch %f %p'
recovery_target_time = '${RECOVERY_TARGET}'   # UTC; omit the line to replay all archived WAL
recovery_target_action = 'promote'
CONF
EOS

# 3. Start Postgres on the restored volume and wait for recovery to finish
docker run -d --name postgres-restore -e POSTGRES_USER=registerwerk -e POSTGRES_DB=registerwerk \
  -e POSTGRES_PASSWORD=<password> -v pgdata-restore:/var/lib/postgresql \
  -v <project>_pgarchive:/wal-archive registerwerk/postgres-wal:18.6
docker logs -f postgres-restore 2>&1 | grep -E "recovery stopping|archive recovery complete|ready to accept"

# 4. Validate
psql -h localhost -U registerwerk -c "SELECT pg_is_in_recovery();"      # f once promoted
psql -h localhost -U registerwerk -c "SELECT max(occurred_at) FROM audit_event;"
```

Avec un stockage S3, remplacez les montages `-v ..._pgarchive` par les variables `WALG_S3_PREFIX` / `AWS_*` du serveur source. Après la promotion, l'instance tourne sur une nouvelle timeline : faites immédiatement une nouvelle sauvegarde de base (`pg-backup once`). Pour les formes de production, utilisez le PITR de la plateforme : PostgreSQL géré (par ex. `gcloud sql instances clone <source> <target> --point-in-time <UTC timestamp>`) ou un cluster de récupération CloudNativePG (`deploy/helm/registerwerk/examples/cnpg-cluster.yaml`, en bas). Ces deux voies ne sont pas exercées par le script de ce dépôt ; réalisez votre propre exercice de restauration.

Les données validées après la cible de récupération (ou après le dernier segment archivé) sont perdues. Comparez `max(occurred_at)` avec l'heure de l'incident pour chiffrer la perte réelle, puis réconciliez avec les indexeurs de chaînes.

### 2b. Restaurer à partir de pg_dump (repli – RPO = dernier dump)
```bash
pg_restore -h new-host -U registerwerk -d registerwerk \
  --clean --if-exists \
  /backups/registerwerk_$(date +%Y%m%d).dump
```

**`scripts/dr-restore-drill.sh` n'automatise que ce chemin de repli pg_dump (`scripts/pitr-drill.sh` couvre le chemin WAL-G de 2a)** (pg_dump du service compose `postgres` en cours d'exécution → restauration dans un conteneur jetable → comparaison du nombre de lignes de chaque table → affichage d'un chiffre de RTO) et, avec `--record-dora <backend-base-url> <bearer-token>`, enregistre le résultat comme une vraie entrée `SCENARIO_BASED` dans `POST /api/v1/dora/resilience-tests` — un exercice de continuité réellement exécuté, et non un espace réservé alimenté par la démo. Il n'exerce volontairement pas 2a, qui a son propre exercice (`scripts/pitr-drill.sh`, ci-dessous). Exécutez-le périodiquement (par exemple chaque trimestre) et après tout changement de schéma touchant les migrations, afin que le « testé » de « continuité testée » reste d'actualité.

Avec `--verify-audit-chain`, il automatise en plus la moitié « chaîne de hachage d'audit » de cette section : il démarre un vrai conteneur backend jetable sur la copie restaurée (HSM désactivé pour ce seul conteneur jetable — il n'a besoin d'aucun mécanisme de portefeuille/signature, seulement de la connectivité à la base — de sorte que l'option fonctionne avec le seul service `postgres` en marche, sans la pile de démonstration complète) et appelle le propre `POST /api/v1/audit/chain/verify` de l'application, au lieu de réimplémenter en bash la canonicalisation SHA-256 de `AuditChainVerificationService`, ce qui risquerait de diverger silencieusement et de donner une fausse assurance. Il exige que `DEFAULT_ADMIN_EMAIL` / `DEFAULT_ADMIN_PASSWORD` soient disponibles (environnement du shell ou `.env` à la racine du dépôt) et correspondent aux identifiants avec lesquels la base *source* a réellement été alimentée ; sans eux, cette étape est signalée `SKIPPED`, et non traitée comme un échec de l'exercice.

**`scripts/pitr-drill.sh` exerce le chemin WAL-G (2a).** Il construit la même image `postgres-wal` que le overlay Compose, lance un serveur jetable avec `archive_mode=on`, prend une sauvegarde de base, insère des lignes avant et après une heure cible enregistrée, attend sans forcer de changement de segment que le segment de la dernière ligne soit archivé (l'échantillon de RPO), supprime le conteneur et son volume, restaure sauvegarde de base plus WAL à l'heure cible et vérifie que seules les lignes validées jusque-là existent. Il affiche le RPO et le RTO et dure environ 6 minutes avec `archive_timeout` 300 s. Avec `--record <backend-base-url> <operator-bearer-token>`, le résultat est enregistré comme entrée `SCENARIO_BASED` via `POST /api/v1/dora/resilience-tests` (désactivé par défaut).

### 2c. Promotion du réplica en lecture (Helm/Kubernetes, `values-production.yaml` uniquement)

`values-production.yaml` exécute en option un réplica Postgres en lecture par réplication en flux à côté du primaire (`postgresql.architecture: replication`) — un secours à chaud en temps réel, et non une sauvegarde périodique. Ce n'est **pas** un basculement automatique : rien ne redirige le `DB_URL` du backend vers le réplica si le primaire tombe, et le réplica reste en lecture seule jusqu'à sa promotion explicite. Si le primaire est perdu et que le réplica est intact, c'est plus rapide qu'une restauration WAL-G complète (§2a) :

```bash
# 1. Confirmer que le retard de réplication du réplica est assez faible pour accepter la perte de données
kubectl exec -it <release-name>-postgresql-read-0 -- \
  psql -U postgres -c "SELECT now() - pg_last_xact_replay_timestamp() AS replication_lag;"

# 2. Promouvoir le réplica hors du mode de récupération
kubectl exec -it <release-name>-postgresql-read-0 -- pg_ctl promote -D /bitnami/postgresql/data

# 3. Pointer le backend vers l'instance promue
kubectl set env deployment/<release-name> \
  DB_URL="jdbc:postgresql://<release-name>-postgresql-read:5432/registerwerk"

# 4. Une fois l'ancien primaire récupérable, reconstruisez-le comme nouveau réplica (NE le laissez PAS
#    revenir comme primaire — lui et l'instance promue ont désormais divergé) ou exécutez un nouveau
#    `helm upgrade` pour que le sous-chart recrée la topologie primaire/réplica de zéro.
```

Toute transaction pas encore diffusée au réplica au moment de la promotion est perdue — c'est un vrai RPO, non nul ; comme pour le chemin WAL-G ci-dessus, le RPO est borné par ce qui a été capturé en dernier (ici : par le retard de réplication, et non par l'archive). Répétez cette promotion dans un espace de noms hors production avant de vous y fier lors d'un incident réel.

### 2d. Lignes dans une partition DEFAULT

`token_transfer`, `blockchain_transaction` et `audit_event` sont partitionnées par mois. Une ligne dont `occurred_at` sort des partitions existantes (source d'indexeur mal horodatée, rattrapage historique) atterrit dans `<table>_default`, et Postgres ne peut plus créer la partition de ce mois. L'alerte `PartitionDefaultRowsPresent` (`registerwerk_partition_default_rows`) se déclenche. Scinder la partition par défaut pendant une fenêtre de maintenance (table, mois et dates sont des exemples) :

```sql
BEGIN;
ALTER TABLE token_transfer DETACH PARTITION token_transfer_default;
CREATE TABLE token_transfer_2031_03 PARTITION OF token_transfer
  FOR VALUES FROM ('2031-03-01') TO ('2031-04-01');
INSERT INTO token_transfer_2031_03 SELECT * FROM token_transfer_default
  WHERE occurred_at >= '2031-03-01' AND occurred_at < '2031-04-01';
DELETE FROM token_transfer_default
  WHERE occurred_at >= '2031-03-01' AND occurred_at < '2031-04-01';
ALTER TABLE token_transfer ATTACH PARTITION token_transfer_default DEFAULT;
COMMIT;
```

À répéter par mois et par table concernés. Aucun job de scission automatique n'existe (il nécessite une décision sur la fenêtre de maintenance).

---

## 3. Restauration du backend
```bash
# Pull signed image (verify Cosign signature first)
cosign verify ghcr.io/makibytes/registerwerk/backend:VERSION

# Deploy with production environment
docker run -d \
  --env-file /etc/registerwerk/prod.env \
  -e REGISTERWERK_PRODUCTION_MODE=true \
  -p 127.0.0.1:48080:8080 \
  ghcr.io/makibytes/registerwerk/backend:VERSION

# Verify health
curl http://localhost:48080/actuator/health | jq .status
```

---

## 4. Vérification de la chaîne d'audit après restauration
```bash
# Trigger the verification: a POST (reading /actuator/health/auditChainVerificationService only
# reports the last verdict, it triggers nothing). Requires REGISTRY_ADMIN; scripts/dr-restore-drill.sh
# --verify-audit-chain shows the full login + XSRF-TOKEN flow.
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:48080/api/v1/audit/chain/verify | jq .
```

Si le verdict est BROKEN : ne PAS reprendre l'exploitation. Escalader en incident CRITICAL ; la rupture de la chaîne de hachage doit être investiguée avant la reprise du registre. Un verdict BROKEN maintient `/actuator/health` à DOWN (la readiness n'est pas affectée) jusqu'à ce qu'une exécution ULTÉRIEURE soit valide ET que le verdict défaillant soit acquitté en double contrôle : `POST /api/v1/audit/verification/{id}/ack` (REGISTRY_ADMIN, step-up, second approbateur, motif `AUDIT_CHAIN_VERIFICATION_ACK`, `note` facultative) ; la page du journal d'audit du portail opérateur a le bouton correspondant. Le déclenchement se fait par POST ; lire `/actuator/health/auditChainVerificationService` ne montre que le dernier verdict.

---

## 5. Matériau de clé Break-Glass (remplace le point de terminaison exportRaw supprimé)

L'accès brut à la clé privée nécessite les trois éléments suivants :
1. Deux parts Shamir sur trois (détenues par le CTO, le CFO et un conseil juridique externe)
2. Résolution du conseil d'administration (préavis minimum de 24 heures au conseil juridique réglementaire)
3. Dossier de bris de glace approuvé et audité en interne. Toute notification à un régulateur est spécifique à l'incident, à l'opérateur et à la juridiction, et doit suivre la procédure approuvée en externe ; ce dépôt de code ne la transmet à aucune autorité.

Accès d'urgence au KMS (AWS KMS) :
```bash
aws kms decrypt \
  --ciphertext-blob fileb://wallet-wrapped-dek.bin \
  --key-id arn:aws:kms:eu-central-1:ACCT:key/KEY_ID \
  --output text --query Plaintext | base64 -d > dek.bin
```

---

## 6. Restauration de Kong/passerelle
Kong fonctionne sans base de données (DB-less) : toute sa configuration est `gateway/kong.yml`, chargée au démarrage (`KONG_DECLARATIVE_CONFIG`). Il n'y a aucune base à restaurer et aucun chemin d'écriture via l'API Admin ; `deck sync` ne s'applique donc pas. Restaurez le fichier depuis le contrôle de version, validez-le et recréez le conteneur :
```bash
docker compose run --rm kong kong config parse /etc/kong/kong.yml
docker compose up -d --force-recreate kong
```
Sur Kubernetes, le même fichier est livré sous `deploy/helm/registerwerk/files/kong.yml` (ConfigMap `registerwerk-kong-config`) : `helm upgrade`, puis redéployer Kong.

---

## 7. Liste de contrôle post-récupération
- [ ] État de Postgres : `pg_isready`
- [ ] État du backend : `/actuator/health` → UP
- [ ] Chaîne d'audit : `POST /api/v1/audit/chain/verify` renvoie `valid: true` (section 4), puis `/actuator/health` → UP
- [ ] Activité des indexeurs : `GET /api/v1/indexers` montre tous les indexeurs à jour, et aucune alerte `IndexerStaleCritical` / `IndexerStaleWarning` n'est active
- [ ] Dérive de la chaîne : confirmer qu'aucune ligne `chain_drift_event` ouverte n'a une gravité = CRITICAL
- [ ] Vérification des sanctions : confirmer qu'aucune ligne `screening_hit` ouverte n'a plus de 4 h
- [ ] Aperçu du registre : vérifier que les montants nominaux totaux correspondent à l'instantané pré-incident
- [ ] Si l'incident a été classé comme majeur, déposer les déclarations DORA dans les délais de l'acte délégué (vérifier le texte en vigueur, section 1)

## 8. Exercices de chaos (Docker Compose)

Deux scripts exercent un vrai comportement de panne/reprise sur la pile Compose en cours d'exécution — pas une simulation — et, pour `chaos-drill.sh`, enregistrent le résultat comme une vraie ligne DORA `ResilienceTest`, le même mécanisme que `dr-restore-drill.sh --record-dora` utilise déjà. Les deux redémarrent ce qu'ils ont arrêté avant de se terminer, mais attendez-vous à un bref redémarrage du backend ; ne les lancez pas sur une pile que d'autres utilisent activement.

```bash
scripts/chaos-drill.sh kill-postgres   # SIGKILL de postgres en plein trafic ; mesurer dégradation + reprise
scripts/chaos-drill.sh kill-backend    # SIGKILL du backend en pleine requête ; mesurer la reprise
scripts/verify-graceful-shutdown.sh    # docker stop (SIGTERM) face aux cas ci-dessus — le cas de contraste
```

**`kill-backend` a révélé, dès sa première exécution, une lacune réelle et jusque-là non documentée** : la politique Docker `restart: unless-stopped` ne redémarre **pas** un conteneur après `docker kill` ou `docker stop` — confirmé par `docker inspect ... RestartCount` resté à 0 après l'arrêt brutal. Elle ne récupère que d'un véritable plantage dans le processus, observé par le runtime de conteneurs lui-même, pas d'une terminaison déclenchée via l'API du moteur. `kill-backend` le mesure désormais honnêtement : il attend 20 s une reprise automatique et, si elle n'a pas lieu, se rabat sur un `docker start` explicite et enregistre le résultat comme `FINDINGS_OPEN`, et non `PASSED`. Le chemin Helm/Kubernetes ne partage pas cette lacune — `restartPolicy: Always` (la valeur implicite d'un Deployment) redémarre un pod après *toute* sortie du conteneur, administrative ou non. Si l'auto-réparation après l'arrêt brutal d'un conteneur compte spécifiquement pour le chemin Compose, c'est un vrai suivi à mener (un superviseur externe, ou l'acceptation de la reprise manuelle comme modèle documenté), et non quelque chose qu'un des scripts masque.

`verify-graceful-shutdown.sh` est le cas de contraste : il envoie une rafale de requêtes concurrentes, émet un vrai `docker stop` (SIGTERM) au milieu de la rafale et confirme que le conteneur se termine de lui-même dans son `stop_grace_period` (35 s, `docker-compose.yml` — aligné sur `spring.lifecycle.timeout-per-shutdown-phase`, `application.yml`) au lieu d'être forcé par l'escalade SIGKILL de Docker, et que les requêtes en cours se terminent au lieu d'être réinitialisées. C'est à cela que sert réellement `server.shutdown: graceful` — un arrêt/recréation normal, pas un plantage — et c'est la raison pour laquelle le service backend de `docker-compose.yml` a besoin d'un `stop_grace_period` explicite : le délai d'arrêt par défaut de Docker (10 s) est plus court que les 30 s que l'application utilise pour son propre drainage.
