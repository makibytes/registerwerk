---
title: Protection des données (DSGVO / GDPR)
description: Inventaire des données personnelles et flux de travail partiels DSAR, avec les lacunes actuelles en matière de chiffrement et de couverture.
---

# Protection des données (DSGVO / GDPR) {#data-protection-dsgvo-gdpr}

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Cette page enregistre les mappages de contrôle de confidentialité prévus et le comportement actuel du référentiel. Il ne s'agit pas d'une évaluation de conformité GDPR/DSGVO, d'un ROPA ou d'un DPIA approuvé, d'une décision de conservation, ou d'une détermination de base légale. Les rôles du contrôleur/processeur, les objectifs, les bases juridiques, l'inventaire des données, la conservation, la gestion des droits et les mesures de sécurité nécessitent un examen spécifique au déploiement par le contrôleur, le DPO, les propriétaires de sécurité et un avocat qualifié.

**Le règlement (UE) 2016/679** (GDPR, ou DSGVO en allemand) s'applique à toutes les données personnelles traitées par les opérateurs de Registerwerk. En tant que registre des valeurs mobilières qui traite les noms, les dates de naissance, les numéros d'identification fiscale, les numéros de passeport et les données financières des personnes physiques, Registerwerk est un responsable du traitement des données (et parfois un sous-traitant) soumis à toutes les obligations du GDPR.

---

## Données personnelles dans Registerwerk {#personal-data-in-registerwerk}

L'emplacement principal des données personnelles est l'entité `NaturalPerson`. Cela inclut :

| Champ | Catégorie GDPR | Objectif |
|---|---|---|
| `givenName`, `familyName` | Données personnelles | Vérification d'identité KYC |
| `dateOfBirth` | Données personnelles | Vérification d'identité KYC |
| `nationality`, `countryOfResidence` | Données personnelles | Contrôle des sanctions, reporting |
| `taxId`, `taxIdCountry` | Données personnelles sensibles | Rapports DAC8/CARF |
| Champs `address` | Données personnelles | Vérification KYC, correspondance documentaire |
| `pepStatus` | Catégorie spéciale (politique) | Diligence raisonnable améliorée |
| Dossiers de documents (passeports, cartes d'identité) | Données personnelles sensibles | Vérification KYC — stockée dans S3 |

---

## Chiffrement au repos — non implémenté pour les champs `NaturalPerson` {#encryption-at-rest-not-implemented-for-naturalperson-fields}

`NaturalPerson` PII est actuellement mappé aux colonnes de base de données ordinaires. Le référentiel n'implémente pas le chiffrement des colonnes au niveau de l'application, les DEK par enregistrement, l'encapsulation KEK ou l'effacement cryptographique pour ces champs. Le chiffrement des volumes de base de données et du magasin d'objets peut être configuré en externe, mais doit être vérifié dans chaque déploiement et ne remplace pas les contrôles au niveau de l'application lorsque ceux-ci sont requis.

---

## Art. 30 — Registres des activités de traitement (ROPA) {#art-30-records-of-processing-activities-ropa}

Le référentiel contient un projet de document ROPA et un inventaire initial des activités de traitement. L'exhaustivité, les bases juridiques, les périodes de conservation, la propriété et l'approbation ne sont pas établies par le référentiel :

| Activité | Base juridique | Rétention |
|---|---|---|
| Vérification d'identité KYC/KYB | Obligation légale (GwG, TVTG, AMF) | Par juridiction (5 à 10 ans) |
| Contrôle des sanctions | Obligation légale | Par juridiction |
| Inscriptions au registre des valeurs mobilières | Obligation légale (eWpG, TVTG) | Par juridiction (5 à 10 ans) |
| Déclaration des transactions (MiFIR) | Obligation légale | Conformément aux règles de conservation MiFIR |
| Déclaration fiscale DAC8 | Obligation légale | Règles par État membre |
| Communication du support client | Intérêt légitime | 3 ans après le dernier contact |
| Journal d'audit | Obligation légale | Par juridiction |

Le brouillon est stocké dans `docs/compliance/ropa.md`. Un déploiement doit désigner un propriétaire, le compléter et l'approuver, enregistrer les preuves de révision et définir une cadence de révision.

---

## Art. 35 — Évaluation de l'impact sur la protection des données (DPIA) {#art-35-data-protection-impact-assessment-dpia}

Le référentiel contient des brouillons de DPIA par juridiction. La nécessité d'un DPIA, ainsi que le caractère complet et approuvé d'un projet, doivent être déterminés pour le déploiement concerné :

- `docs/compliance/dpia-DE.md` — Déploiement eWpG allemand
- `docs/compliance/dpia-LU.md` — Déploiement CSSF luxembourgeois
- `docs/compliance/dpia-FR.md` — Déploiement AMF français
- `docs/compliance/dpia-LI.md` — Déploiement TVTG liechtensteinois

Ces fichiers sont des entrées de révision et non une preuve d'un DPIA.

---

## Art. 17 — Droit à l'effacement (« droit à l'oubli ») {#art-17-right-to-erasure-right-to-be-forgotten}

L'art. 17 du GDPR donne aux personnes concernées le droit de demander la suppression de leurs données personnelles. Toutefois, l'article 17(3)(b) prévoit une exemption pour les données conservées pour se conformer à une obligation légale. Pour Registerwerk :

- Les inscriptions au registre des valeurs mobilières **ne peuvent pas être supprimées** pendant la période de conservation (eWpG §15, TVTG art. 10) — l'exemption pour obligation légale s'applique
- Les documents KYC doivent être conservés pendant la durée de la relation commerciale plus la période de conservation
- Le service d'effacement actuel neutralise (« tombstone ») les champs de contact/authentification `AppUser` sélectionnés après examen par l'opérateur ; il n'efface pas toutes les données personnelles associées à une entité

Comportement actuel :
1. Une demande d'effacement crée un élément de travail opérateur.
2. L'achèvement remplace les valeurs de nom/e-mail `AppUser` sélectionnées, efface le hachage du mot de passe et désactive l'utilisateur.
3. La couverture des documents `NaturalPerson`, KYC, des titres de détention, des transactions et autres données liées est incomplète ; aucun DEK n'est détruit car le cryptage DEK par enregistrement n'est pas implémenté.
4. Des événements de demande/résolution sont émis, mais cela ne prouve pas à lui seul l'effacement complet ou le traitement légal de la demande.
5. Tant que l'entité détient encore des positions actives au registre, l'achèvement exige que l'opérateur désigne le **canal de notification conservé** pour les relevés légaux §19 eWpG ; il est enregistré sur la demande et dans l'événement d'audit. Un relevé §19 ne peut plus être envoyé par e-mail à un utilisateur effacé ou désactivé : il est enregistré comme `FAILED` (`NO_LAWFUL_CHANNEL`), déclenche une alerte d'audit et n'est pas rejoué. La politique applicable à ces titulaires (canal de conservation) est une décision ouverte.

---

## Points de terminaison des droits des personnes concernées {#data-subject-rights-endpoints}

| Droit | Point de terminaison |
|---|---|
| Art. 15/20 — Accès/portabilité | `GET /api/v1/me/dsar/export` — exportation partielle d'entité légale/statut KYC ; il ne s'agit pas d'une exportation complète de données personnelles |
| Art. 16 — Rectification | Aucun flux de travail complet de rectification DSAR n'est documenté ici |
| Art. 17 — Effacement | `POST /api/v1/me/dsar/erasure` — enregistre une demande d'examen par l'opérateur ; les demandes terminées neutralisent (« tombstone ») actuellement uniquement les champs `AppUser` sélectionnés |

Les flux de requêtes et de résolution émettent des événements d'audit. La couverture de bout en bout du DSAR et l'exhaustivité de l'audit restent à vérifier.

---

## Art. 32 — Sécurité du traitement {#art-32-security-of-processing}

Mesures techniques mises en œuvre :

| Mesure | Implémentation |
|---|---|
| Chiffrement en transit | TLS 1.3 sur tous les points de terminaison (Kong + backend) |
| Chiffrement au repos | Le chiffrement des champs `NaturalPerson` n'est pas implémenté ; le chiffrement de la base de données/du magasin d'objets au niveau du déploiement doit être configuré et vérifié séparément |
| Contrôle d'accès | Basé sur les rôles (`@PreAuthorize`) + authentification renforcée (step-up) pour les lectures sensibles |
| Journalisation d'audit | Chaîne de hachage inviolable pour toutes les opérations |
| MFA | WebAuthn / TOTP pour tous les comptes d'opérateur |
| Pseudonymisation | `NaturalPerson.id` (UUID) utilisé dans les références inter-modules au lieu du nom |
| Réponse aux incidents | Des enregistrements manuels des incidents et un suivi des délais existent ; l'automatisation de la notification aux autorités/personnes concernées n'est pas mise en œuvre |

---

## Art. 33/34 — Notification de violation {#art-3334-breach-notification}

En cas de violation de données personnelles :

- Art. 33 : Avertir l'**autorité de contrôle** dans les 72 heures après en avoir eu connaissance
- Art. 34 : Avertir les **personnes concernées** sans délai injustifié si la violation présente un risque élevé

Aucun processus automatisé de notification, à l'autorité de contrôle ou aux personnes concernées, en cas de violation de données n'est mis en œuvre au titre du GDPR. Les opérateurs doivent établir, tester et prouver un processus spécifique au déploiement.

## Cycle de vie de l'entité et périmètre DSAR {#entity-lifecycle}

- **Machine à états :** PENDING_ONBOARDING -> ACTIVE uniquement via l'onboarding ; ACTIVE <-> SUSPENDED (suspension et réactivation exigent step-up, un second approbateur et un motif) ; CLOSED (résiliation) et DISSOLVED (fusion) sont définitifs. La réactivation est refusée tant que le KYC est expiré/rejeté, qu'une alerte de filtrage est non résolue ou qu'un Sperrvermerk est actif.
- **La résiliation** est refusée tant que des obligations sont ouvertes (titres émis, trades non réglés, positions repo/lending, opérations sur titres et transferts de registre en cours, positions sous Sperrvermerk), sauf si chacune est confirmée avec un motif. Les obligations confirmées deviennent des tâches de suivi (`/api/v1/entity-tasks`, jauge `registerwerk_offboarding_open_tasks`) ouvertes jusqu'à clôture par un opérateur. Les utilisateurs sont désactivés, leurs sessions et jetons d'action inutilisés révoqués.
- **On-chain :** la suspension suspend l'org sur chaque chaîne ; la résiliation et la fusion révoquent en plus les claims KYC/AML. Une réactivation ne rétablit rien automatiquement on-chain ; elle crée la tâche `CHAIN_REINSTATEMENT_REQUIRED` pour le rétablissement en double contrôle.
- **Modifications de données :** un changement de nom, LEI ou pays relance le filtrage et crée une tâche `KYC_REVIEW_REQUIRED` pour les entités APPROVED (le statut KYC n'est pas modifié automatiquement). L'abaissement de la catégorie client et les fusions exigent un second approbateur et un motif ; l'événement d'audit contient l'avant et l'après.
- **DSAR :** l'export ne couvre que les données de base de l'entité et indique ce qui n'est pas inclus. Un effacement se termine en `COMPLETED_PARTIAL` avec les listes effacé, conservé (base légale) et non couvert. L'application des durées de conservation est manuelle ; rien n'est purgé automatiquement (durées = décision juridique ouverte).
