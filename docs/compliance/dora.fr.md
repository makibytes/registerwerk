---
title: DORA — Gestion des risques ICT
description: Incidents du prototype ICT, tests de résilience et enregistrements de fournisseurs tiers ; pas une implémentation complète de DORA.
---

# DORA — Digital Operational Resilience Act

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Cette page enregistre les cartographies de contrôle prévues et le comportement actuel du dépôt. Il ne s'agit
    pas d'un conseil juridique ni d'une preuve que DORA s'applique à un opérateur particulier, qu'un cadre de
    contrôle DORA complet existe, ou qu'un incident a été valablement classé ou signalé. L'applicabilité, la
    classification, les délais, les autorités compétentes, les formulaires, les canaux et les preuves nécessitent
    un examen actuel, spécifique à l'opérateur, au service, à l'incident, à la juridiction et au déploiement,
    mené par un conseiller qualifié et les responsables de la résilience et de la conformité.

Le dépôt contient un enregistrement opérationnel manuel pour les incidents ICT, les tests de résilience et les
fournisseurs tiers. Il ne s'agit pas d'une implémentation de déclaration aux autorités.

## Portée et applicabilité

L'applicabilité de DORA ne peut pas être déduite du nom du dépôt, d'une valeur de juridiction `eWpG`, d'un
standard de token, ou de la présence d'un module `dora`. Les capacités réglementées de l'opérateur et les
services effectivement rendus doivent être classifiés en externe avant de s'appuyer sur une quelconque
cartographie de contrôle.

Les affirmations de droit applicable concernant les articles de DORA, les normes techniques, les seuils de
classification et les délais de déclaration doivent être vérifiées par rapport aux sources officielles
actuelles dans le cadre de cet examen.

## Enregistrement d'incident actuel

Un opérateur autorisé peut créer manuellement un `IctIncident` via `POST /api/v1/dora/incidents`. L'entité
actuelle enregistre :

- catégorie : `DATA_BREACH`, `SYSTEM_OUTAGE`, `RANSOMWARE`, `THIRD_PARTY_FAILURE` ou `OTHER` ;
- gravité : `LOW`, `MEDIUM`, `HIGH` ou `MAJOR` ;
- statut : `DETECTED`, `INVESTIGATING`, `CONTAINED`, `RESOLVED`, `REPORTED_TO_AUTHORITY` ou `CLOSED` ;
- description, étiquettes d'événement source, horodatages, cause racine, remédiation, affectation, et une
  référence d'autorité saisie par l'opérateur ;
- des horodatages de rappel calculés par l'application pour les incidents saisis comme `MAJOR`.

Ces valeurs sont des données opérationnelles saisies par l'opérateur. Un statut tel que `REPORTED_TO_AUTHORITY`
ou un `authorityRef` enregistre une affirmation de l'opérateur ; l'application ne vérifie pas indépendamment un
accusé de réception ou une acceptation par l'autorité.

## Suivi des délais

`DoraService` exécute une tâche quotidienne qui interroge les délais applicables dépassés et écrit des messages
de journal. Il expose également des jauges pour les enregistrements en retard. La tâche ne soumet pas de
notification, ne crée pas de rapport au format d'une autorité, ne prouve pas que le délai configuré est
juridiquement correct, et n'informe pas l'ensemble du personnel responsable.

Le modèle actuel ne représente pas un flux de déclaration initial/intermédiaire/final complet. Les opérateurs
ne doivent pas utiliser ses horodatages comme délais légaux sans un examen juridique et réglementaire actuel.

## Détection automatique des incidents — non implémentée

Les événements d'audit interne, de dérive de chaîne, d'indexeur, de RPC ou de filtrage ne sont pas
automatiquement classifiés et convertis en enregistrements `IctIncident`. `sourceEventType` et
`sourceEventRef` sont des champs de corrélation renseignés manuellement, et non la preuve d'un pipeline de
détection automatisé.

## Enregistrements des fournisseurs tiers ICT

L'entité `ThirdPartyProvider` stocke des champs opérationnels incluant le nom, la catégorie, la criticité, le
LEI, le pays, les dates de contrat, les notes de sous-traitance, le contact, le SLA, le RTO/RPO, et un
indicateur de notification géré par l'opérateur. Les enregistrements sont listés via :

- `GET /api/v1/dora/providers`
- `GET /api/v1/dora/providers/expiring`

Cette table ne constitue pas un registre d'informations DORA complet ou approuvé par une autorité. Aucun export
Art. 28 prêt pour l'autorité et validé par schéma n'est implémenté.

## Enregistrements des tests de résilience

Le module peut enregistrer et lister des métadonnées de tests de résilience et mettre en évidence les
enregistrements dont la prochaine date d'échéance configurée est dépassée. Il n'exécute pas de test de
résilience, ne valide pas ses preuves, n'établit pas le périmètre TLPT, et ne certifie pas le résultat.

## Routage vers les autorités et dépôt — non implémentés

Le dépôt n'implémente pas de routage vers les autorités DORA spécifique à une juridiction, de formulaires ou
schémas officiels, de transmission authentifiée, d'accusés de réception, de corrections, de traitement des
rejets, ni d'acceptation par une autorité. Enregistrer qu'un incident a été signalé ne constitue pas une preuve
de dépôt.
