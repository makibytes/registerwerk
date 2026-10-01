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

### Déploiement Docker Compose — pg_dump

Ajoutez cette tâche cron à votre serveur (ou exécutez-la en tant que conteneur Docker) :

```bash
# /etc/cron.d/ewpg-backup
0 2 * * * root docker exec registerwerk-postgres-1 \
  pg_dump -U registerwerk registerwerk | gzip \
  > /backups/postgres/registerwerk-$(date +%Y%m%d-%H%M%S).sql.gz
```

Conservez les sauvegardes quotidiennes pendant 30 jours, les sauvegardes hebdomadaires pendant 12 mois :

```bash
# Delete daily backups older than 30 days
find /backups/postgres -name "*.sql.gz" -mtime +30 -delete
```

### Déploiement Helm/Kubernetes — sauvegarde de base WAL-G

!!! warning "Le RPO est la dernière sauvegarde de base quotidienne"
    Le WAL n'est **pas** archivé (aucun `archive_command`/`archive_mode` dans le dépôt). Le point de récupération est la dernière sauvegarde de base réussie, jusqu'à 24 heures ; la restauration à un instant donné n'est pas disponible. Un RPO plus court exige une archive WAL (sidecar, `archive_command` ou PostgreSQL géré avec PITR) — décision en suspens (T7-04). Un `wal-g backup-push` en échec, ou un push Pushgateway en échec, fait échouer le job, de sorte que `BackupStale` se déclenche. Il faut aussi définir `postgresql.existingSecret` (Secret contenant le mot de passe de la base) : `--set postgresql.existingSecret=<Secret du chart principal>`.

L'approche Docker Compose pg_dump ci-dessus ne s'applique **pas** ici. `deploy/helm/backup/` est un chart Helm distinct
(installé comme sa propre release, aux côtés — non fusionné dans — le chart principal
`deploy/helm/registerwerk`) qui exécute un `CronJob` WAL-G quotidien archivant des sauvegardes continues
sur S3. Avant de l'installer, vous devez définir deux valeurs de chart qu'il ne peut pas déterminer seul :

```bash
helm install registerwerk-backup deploy/helm/backup \
  --set postgresql.host=<main-release-name>-postgresql \
  --set postgresql.pvcName=data-<main-release-name>-postgresql-0
```

Voir `deploy/helm/backup/values.yaml` pour l'ensemble complet des options (compartiment/région S3, rétention,
IRSA par rapport aux informations d'identification S3 statiques, Prometheus Pushgateway URL en option pour la métrique d'obsolescence
ci-dessous).

Pour l'un ou l'autre chemin, [pgBackRest](https://pgbackrest.org/) ou [Barman](https://pgbarman.org/) restent des alternatives raisonnables si vous préférez ne pas exécuter WAL-G directement.

### Test des sauvegardes

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

### Restauration à partir d'une sauvegarde WAL-G (déploiement Helm/Kubernetes)

!!! warning "Non testé"
    Ce chemin ne restaure que la sauvegarde de base et n'a pas été éprouvé lors d'un véritable exercice wal-g. Voir le [runbook DR](../dr/runbook.md).

```bash
# Scale the backend down to prevent writes during restore
kubectl scale deployment/registerwerk --replicas=0

# Restore the latest base backup + replay WAL to the target point in time
kubectl run wal-g-restore --rm -it --image=ghcr.io/wal-g/wal-g:v3.0.0 \
  --overrides='{"spec":{"volumes":[{"name":"pgdata","persistentVolumeClaim":{"claimName":"<postgresql-pvc-name>"}}]}}' \
  -- wal-g backup-fetch /bitnami/postgresql/data LATEST

# Scale the backend back up — Flyway will verify the schema
kubectl scale deployment/registerwerk --replicas=<original-replica-count>
```

### Objectifs de temps de récupération

| Scénario | RTO (Objectif de temps de récupération) |
|---------------|-------------------------------|
| Redémarrage d'un service unique | < 1 minute |
| Restauration de la base de données à partir d'une sauvegarde quotidienne | < 30 minutes |
| Reconstruction complète du serveur à partir de zéro | < 4 heures |
| État du contrat intelligent | La restauration n'est pas contrôlée par la sauvegarde de l'application. Récupérer séparément les projections applicatives et les réconcilier avec la chaîne configurée et le registre juridique spécifique à l'instrument ; la blockchain ne fait pas universellement autorité. |

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
