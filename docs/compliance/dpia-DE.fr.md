---
title: DPIA — Allemagne
description: Analyse d'impact relative à la protection des données (DPIA) en projet pour la juridiction DE_EWPG — nécessite la validation du DPO et d'un conseiller juridique avant la mise en production.
---

# Datenschutz-Folgenabschätzung (DSGVO Art. 35)

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Il s'agit d'une ébauche de dépôt, et non d'une DPIA approuvée. Le responsable du traitement pour ce déploiement
    et le DPO doivent établir la portée, la nécessité, la proportionnalité, les risques, les mesures d'atténuation,
    les exigences de consultation, la propriété, l'approbation et les éléments de preuve de la revue avant de s'y
    fier.

# Analyse d'impact relative à la protection des données — Juridiction DE_EWPG

**Système :** Registerwerk  
**Juridiction :** DE — eWpG / BaFin / GwG  
**DPO :** [À remplir]  
**Date :** 2026-05-21  
**Statut :** DRAFT — nécessite la validation du DPO et d'un conseiller juridique avant la mise en production

---

## 1. Nécessité et proportionnalité

**Traitement :** Registerführung (tenue du registre) pour les titres financiers électroniques tokenisés au sens de l'eWpG.

**Nécessité :** Légalement requis. L'eWpG §7 impose le registre central. Le GwG §10 impose le KYC. L'eWpG §15 impose une conservation de 10 ans. Le traitement ne peut pas être réduit en deçà de ces minimums légaux.

**Proportionnalité :** Les données collectées correspondent au minimum requis par l'eWpG et le GwG. Les données à caractère personnel des personnes physiques sont limitées aux dirigeants et aux bénéficiaires effectifs (seuil GwG §3 ≥ 25 %). Les données personnelles des investisseurs ne sont collectées que lorsque l'investisseur est une personne physique.

---

## 2. Évaluation des risques

| Risque | Probabilité | Gravité | Risque résiduel | Contrôle |
|---|---|---|---|---|
| Divulgation non autorisée de données KYC à caractère personnel | Moyenne | Élevée | LOW | Accès basé sur les rôles ; AES-256 au repos ; TLS 1.3 ; journal d'audit |
| Falsification du journal d'audit | Faible | Critique | LOW | Chaîne de hachage SHA-256 + déclencheur WORM + ancrage public quotidien |
| Compromission de la clé de portefeuille | Faible | Critique | LOW | Chiffrement d'enveloppe KMS ; pas de point de terminaison exportRaw ; accès journalisé |
| Sanction non détectée lors du contrôle | Faible | Élevée | LOW | Réexamen quotidien ; double liste (OpenSanctions + Refinitiv) ; acceptation à quatre yeux |
| Violation de données (piratage) | Faible | Élevée | MEDIUM | Isolation réseau ; WAF (détection de bots Kong + restriction IP) ; test d'intrusion annuel |
| Suppression illicite d'inscriptions au registre | Très faible | Critique | LOW | Déclencheur WORM ; journal d'audit immuable ; séparation des rôles en base de données |
| Transfert transfrontalier sans garanties | Faible | Moyenne | LOW | AWS eu-central-1 ; clauses contractuelles types |
| Retards de traitement des demandes d'accès des personnes concernées | Faible | Faible | LOW | Points de terminaison DSAR sur /api/v1/me/dsar/ |

**Niveau de risque global :** MEDIUM — atténué par les contrôles décrits dans le registre des activités de traitement (ROPA).

---

## 3. Activités de traitement à haut risque

| Activité | Déclencheur Art. 35 | Résultat de la DPIA |
|---|---|---|
| Données des bénéficiaires effectifs (PPE, statut au regard des sanctions) | Catégorie particulière potentielle (indicateur proxy d'opinion politique) | Justifié par l'art. 6(1)(c) obligation légale ; art. 9(2)(g) intérêt public substantiel |
| Journal d'audit — non supprimable | Exception de l'art. 17(3)(b) appliquée | Justifié : l'eWpG §15(3) impose une conservation de 10 ans ; documenté dans l'avis d'information |
| Identité des investisseurs (personnes physiques) | Traitement à grande échelle | Réduit au minimum : adresse de portefeuille + montant nominal uniquement, sauf investisseur personne physique |

---

## 4. Mesures visant à traiter les risques

1. **Minimisation des données :** seules les données requises par l'eWpG/le GwG sont collectées.
2. **Chiffrement :** AES-256-GCM pour les documents + enveloppe KMS pour les clés de portefeuille.
3. **Contrôle d'accès :** rôle `COMPLIANCE_OFFICER` pour le KYC ; `REGISTRY_ADMIN` avec MFA pour les opérations sensibles.
4. **Application de la conservation :** `KycMonitoringJob` applique l'expiration ; effacement automatisé à la demande de la personne concernée via `POST /api/v1/me/dsar/erasure` (tombstone des données personnelles ; hachage d'audit préservé).
5. **Réponse aux incidents :** classification des incidents DORA dans `ict_incident` ; notification de violation sous 72 h conformément à l'art. 33 DSGVO.
6. **Droits des personnes concernées :** points de terminaison DSAR mis en œuvre ; SLA de réponse de 30 jours.
7. **Consultation du DPO :** cette DPIA nécessite l'examen du DPO avant le début du traitement.

---

## 5. Consultation du DPO

**Nom du DPO :** [À remplir]  
**Date de validation du DPO :** [À remplir]  
**Avis du DPO :** [À remplir]

---

## 6. Signature

| Rôle | Nom | Date |
|---|---|---|
| DPO | | |
| Conseiller juridique | | |
| CTO | | |
| Directeur général | | |

*Cette DPIA doit être révisée chaque année et lors de tout changement significatif des activités de traitement.*
