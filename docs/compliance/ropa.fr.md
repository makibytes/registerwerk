---
title: Registre des activités de traitement
description: Projet de registre des activités de traitement au titre de l'art. 30 du RGPD.
---

# Verzeichnis von Verarbeitungstätigkeiten (DSGVO Art. 30)

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Ce document du dépôt est un projet d'inventaire et non un registre approuvé ou complet au titre de l'article 30.
    Le responsable du traitement/sous-traitant du déploiement doit établir la portée, les finalités, les bases juridiques,
    les destinataires, les transferts, la conservation, les mesures de sécurité, la propriété, l'approbation et les
    éléments de preuve de la revue.

# Registre des activités de traitement (RGPD Art. 30)

**Responsable du traitement :** [Nom de l'opérateur à renseigner]  
**DPO :** [Contact à renseigner]  
**Dernière mise à jour :** 2026-05-21  
**Version :** 1.0

---

## 1. Intégration des clients & KYC

| Champ | Valeur |
|---|---|
| **Finalité** | Vérification de l'identité du client et intégration pour l'émission de titres financiers électroniques (GwG §10, eWpG §3) |
| **Base juridique** | Obligation légale (DSGVO art. 6(1)(c)) — GwG §10, eWpG |
| **Catégories de données** | Nom de la personne morale, LEI, numéro d'enregistrement, date de constitution, documents KYC (extrait de registre, déclaration des bénéficiaires effectifs, pièces d'identité, résolutions du conseil d'administration), statut KYC |
| **Personnes physiques** | Dirigeants, bénéficiaires effectifs : nom, date de naissance, nationalité, adresse, type/numéro de pièce d'identité, statut PPE/sanctions |
| **Destinataires** | BaFin (DE), CSSF (LU), AMF (FR), FMA (LI) — sur demande réglementaire uniquement |
| **Transferts vers des pays tiers** | Aucun n'est prévu ; AWS S3 (eu-central-1) pour le stockage de documents — clauses contractuelles types |
| **Conservation** | 10 ans après la fin de la relation (eWpG §15(3)) ; 5 ans pour les dossiers KYC (GwG §8) |
| **Mesures de sécurité** | AES-256-GCM au repos ; TLS 1.3 en transit ; accès basé sur les rôles (COMPLIANCE_OFFICER, REGISTRY_ADMIN) ; journal d'audit |

## 2. Registre des titres financiers électroniques

| Champ | Valeur |
|---|---|
| **Finalité** | Tenue du registre des titres financiers électroniques au sens de l'eWpG (Registerführung) |
| **Base juridique** | Obligation légale (art. 6(1)(c)) — eWpG §7, §15, §16, §17 |
| **Catégories de données** | Détenteur de l'actif : adresse de portefeuille, montant nominal, date d'acquisition, statut de liste blanche ; historique des transactions |
| **Personnes physiques** | Identité du détenteur pour les personnes physiques : nom, date de naissance, nationalité, numéro fiscal (via HolderIdentity) |
| **Destinataires** | BaFin (divulgations sur décision de justice) ; émetteur (selon eWpG §15) |
| **Conservation** | 10 ans après le remboursement/l'annulation (eWpG §15(3)) |
| **Mesures de sécurité** | Journal d'audit immuable chaîné par hachage ; déclencheur WORM ; ancrage quotidien ; détection de dérive de chaîne |

## 3. Filtrage des sanctions et PPE

| Champ | Valeur |
|---|---|
| **Finalité** | Filtrage LCB-FT continu au sens du GwG §10 al. 1 n° 5 |
| **Base juridique** | Obligation légale (art. 6(1)(c)) — GwG §10, MiCAR art. 60 |
| **Catégories de données** | Nom de l'entité, LEI, numéro d'enregistrement — comparés aux listes OFAC SDN, PESC de l'UE, ONU 1267, UK HMT, CH-SECO |
| **Sous-traitants** | OpenSanctions (données ouvertes, neutre au regard du RGPD) ; Refinitiv World-Check (DPA requis) |
| **Conservation** | 5 ans (GwG §8) |
| **Mesures de sécurité** | Résultats de filtrage stockés dans une base de données chiffrée ; principe des quatre yeux pour l'acceptation d'une alerte |

## 4. Négociation et traitement des transactions

| Champ | Valeur |
|---|---|
| **Finalité** | Exécution d'opérations sur titres sur les plateformes de négociation (Assetera, Archax, Talos, simulées) |
| **Base juridique** | Nécessité contractuelle (art. 6(1)(b)) ; obligation légale pour la déclaration MiFIR (art. 6(1)(c)) |
| **Catégories de données** | ID du négociateur, identifiant d'entité, offres de vente, enregistrements d'exécution, adresses de portefeuille |
| **Destinataires** | BaFin/AMF — déclarations de transactions MiFIR RTS 22 |
| **Conservation** | 7 ans (MiFIR art. 25(1)) ; 5 ans (GwG) |
| **Mesures de sécurité** | Accès basé sur les rôles (TRADER) ; journal d'audit par transaction |

## 5. Journal d'audit

| Champ | Valeur |
|---|---|
| **Finalité** | Piste d'audit de sécurité et de conformité ; exigence d'intégrité eWpRV §6 |
| **Base juridique** | Obligation légale (art. 6(1)(c)) — eWpG §15, eWpRV §6, DORA art. 9 |
| **Catégories de données** | ID de l'acteur, rôle de l'acteur, type d'événement, ID/type du sujet, contenu (peut inclure des noms d'entités) |
| **Conservation** | 10 ans (eWpG §15(3)) ; ajout uniquement, ne peut pas être supprimé |
| **Mesures de sécurité** | Chaîne de hachage SHA-256 ; déclencheur de base de données WORM ; ancrage quotidien sur une blockchain publique ; rôle de base de données restreint |

## 6. Gestion des utilisateurs opérateur

| Champ | Valeur |
|---|---|
| **Finalité** | Authentification et autorisation du personnel du registre |
| **Base juridique** | Intérêt légitime (art. 6(1)(f)) — sécurité informatique, contrôle d'accès |
| **Catégories de données** | E-mail, mot de passe haché, rôles, dernière connexion, jetons d'action |
| **Conservation** | Durée d'emploi + 2 ans |
| **Mesures de sécurité** | Hachage du mot de passe BCrypt ; JWT (courte durée de vie, 8 h) ; MFA pour les opérations sensibles |

## 7. Déclarations réglementaires (MiFIR, DAC8, Steuerbescheinigung)

| Champ | Valeur |
|---|---|
| **Finalité** | Déclaration obligatoire des transactions aux autorités compétentes |
| **Base juridique** | Obligation légale (art. 6(1)(c)) — MiFIR art. 26, DAC8, EStG §43 |
| **Catégories de données** | Nom de l'investisseur, numéro fiscal, avoirs, transactions, IBAN (pour Steuerbescheinigung) |
| **Destinataires** | BaFin (DE), AMF (FR), CSSF (LU), FMA (LI), BZSt (DAC8/CARF), DGFiP (FR), ACD (LU) |
| **Conservation** | 7 ans (MiFIR) ; 10 ans (eWpG) |
| **Mesures de sécurité** | PDF signés PAdES-B-LT ; SFTP vers les portails des autorités ; accusés de réception de soumission |

---

## Droits des personnes concernées

| Droit | Mise en œuvre |
|---|---|
| Art. 15 Accès | `GET /api/v1/me/dsar/export` |
| Art. 17 Effacement | `POST /api/v1/me/dsar/erasure` — données personnelles neutralisées (tombstone) ; chaîne de hachage d'audit préservée (obligation légale de l'art. 17(3)(b)) |
| Art. 20 Portabilité | `GET /api/v1/me/dsar/export` renvoie du JSON |
| Art. 21 Opposition | Sans objet (base d'obligation légale) |
| Art. 22 Décision automatisée | Aucune décision automatisée ; toutes les approbations KYC sont examinées par des humains |
