---
title: Sauvegardes et récupération
---

# Sauvegardes et récupération

L'état persistant du registre se trouve à deux endroits :
1. **PostgreSQL** — toutes les données du registre (émissions, entités, journal d'audit, KYC, état de l'indexeur)
2. **S3 / stockage d'objets** — documents KYC de plus de 5 Mo

L'état du contrat intelligent réside sur la blockchain et est intrinsèquement répliqué — il n'a pas besoin d'être sauvegardé séparément.

## Stratégie de sauvegarde PostgreSQL

Les deux chemins de déploiement documentés dans le `CLAUDE.md` du dépôt utilisent deux mécanismes de sauvegarde différents et **non interchangeables**. Suivez la section qui correspond à la façon dont vous exécutez réellement Registerwerk.

### Déploiement Docker Compose — pg_dump manuel

Ceci est la variante de démonstration locale/mono-hôte (voir l'avertissement « Not a production topology » de `CLAUDE.md`), pas une simulation d'une vraie stratégie de sauvegarde : `docker compose up` ne lance aucun service de sauvegarde automatique. Les déploiements réels exécutent PostgreSQL comme service géré (Cloud SQL sur GKE) avec sa propre sauvegarde/PITR ; voir les formes de production ci-dessous. Dump manuel de la base de démonstration locale :

```bash
docker compose exec postgres pg_dump -U ${DB_USER:-registerwerk} --no-owner --no-privileges registerwerk \
  | gzip > registerwerk-$(date -u +%Y%m%d-%H%M%S).sql.gz
```

Ce fichier local n'a ni rétention planifiée ni réplication hors site — c'est un geste manuel ponctuel, pas une stratégie de sauvegarde.

### Déploiement Docker Compose — archivage WAL et PITR en option

La pile par défaut ci-dessus reste inchangée. Le overlay `docker-compose.wal.yml` est une activation explicite qui ajoute l'archivage WAL continu et la restauration à un instant donné avec [WAL-G](https://github.com/wal-g/wal-g) :

```bash
# postgres with archive_mode=on (recreates the container; the pg18data volume is reused as is)
docker compose -f docker-compose.yml -f docker-compose.wal.yml up -d postgres
# daily base backups + retention (profile "backup"); one-shot variant: ... run --rm pg-backup once
docker compose -f docker-compose.yml -f docker-compose.wal.yml --profile backup up -d pg-backup
```

Le overlay remplace l'image par `registerwerk/postgres-wal:18.6` (`postgres-wal/` : la même base `postgres:18.6-alpine` plus WAL-G construit depuis un tag figé, donc un volume existant n'a besoin d'aucun dump/restore) et définit `archive_mode=on`, `archive_command='wal-g wal-push %p'` et `archive_timeout`. Paramètres (environnement ou `.env`) :

| Variable | Défaut | Signification |
|---|---|---|
| `PG_ARCHIVE_TIMEOUT` | `300` | Secondes après lesquelles un serveur actif doit changer de segment WAL et l'archiver. C'est le réglage du RPO : le RPO est borné par cette valeur plus le temps d'envoi |
| `WALG_FILE_PREFIX` | `/wal-archive` | Archive locale (volume `pgarchive`). Même hôte que la base : protège contre la perte logique et la corruption, **pas** contre la perte de la machine |
| `WALG_S3_PREFIX` + `AWS_ENDPOINT`, `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_S3_FORCE_PATH_STYLE` | vide | Archive compatible S3 (prioritaire sur le préfixe fichier) ; le bucket doit déjà exister. Le service `minio` du profil `audit-anchor` convient pour une démo locale |
| `BACKUP_INTERVAL_SECONDS` / `BACKUP_RETAIN_FULL` | `86400` / `7` | Cadence des sauvegardes de base et nombre de sauvegardes complètes conservées |
| `BACKUP_PUSHGATEWAY_URL` | vide | Reçoit `backup_last_success_timestamp` (la métrique de `BackupStale`) après chaque sauvegarde |

Restauration et valeurs mesurées : [runbook DR, section 2a](../dr/runbook.md#2a-point-in-time-restore-from-wal-g-primary-path). Le mode S3 se configure avec les mêmes variables mais n'a pas été exercé par l'exercice (il a utilisé l'archive locale).

### Déploiement Helm/Kubernetes — trois formes de base de données

Le chart principal déclare sur quelle forme repose une release (`database.shape` dans `deploy/helm/registerwerk/values.yaml` ; le chart refuse les combinaisons qui contredisent `postgresql.enabled`, et `NOTES.txt` indique ce que la forme couvre ou non). Objectif des deux formes de production : **RPO ≤ 15 minutes** grâce à l'archivage WAL continu (`archive_timeout` 300 s).

| `database.shape` | PostgreSQL | Point de restauration | Usage |
|---|---|---|---|
| `managed` | Service géré avec PITR (Cloud SQL via `cloudSqlProxy`, RDS, Azure Flexible Server, ...) ; `postgresql.enabled=false` | PITR du fournisseur (archive de journaux continue) | Production |
| `cnpg` | Cluster CloudNativePG avec archivage WAL Barman Cloud vers un stockage objet, exploité hors de ce chart ; `postgresql.enabled=false` | Archive WAL continue (`archive_timeout` 300 s) | Production |
| `bundled` (défaut) | Sous-chart Bitnami, primaire unique | Dernière sauvegarde de base quotidienne (jusqu'à 24 h), pas de PITR | Dev/test uniquement |

#### PostgreSQL géré avec PITR (`database.shape: managed`)

Activez ces points chez le fournisseur ; le chart ne peut pas les vérifier, traitez-les comme une liste de recette :

- Restauration à un instant donné / archivage continu des journaux de transactions activé, avec au moins 7 jours de rétention des journaux (Cloud SQL : `--enable-point-in-time-recovery --retained-transaction-log-days=7` ; RDS : rétention des sauvegardes automatiques d'au moins 7 jours ; Azure Flexible Server : rétention PITR de 7 à 35 jours).
- Sauvegardes quotidiennes automatiques avec assez de sauvegardes conservées pour votre politique de rétention, haute disponibilité (régionale/multi-AZ) et protection contre la suppression activées.
- Ne définissez pas vous-même `archive_mode`/`archive_command` ; le fournisseur les gère. Le RPO est l'intervalle d'envoi des journaux que le fournisseur documente (confirmez la valeur dans le contrat/SLA sur lequel vous vous appuyez).
- Alertez sur la métrique de santé d'archivage/PITR propre au fournisseur et répétez un vrai clone à un instant donné vers une instance de test (`gcloud sql instances clone <source> <target> --point-in-time <UTC timestamp>` sur Cloud SQL) avant d'en avoir besoin. `scripts/pitr-drill.sh` ne pilote pas un service géré.
- Provisionnez une fois le login d'exécution DML uniquement avec `postgres-init/roles/ensure-runtime-role.sh`.

#### CloudNativePG avec Barman Cloud (`database.shape: cnpg`)

`deploy/helm/registerwerk/examples/cnpg-cluster.yaml` est un exemple complet : `ObjectStore` (destination S3), un `Cluster` de 3 instances avec `archive_timeout: "300"`, le plugin Barman Cloud comme archiveur WAL, un volume WAL séparé, `enablePodMonitor` (expose les métriques `cnpg_pg_stat_archiver_*` lues par l'alerte `WalArchiveLag`), un `ScheduledBackup` quotidien et un `Cluster` de récupération commenté avec `recoveryTarget.targetTime`. Installez d'abord l'opérateur et le plugin, appliquez ensuite le manifeste, définissez `database.shape=cnpg` et `postgresql.enabled=false`, puis pointez `env.DB_URL` vers le service `-rw`. Le manifeste n'a pas été appliqué à un cluster réel dans ce dépôt ; validez-le avec vos versions d'opérateur et de plugin.

#### PostgreSQL intégré (`database.shape: bundled`, dev/test)

`deploy/helm/backup/` est un chart Helm distinct (installé comme sa propre release, à côté du chart principal et non fusionné avec lui) qui exécute un `CronJob` WAL-G quotidien envoyant une **sauvegarde de base** de la base intégrée vers S3.

!!! warning "Le RPO est la dernière sauvegarde de base quotidienne"
    La base intégrée n'archive pas le WAL ; le point de restauration est donc la dernière sauvegarde de base réussie (jusqu'à 24 heures) et la restauration à un instant donné n'est pas disponible. Un `wal-g backup-push` en échec fait échouer le job, tout comme un envoi Pushgateway en échec, de sorte que `BackupStale` se déclenche. Pour l'objectif de 15 minutes, utilisez `managed` ou `cnpg`.

Avant l'installation, définissez trois valeurs de chart qu'il ne peut pas déduire :

```bash
helm install registerwerk-backup deploy/helm/backup \
  --set postgresql.host=<main-release-name>-postgresql \
  --set postgresql.pvcName=data-<main-release-name>-postgresql-0 \
  --set postgresql.existingSecret=<main chart's postgresql.auth.existingSecret>
```

Voir `deploy/helm/backup/values.yaml` pour toutes les options (bucket/région S3, rétention, IRSA ou identifiants S3 statiques, URL Pushgateway facultative). [pgBackRest](https://pgbackrest.org/) reste une alternative raisonnable à WAL-G.

### Test des sauvegardes

**Exercice PITR (`scripts/pitr-drill.sh`).** Exécute tout le chemin WAL-G sur des conteneurs et volumes jetables (jamais sur la base de démonstration) : sauvegarde de base, archive WAL, perte totale du volume de données, restauration à une heure cible enregistrée et vérification ligne à ligne. Il affiche l'échantillon de RPO mesuré et le RTO, et dure environ 6 minutes avec l'`archive_timeout` par défaut. `--record <backend-base-url> <operator-bearer-token>` enregistre le résultat comme entrée `SCENARIO_BASED` via `POST /api/v1/dora/resilience-tests` (désactivé par défaut). Dernier passage 2026-10-07 : **PASSED**, échantillon de RPO **286.8 s** (borne : `archive_timeout` 300 s plus temps d'envoi, objectif ≤ 15 min), RTO **2.3 s** (dont `wal-g backup-fetch` 1.5 s) sur une base quasi vide. Le RTO n'est pas une promesse pour la production : il croît avec la taille de la sauvegarde de base et le WAL à rejouer ; relancez l'exercice (et chronométrez la restauration) sur des données de taille production.

```bash
scripts/pitr-drill.sh
scripts/pitr-drill.sh --record http://localhost:48080 "$OPERATOR_TOKEN"
```

Testez votre procédure de sauvegarde et de restauration au moins une fois par mois :

```bash
# Restore a backup to a test database
gunzip -c /backups/postgres/registerwerk-20250401-020000.sql.gz \
  | docker exec -i registerwerk-postgres-1 \
  psql -U registerwerk registerwerk_test
```

Vérifiez que les tables clés sont présentes et que les décomptes de données sont cohérents :

```sql
SELECT 'entities' AS tbl, COUNT(*) FROM entity
UNION ALL
SELECT 'assets', COUNT(*) FROM asset
UNION ALL
SELECT 'deployments', COUNT(*) FROM asset_deployment
UNION ALL
SELECT 'transfers', COUNT(*) FROM token_transfer
UNION ALL
SELECT 'audit_log', COUNT(*) FROM audit_log;
```

## Sauvegarde de documents S3

Activez la gestion des versions S3 et la réplication interrégionale pour votre compartiment de documents KYC :

```bash
# Enable versioning
aws s3api put-bucket-versioning \
  --bucket your-kyc-bucket \
  --versioning-configuration Status=Enabled

# Enable cross-region replication (requires destination bucket in another region)
aws s3api put-bucket-replication \
  --bucket your-kyc-bucket \
  --replication-configuration file://replication.json
```

## Reprise après sinistre

### Restauration complète à partir de la sauvegarde pg_dump (déploiement Docker Compose)

```bash
# Stop the backend to prevent writes during restore
docker compose stop backend

# Drop and recreate the database
docker exec registerwerk-postgres-1 \
  psql -U registerwerk -c "DROP DATABASE registerwerk; CREATE DATABASE registerwerk;"

# Restore
gunzip -c /backups/postgres/registerwerk-latest.sql.gz \
  | docker exec -i registerwerk-postgres-1 \
  psql -U registerwerk registerwerk

# Restart the backend — Flyway will verify the schema
docker compose start backend
```

### Restauration à un instant donné (WAL-G, CloudNativePG, géré)

La procédure PITR pas à pas (PostgreSQL 18 : `recovery.signal` et `recovery_target_time` dans `postgresql.auto.conf`, pas de `recovery.conf`) figure dans le [runbook DR, section 2a](../dr/runbook.md#2a-point-in-time-restore-from-wal-g-primary-path). Pour CloudNativePG, utilisez le `Cluster` de récupération en bas de `examples/cnpg-cluster.yaml` ; pour un service géré, le clone à un instant donné du fournisseur. La base intégrée dev/test ne peut être restaurée qu'à sa dernière sauvegarde de base (`wal-g backup-fetch /bitnami/postgresql/data LATEST` depuis un pod avec le PVC de données monté, après avoir mis le backend à zéro réplica).

### Objectifs de récupération

| Élément | Valeur | Base |
|---|---|---|
| RPO, archivage WAL actif | ≤ 5 min sous activité d'écriture (`archive_timeout` 300 s + envoi) ; objectif ≤ 15 min | Exercice 2026-10-07 : 286.8 s mesurées |
| RPO, base intégrée dev/test | Jusqu'à 24 h (dernière sauvegarde de base quotidienne) | Par construction, pas d'archive WAL |
| RTO, restauration PITR | 2.3 s sur la base quasi vide de l'exercice | Exercice 2026-10-07 ; non mesuré sur des données de taille production |
| RTO, reconstruction complète du serveur | Non mesuré | Aucun exercice ; aucun chiffre n'est promis |
| État des smart contracts | La restauration n'est pas pilotée par la sauvegarde de l'application. Récupérez séparément les projections applicatives et rapprochez-les de la chaîne configurée et du registre juridique propre à l'instrument ; la blockchain ne fait pas universellement foi. |  |

## Surveillance des sauvegardes

`BackupStale` se déclenche désormais si la dernière sauvegarde a plus de 24 h **ou** si la série est absente ; `BackupMetricAbsent` (série absente depuis 2 jours) et `BackupPushStale` complètent la règle. Sans service de sauvegarde (Docker Compose), `BackupStale` se déclenche donc car la série est absente.

`monitoring/alerts/registerwerk.yml` inclut déjà une règle `BackupStale` interrogeant
`backup_last_success_timestamp`. Cette métrique n'existe que si quelque chose la transmet réellement — un
CronJob est éphémère et ne peut pas être récupéré directement (scrapé), donc les deux chemins de sauvegarde la transmettent à un Prometheus
Pushgateway en cas de succès :

- **Helm/Kubernetes (WAL-G)** : définissez `monitoring.pushgatewayUrl` dans `deploy/helm/backup/values.yaml`
— le CronJob pousse automatiquement une fois défini (voir `templates/backup-cronjob.yaml`).
- **Docker Compose (pg_dump)** : ajoutez le push équivalent à la fin de votre script cron :

  ```bash
  curl -s -X POST --data-binary "backup_last_success_timestamp $(date +%s)" \
    "$PUSHGATEWAY_URL/metrics/job/registerwerk_pg_backup"
  ```

Aucun des deux chemins ne fournit de Pushgateway par défaut : ajoutez-en une à la pile de surveillance que vous exécutez
(`monitoring/docker-compose.yml` pour Compose ou une pile à l'échelle du cluster pour Kubernetes) si
vous souhaitez que cette alerte ait des données réelles derrière elle.

L'archivage WAL a ses propres alertes dans le même fichier : `WalArchiveLag` (aucun segment WAL archivé depuis plus de 15 minutes, pendant 5 minutes), `WalArchiveFailing` (`pg_stat_archiver.failed_count` augmente) et `WalArchiveMetricAbsent` (aucune des métriques sources n'est collectée, l'alerte de retard ne pourrait donc jamais se déclencher). Elles lisent l'une de deux sources :

- `cnpg_pg_stat_archiver_seconds_since_last_archival` et `cnpg_pg_stat_archiver_failed_count` issus de la supervision intégrée de CloudNativePG (`enablePodMonitor` ; vérifiez les noms avec votre version de CNPG).
- `pg_wal_archive_last_archive_age_seconds` et `pg_wal_archive_failed_count` issus d'une requête personnalisée sur `pg_stat_archiver` dans votre exporter PostgreSQL (par ex. le fichier de requêtes de `postgres_exporter` ; vérifiez que votre version prend encore en charge les requêtes personnalisées) :

```yaml
pg_wal_archive:
  query: |
    SELECT failed_count,
           COALESCE(EXTRACT(EPOCH FROM (now() - last_archived_time)), 1e9) AS last_archive_age_seconds
    FROM pg_stat_archiver
  master: true
  metrics:
    - failed_count:
        usage: COUNTER
        description: Failed archive_command attempts
    - last_archive_age_seconds:
        usage: GAUGE
        description: Seconds since the last WAL segment was archived
```

Le registre a toujours une activité d'écriture ; avec `archive_timeout`, l'âge de l'archive reste donc sous le délai ; un âge supérieur à 15 minutes signifie que l'archivage est bloqué, pas que la base est inactive. Les services gérés exposent leur propre équivalent : branchez-le sur la même alerte ou alertez sur la métrique du fournisseur. `monitoring/alerts/tests/registerwerk.test.yml` teste ces règles unitairement (`promtool test rules`).
