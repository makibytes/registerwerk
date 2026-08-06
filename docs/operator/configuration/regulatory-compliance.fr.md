---
title: Portée de conformité réglementaire
---

Cette page définit ce que Registerwerk met en œuvre pour le support de conformité et ce qui reste à la charge de l'opérateur réglementé.

## Avertissement important

Registerwerk est un logiciel permettant la conformité, pas un moteur de détermination juridique. Les obligations réglementaires dépendent de la juridiction, de la portée de la licence et de l'interprétation de l'autorité de supervision.

## Profils de juridiction dans la portée

Registerwerk comprend des identifiants de juridiction et des profils d'exigences KYC configurables pour :

- `DE_EWPG` (Allemagne, BaFin, contexte eWpG)
- `LU_CSSF` (Luxembourg, CSSF)
- `FR_AMF` (France, AMF)
- `LI_TVTG` (Liechtenstein, FMA, contexte TVTG)

Ces profils sont des contrôles opérationnels pour les flux de travail de collecte et d'approbation de documents. Ils ne constituent pas des conseils juridiques et doivent être examinés par les équipes juridiques/de conformité avant utilisation en production.

## Contrôles mis en œuvre par la plateforme

- Évaluation de la liste de contrôle des documents KYC propre à chaque juridiction.
- État d'approbation par juridiction avec expiration et motif de rejet.
- Justification obligatoire (`overrideNote`) pour les approbations lorsque les preuves requises sont manquantes, expirées ou trop anciennes.
- Flux d'événements d'audit immuable pour les soumissions, approbations, rejets et remplacements KYC.
- API dédiée de rapport de remplacement (`/api/v1/audit/reports/kyc-overrides`) pour les comités d'audit.
- Autorisation au niveau API pour les actions KYC sensibles.
- Blocs de construction de conservation des données dans PostgreSQL/S3 avec chemins de récupération contrôlés.

## Contrôles en dehors de la portée de la plate-forme

Les opérateurs restent responsables de :

- Statut de licence et d'enregistrement auprès des autorités compétentes.
- Méthodologie de risque LCB-FT et obligations de déclaration de soupçon.
- Qualité, calibrage et politique d'escalade du prestataire de filtrage des sanctions.
- Normes de vérification du bénéficiaire effectif et suffisance des preuves.
- Qualification juridique MiCA/MiFID/eWpG et obligations de divulgation.
- Gouvernance du droit de la vie privée (base légale, décisions DPIA, mécanismes de transfert, gouvernance DSAR).

## Références réglementaires utilisées pour l'alignement de base

- Allemagne : structure eWpG et obligations d'enregistrement.
- UE : principes du cadre MiCA pour les services de crypto-actifs.
- UE : principes du RGPD pour le traitement licite, la minimisation, la sécurité et la responsabilité.
- Référentiel mondial LCB-FT : recommandations du GAFI (FATF), approche fondée sur les risques.

## Pack de gouvernance de l'opérateur suggéré

Avant la mise en ligne, conservez ces artefacts en dehors du code source et examinez-les périodiquement :

- Mémo juridique juridictionnel concernant la portée du produit et les limites de licence.
- Politique KYC/AML avec matrice d'escalade et niveaux d'autorité d'approbation.
- Procédures opérationnelles de surveillance des sanctions et des transactions.
- Registre des contrôles de protection des données (conservation, contrôle d'accès, réponse aux incidents).
- Processus de gestion des changements pour les mises à jour des profils de juridiction et l'approbation légale.
