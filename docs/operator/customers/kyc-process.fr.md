---
title: Processus KYC
---

Cette page décrit le flux de travail de l'opérateur pour les enregistrements KYC et les décisions de liste de contrôle tenant compte de la juridiction. Ces contrôles ne sont pas encore appliqués uniformément sur chaque chemin d'émission, de déploiement, de réception ou de transfert.

## Portée et modèle

Registerwerk stocke :

- Statut KYC au niveau de l'entité (`legal_entity.kyc_status`) pour le cycle de vie d'intégration générique.
- Approbations KYC au niveau de la juridiction (`kyc_jurisdiction_approval`) pour l'éligibilité à l'émission dans le cadre de régimes spécifiques.
- Documents étiquetés par juridiction (`kyc_document.jurisdiction`) où `null` signifie des preuves universelles pouvant satisfaire plusieurs juridictions.

## Gestion des documents

- Les fichiers inférieurs à 5 Mo sont stockés dans PostgreSQL.
- Les fichiers de 5 Mo et plus sont stockés dans un stockage d'objets compatible S3.
- Les actions d'accès et de changement d'état sont écrites dans la piste d'audit.

## Téléchargement des preuves KYC

```bash
curl -X POST http://localhost:8000/api/v1/entities/{entityId}/kyc/documents \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -F "file=@certificate.pdf" \
   -F "documentType=INCORPORATION_CERTIFICATE" \
   -F "jurisdiction=DE_EWPG"
```

Si `jurisdiction` est omis, le document est traité comme universel.

## Liste de contrôle de conformité avant approbation

Avant l'approbation de la juridiction, le backend calcule la conformité par rapport au profil configuré pour cette juridiction.

```bash
GET /api/v1/entities/{entityId}/kyc/compliance/{jurisdiction}
```

La réponse comprend :

- `missingCount`
- `expiredCount`
- `tooOldCount`
- `fullyCompliant` (un nom au niveau du code signifiant aucune lacune dans la liste de contrôle configurée, pas de conformité légale)

## Approbation du KYC par juridiction

Modèle d'autorisation :

- `ROLE_COMPLIANCE_OFFICER` : peut approuver uniquement si `fullyCompliant=true` pour la liste de contrôle configurée.
- `ROLE_REGISTRY_ADMIN` : peut approuver les cas avec ou sans lacunes dans la liste de contrôle configurée.
- Approbation avec lacunes dans la liste de contrôle nécessite `overrideNote` et est rejetée pour les utilisateurs non-administrateurs.

Approbation sans lacunes dans la liste de contrôle configurée :

```bash
curl -X POST http://localhost:8000/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/approve \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -H "Content-Type: application/json" \
   -d '{"expiresAt":"2027-01-31"}'
```

S'il existe des lacunes dans la liste de contrôle, l'approbation est bloquée à moins qu'une note de remplacement explicite ne soit fournie :

```bash
curl -X POST http://localhost:8000/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/approve \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -H "Content-Type: application/json" \
   -d '{"overrideNote":"Approved by compliance officer after manual source-of-funds review."}'
```

Lorsque le remplacement est utilisé, la note est stockée dans `kyc_jurisdiction_approval.override_note` et les compteurs de conformité sont inclus dans l'événement d'audit.

## Workflow de rejet

```bash
curl -X POST http://localhost:8000/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/reject \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -H "Content-Type: application/json" \
   -d '{"reason":"Missing certified beneficial ownership register extract."}'
```

## Attestations on-chain et contrôles de jetons

L'approbation de la juridiction est disponible pour les flux de travail back-end, mais une porte d'opération centrale à rejet par défaut (fail-closed) n'est pas encore appliquée uniformément. Les contrats ERC-3643 et les attestations ONCHAINID fournissent des restrictions distinctes au niveau du contrat lorsqu'elles sont configurées ; ils ne prouvent pas l'application complète du KYC ou l'éligibilité légale.

## Note réglementaire

Cet ensemble de fonctionnalités prend en charge la preuve des contrôles et de l'auditabilité du KYC. Il ne s'acquitte pas à lui seul des obligations de licence, de signalement d'activités suspectes, de Travel Rule ou de surveillance locale.

## Rapports de remplacement

Les auditeurs et les administrateurs peuvent lister les approbations avec remplacement par juridiction et par période :

```bash
GET /api/v1/audit/reports/kyc-overrides?jurisdiction=DE_EWPG&from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z
```
