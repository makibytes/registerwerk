---
title: Journal d'audit
description: Journal d'audit de chaîne de hachage inviolable : schéma, vérification de l'intégrité et gestion des partitions.
---

# Journal d'audit { #audit-log }

Les chemins d'application audités émettent un `AuditEvent` ; la couverture n'est pas encore prouvée pour chaque mutation d'état.
La table `audit_event` est en ajout uniquement, chaînée par hachage et partitionnée par PostgreSQL par mois. Il s'agit uniquement de contrôles techniques
: l'exhaustivité, la conservation, la surveillance opérationnelle et l'adéquation juridique sous
eWpG, GwG, DORA ou RGPD nécessitent des preuves distinctes et un examen externe.

---

## Schéma { #schema }

```sql
CREATE TABLE audit_event (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    sequence_no     BIGINT       GENERATED ALWAYS AS IDENTITY,
    event_type      TEXT         NOT NULL,
    actor_id        UUID,                        -- NULL for system-initiated events
    entity_id       UUID,                        -- The primary entity affected
    asset_id        UUID,                        -- If asset-related
    jurisdiction    TEXT,                        -- Jurisdiction context
    payload         JSONB        NOT NULL,       -- Full event details
    prev_hash       BYTEA,                       -- SHA-256 of previous entry
    entry_hash      BYTEA        NOT NULL,       -- SHA-256(prev_hash ‖ payload ‖ sequence_no)
    signature       BYTEA,                       -- Ed25519 over entry_hash (optional)
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    trace_id        TEXT                         -- OpenTelemetry trace ID
) PARTITION BY RANGE (occurred_at);
```

---

## Chaîne de hachage { #hash-chain }

Chaque `AuditEvent` porte :

- `prev_hash` — le `entry_hash` de la rangée immédiatement précédente (par `sequence_no`)
- `entry_hash` — `SHA-256(prev_hash ‖ canonical_json(payload) ‖ sequence_no)`

Le premier événement de la chaîne a `prev_hash = null` ; son `entry_hash` est `SHA-256(null ‖ payload ‖ 1)`.

```mermaid
graph LR
    E1["seq=1<br/>prev_hash=null<br/>entry_hash=H1"] --> E2["seq=2<br/>prev_hash=H1<br/>entry_hash=H2"]
    E2 --> E3["seq=3<br/>prev_hash=H2<br/>entry_hash=H3"]
    E3 --> En["seq=n<br/>prev_hash=H(n-1)<br/>entry_hash=Hn"]
```

**Détection de sabotage :** Si une ligne est modifiée, son `entry_hash` ne correspondra plus à `SHA-256(prev_hash ‖ payload ‖ sequence_no)`. Le `prev_hash` de chaque ligne suivante sera également erroné. `AuditChainVerificationService.verify()` détecte cela et renvoie le numéro de séquence du premier lien rompu.

---

## Application par ajout uniquement { #append-only-enforcement }

Un déclencheur PostgreSQL sur `audit_event` lève une exception sur tout `UPDATE` ou `DELETE` :

```sql
CREATE TRIGGER audit_event_no_update_delete
BEFORE UPDATE OR DELETE ON audit_event
FOR EACH ROW EXECUTE FUNCTION raise_immutable_exception();
```

Même le superutilisateur de la base de données ne peut pas modifier les enregistrements sans désactiver au préalable ce déclencheur, qui lui-même nécessite une procédure de type « bris de glace » et génère une entrée de journal `pg_audit`.

---

## Ancre quotidienne { #daily-anchor }

Toutes les 24 heures, `AuditChainVerificationService` ajoute un **événement d'ancrage** :

- `event_type = AUDIT_ANCHOR`
- `payload` contient le `entry_hash` du dernier événement de la journée et un horodatage UTC
- En option, le hachage d'ancrage est écrit sur le réseau principal Ethereum sous la forme d'une transaction avec calldata, créant une référence croisée publique et immuable

L'ancre permet aux auditeurs externes de vérifier que la chaîne d'audit à une date donnée correspond à un hachage connu, sans avoir besoin de rejouer l'intégralité de la chaîne depuis la genèse.

---

## Types d'événements { #event-types }

| Type d'événement | Déclencheur |
|---|---|
| `ASSET_CREATED` / `ASSET_DEPLOYED` / `ASSET_STATUS_CHANGED` | Cycle de vie des actifs |
| `KYC_SUBMITTED` / `KYC_APPROVED` / `KYC_REJECTED` / `KYC_EXPIRED` | Flux de travail KYC |
| `HOLDER_BLOCK_CREATED` / `HOLDER_BLOCK_LIFTED` / `HOLDER_BLOCK_EXPIRY_REVIEW` | Sperrvermerk (blocage du titulaire) |
| `SCREENING_RUN_COMPLETED` / `SCREENING_HIT_ACCEPTED` | Contrôle des sanctions |
| `FORCE_TRANSFER` / `FORCE_BURN` / `FORCE_APPROVE` | Opérations de jetons privilégiés |
| `TOTP_ENROLLED` / `TOTP_RESET` / `DUAL_CONTROL_APPROVED` / `DUAL_CONTROL_BOOTSTRAP_USED` / `APPROVAL_REQUEST_CREATED` / `_APPROVED` / `_CLAIMED` | Step-up (authentification renforcée) |
| `ADMIN_IMPERSONATION_STARTED` / `ADMIN_IMPERSONATION_HANDOFF_EXCHANGED` / `ADMIN_IMPERSONATION_ENDED` | Mode support administrateur (impersonation) |
| `ICT_INCIDENT_CREATED` / `ICT_INCIDENT_RESOLVED` | Incidents DORA |
| `REGREPORT_SUBMITTED` | Dépôt MiFIR / DAC8 |
| `NATURAL_PERSON_REDACTED` | Effacement RGPD |
| `AUDIT_ANCHOR` | Ancrage de hachage quotidien |

---

## Gestion des partitions { #partition-management }

`audit_event` est partitionné en plage par `occurred_at` (partitions mensuelles) :

- Partition active : `audit_event_YYYY_MM` pour le mois en cours
- Un travail `@Scheduled(cron = "0 0 1 1 * *")` crée les 6 prochains mois de partitions à l'avance
- `audit_event_default` détecte tous les événements qui se situent en dehors d'une partition définie (ne devrait jamais se produire si le travail s'exécute correctement)

!!! warning "Expiration des partitions"
    Le schéma initial est livré avec les partitions pendant 3 mois. La tâche de création de partition planifiée doit être exécutée avant l'expiration de la dernière partition, sinon les événements tomberont dans `audit_event_default` (ce qui déclenche automatiquement un incident DORA `MEDIUM`).

---

## Vérification de la chaîne d'audit { #verifying-the-audit-chain }

```
GET  /api/v1/audit/chain/status    # dernier résultat enregistré (tâche nocturne ou exécution précédente)
POST /api/v1/audit/chain/verify    # lancer une vérification complète maintenant
```

Les deux exigent `REGISTRY_ADMIN` ou `AUDIT`. La réponse :

```json
{
  "valid": true,
  "rowsChecked": 1847293,
  "firstBrokenSequenceNo": null,
  "checkedAt": "2026-05-22T03:00:00Z",
  "reason": null,
  "status": "VALID",
  "verificationId": "6d1f..."
}
```

Si `valid` vaut `false` (`status` `BROKEN`), `firstBrokenSequenceNo` est le `sequence_no` de la première entrée où la chaîne est rompue et `reason` en donne la cause. Le verdict est persisté et alimente l'indicateur de santé, la jauge `registerwerk_audit_chain_valid` (`1` valide, `0` rompue, `-1` aucune exécution enregistrée) et les alertes `AuditChainBroken`, `AuditChainUnverified` et `AuditChainVerificationStale`.

### Acquitter un verdict BROKEN

Un verdict rompu maintient `/actuator/health` à **DOWN** (la readiness n'est pas affectée) tant que **les deux** conditions ne sont pas réunies : une exécution **ultérieure** est valide, **et** l'exécution rompue a été acquittée :

```
POST /api/v1/audit/verification/{verificationId}/ack?note=<texte libre>
```

L'acquittement est réservé à `REGISTRY_ADMIN` et exige une authentification renforcée **et un deuxième approbateur** (motif `AUDIT_CHAIN_VERIFICATION_ACK`) ; la page du journal d'audit du portail opérateur propose un bouton d'acquittement. Seule une vérification rompue peut être acquittée, et une seule fois. Après une restauration depuis une sauvegarde, lancez `POST /api/v1/audit/chain/verify`, examinez tout résultat BROKEN, puis acquittez-le pour que l'indicateur de santé puisse revenir à UP.

---

## Modèle d'intégrité (canonique v2, ancres, reprise)

- **Version canonique.** Chaque ligne porte `canon_version`. La version 2 couvre `eventType`, le sujet, la charge utile, **l'identifiant et le rôle de l'acteur, l'heure de l'événement (`occurred_at`, époque en microsecondes), l'identifiant de corrélation et le lien d'annulation** : modifier l'un d'eux rompt la chaîne. Les lignes de version 1 (écrites avant ce changement) restent vérifiées selon l'ancien format ; une version inconnue fait échouer la vérification.
- **Heure de l'événement.** `occurred_at` est capturée de façon synchrone à la publication de l'événement, et non lors de l'écriture asynchrone ; `recorded_at` est l'heure d'insertion. Les actions effectuées par un opérateur agissant au nom d'un client (usurpation d'identité) sont enregistrées avec le rôle `REGISTRY_ADMIN_IMPERSONATING` et un objet `_imp` haché (session, opérateur, entité, mode).
- **La vérification** détecte : une première ligne qui n'est pas l'origine de la chaîne (tête tronquée, partition supprimée), une dernière ligne différente de `audit_chain_tip`, des lignes supprimées après une ancre quotidienne signée (`audit_chain_anchor`, publiée en option via un `AuditAnchorSink` externe) et une `entry_sig` absente à partir du seuil de signature (premier numéro de séquence signé, inscriptible une seule fois). Activer la signature plus tard ne signe pas rétroactivement les lignes antérieures.
- **Export de preuve.** `/audit/events/export[/signed]` est trié par `sequence_no` et commence par un bloc `# key=value` (`firstSeq`, `lastSeq`, `rowCount`, `truncated`, `nextAfterSeq`, `tipSeq`, `tipEntryHash`) ; les lignes contiennent `prevHash` et `entryHash`. La signature couvre l'en-tête et les lignes. `afterSeq` permet de poursuivre un export tronqué.
- **Les écritures échouées** sont retentées chaque minute (publications de plus de deux minutes) puis, après `registerwerk.audit.max-attempts` (20) tentatives, déplacées vers `audit_event_dead_letter`. Alertez sur `registerwerk_audit_oldest_incomplete_seconds` et `registerwerk_audit_dead_letter_count`.
- **Propriété de la table.** `REVOKE UPDATE, DELETE, TRUNCATE` et les déclencheurs WORM ne lient pas le propriétaire de la table ; le compte d'exécution ne doit donc pas posséder `audit_event`. Utilisez des comptes distincts : le migrateur/propriétaire (`DB_USER`, transmis à Flyway par `SPRING_FLYWAY_USER`) et le compte d'exécution `registerwerk_app` (`DB_APP_USER`), qui ne détient ni UPDATE, DELETE ou TRUNCATE sur les tables d'audit ni CREATE sur le schéma. En mode production, la vérification au démarrage échoue lorsque le compte d'exécution possède la table ou détient encore ces privilèges, ou lorsque les deux comptes sont identiques ; `registerwerk.audit.allow-owner-runtime-role=true` est une reconnaissance explicite du risque transitoire pour le seul cas du propriétaire. Le mode production exige aussi un fournisseur de clé de signature.
- **Ancre externe.** Les ancres quotidiennes peuvent être publiées dans un bucket S3 avec Object Lock (`registerwerk.audit.anchor-sink=s3`, `none` par défaut), de sorte qu'un attaquant ayant accès à la base ne puisse pas réécrire l'historique des ancres ; les publications échouées sont retentées toutes les heures et comptées (`registerwerk_audit_anchor_sink_failures_total`).
- **Bascule.** `registerwerk.audit.legacy-listener=true` (par défaut) traite les publications créées avant la mise à niveau ; désactivez-le lorsque `event_publication` ne contient plus de lignes d'audit incomplètes.
