---
title: Registre des revues d'assurance de Registerwerk
description: Le registre de contrôle proposé pour une future revue multidisciplinaire de Registerwerk — pas une preuve qu'une telle revue a eu lieu.
---

# Registre des revues d'assurance de Registerwerk

Dernière mise à jour : 2026-07-29

> **Aucune revue décrite dans ce document n'a eu lieu.** Aucun panel de domaine et aucun conseil informatique
> n'a été convoqué, nommé ou consulté. Chaque entrée ci-dessous a été rédigée par un contributeur automatisé
> en tant que structure de revue *proposée* et auto-évaluation du référentiel. Lisez-la comme un plan pour
> une revue future, jamais comme une preuve qu'une revue a eu lieu. Un changement de code terminé n'est pas
> une certification légale. Les éléments qui dépendent des termes de l'instrument, d'une licence d'opérateur,
> de preuves externes, de la configuration de déploiement ou d'un avocat qualifié restent en suspens.

Ce document propose le registre de contrôle d'une future revue multidisciplinaire de Registerwerk :
ce qui serait revu, par qui, et les preuves que chaque verdict exigerait.

## Protocole de décision proposé

Les panels suivants sont proposés, mais non constitués. Les panels de domaine couvriraient l'émission et
le règlement d'obligations, les paiements, la criminalité financière et la conformité réglementaire, les
crypto-actifs et le trading, l'audit, ainsi que les pensions livrées et prêts de titres. Un conseil informatique
couvrirait la conception et la mise en œuvre logicielles, l'architecture, la SRE, le frontend et la cryptographie.

Dans le cadre de cette proposition, le conseil informatique noterait les propositions de 0 à 2 sur la fidélité
aux invariants juridiques, l'exactitude du grand livre, l'architecture, la sécurité/confidentialité, le cycle
de vie des données, l'UX/accessibilité, l'opérabilité et la vérification. Une proposition serait :

- approuvée à 14–16 points, sans zéro dans les cinq premières dimensions ;
- approuvée avec modifications à 9–13 points ;
- rejetée à 0–8 points.

Le conseil pourrait opposer son veto à un accès inter-locataires, des clés non sécurisées, des montants
monétaires en virgule flottante, un règlement non idempotent, des migrations irréversibles, un double contrôle
affaibli, des saisies non bornées, une gestion manquante de la finalité/réorganisation de chaîne, un rapprochement
non observable, ou un invariant juridique sans critères d'acceptation.

Les statuts proposés pour ce registre sont `PENDING`, `BLOCKED_DECISION`, `APPROVED`, `IN_PROGRESS`, `VERIFIED`,
`DISMISSED` et `RESIDUAL_RISK`. Aucun n'a été attribué par un évaluateur.

## Couverture de la revue

Chaque cellule est `Not performed`. La colonne « portée » enregistre ce qu'une revue *couvrirait*.
L'auto-évaluation automatisée est suivie séparément dans `docs/claims/registry.json`, où elle porte le
statut `SELF_ASSESSED_UNREVIEWED`.

| Phase | Éléments | Revue de domaine | Revue informatique | Mise en œuvre |
|---|---|---|---|---|
| Inventaire | Backend Spring et 31 modules de domaine ; contrats EVM, Cairo et DAML ; indexeurs EVM/Solana/Canton/Starknet/Stellar ; applications Angular opérateur et investisseur plus UI partagée ; relais de jetons confidentiels ; Kong, Compose, Helm et supervision ; documentation | Non effectué | Non effectué | Référence seulement |
| 0 — invariants | Modèle d'acteur/capacité, périmètre de l'instrument, autorité de registre, unités d'actifs et de monnaie, finalité, créances, portes de libération | Non effectué | Non effectué | Partielle, auto-évaluée seulement |
| 1 — autorité et conformité | Authentification, autorisation, organisations, KYC/AML, filtrage, Travel Rule, approbations juridictionnelles, audit, confidentialité, porte d'opération centrale | En attente | En attente | En attente |
| 2 — émission et règlement | Cycle de vie/déploiement des actifs, normes de jetons, contrats d'identité/conformité, registre, paiements, DvP, conservation, indexeurs, opérations sur titres | En attente | En attente | En attente |
| 3 — marchés et reporting | Marketplace/trading, pensions livrées/prêts de titres, oracle et NAV, gestion des obligations, MiFIR, DAC8/KStTG, DORA et profils de juridiction | En attente | En attente | En attente |
| 4 — interfaces utilisateur | UI opérateur, UI investisseur, UI partagée, contrats d'API, accessibilité et présentation sécurisée des transactions | En attente | En attente | En attente |
| 5 — opérations | CI, dépendances, conteneurs, Kong, Helm, secrets, politiques réseau, supervision, sauvegarde/restauration, SLO et runbooks | En attente | En attente | En attente |
| Clôture | Tests complets, preuves de relecture/rapprochement, rapprochement des créances, notes de migration et validation du risque résiduel | En attente | En attente | En attente |

## Modèle canonique de la phase 0

### Autorité et finalité

Chaque instrument doit avoir une décision de périmètre versionnée qui nomme le registre légal, le grand livre
technique, le sens de la projection, le teneur de registre et les preuves requises pour l'effet juridique. Le
choix de la norme de jeton ne doit pas classifier l'instrument.

Le système ne doit pas comprimer ces dimensions en un seul drapeau `SETTLED` :

`INITIATED → EXECUTED → TECHNICALLY_FINAL → CASH_CONFIRMED → REGISTER_POSTED → RECONCILED → LEGALLY_EFFECTIVE`

Pour les instruments eWpG allemands, l'enregistrement actuel du titulaire en base de données n'est que le registre
légal présumé, dans l'attente d'une politique d'autorité spécifique à l'instrument et approuvée par un avocat.
Une transaction en chaîne, à elle seule, ne doit pas être décrite comme un réenregistrement légal. Le Luxembourg,
la France et le Liechtenstein ont besoin de leurs propres décisions par instrument plutôt que d'hériter du modèle
allemand. La base de la distinction allemande est l'actuelle version officielle de l'[eWpG](https://www.gesetze-im-internet.de/ewpg/BJNR142310021.html) ;
les décisions pertinentes en matière de produits et d'opérateur pour la France et le Luxembourg doivent être
vérifiées au regard des [orientations du régime pilote DLT de l'AMF](https://www.amf-france.org/en/news-publications/depth/pilot-regime)
et du [cadre luxembourgeois des titres dématérialisés](https://www.cssf.lu/en/Document/law-of-6-april-2013/).

### Conventions d'unités

| Valeur | Convention canonique |
|---|---|
| Quantité enregistrée | Unités de titres avec un `quantityScale` explicite ; la conversion en unités de base de chaîne nécessite un `tokenDecimals` déclaré |
| Monnaie | Unités majeures ISO-4217 dans les modèles backend, plus exposant de devise explicite et arrondi |
| Valeur nominale de l'obligation | Unités monétaires majeures par unité de titre entière |
| Prix d'émission | Fraction sans dimension de la valeur nominale ; `1.00` signifie 100 % |
| Coupon fixe | Taux annuel décimal ; le coupon est calculé par principal × taux annuel × fraction contractuelle de décompte des jours, arrondi par bénéficiaire |
| Prix de négociation | Unités monétaires majeures par unité de titre entière, avec devise explicite |
| Paiement en jetons | Unités de base exactes de jeton, après conversion décimale vérifiée |
| NAV ERC-4626 | Virgule fixe WAD ; `1e18` désigne une unité de base sous-jacente par unité de base de part |
| Prix de pension | Unités de base de jeton de prêt par jeton de garantie entier à zéro décimale |
| Taux/indice de pension | WAD ; LTV, facteur de réserve et prime de liquidation sont exprimés en points de base |
| Temps | Calendrier/fuseau horaire légal pour les dates contractuelles ; instant UTC et preuve de bloc canonique pour les événements de chaîne |

### Affirmations de référence

| Affirmation | Constat | Disposition requise |
|---|---|---|
| « Entièrement conforme » en DE/LU/FR/LI | Faux en tant qu'affirmation inconditionnelle | Remplacer par des décisions circonscrites, justifiées et à échéance, par instrument et par opérateur |
| Chaque émetteur/destinataire réussit le KYC avant toute action de valeur | Faux | Porte d'opération centrale côté serveur, plus preuves de document examiné/bénéficiaire effectif/filtrage |
| La base de données ou la blockchain fait autorité universellement | Documentation contradictoire | Choisir l'autorité par instrument ; distinguer le registre légal du grand livre technique et de la projection |
| Le reporting MiFIR est prêt pour la production | Placeholder | Mettre la sortie en quarantaine jusqu'à ce qu'existent le peuplement, le schéma RTS 22, la correction/déduplication et le traitement des accusés de réception |
| L'export DAC8 est prêt | Faux/obsolète pour la mise en œuvre allemande actuelle | Reconstruire autour de la diligence sur l'utilisateur déclarable, la résidence fiscale/le TIN, les flux, le routage par juridiction, les corrections et les décisions KStTG |
| Rails de paiement conformes MiCAR | Faux | Traiter comme des attestations d'opérateur tant que les preuves relatives à l'émetteur, la classification, l'autorisation et le rachat n'ont pas été vérifiées |
| Automatisation des incidents DORA | Placeholder | Conserver des registres manuels étiquetés comme tels ; mettre en œuvre la détection, la classification, le routage et les preuves de soumission avant de revendiquer une automatisation |
| Les données personnelles sont chiffrées au repos | Faux pour les colonnes relatives aux personnes physiques | Corriger l'affirmation ou mettre en œuvre un chiffrement au niveau du champ/de l'application, avec cycle de vie des clés et migration |
| Toutes les chaînes/normes sont mises en œuvre | Faux | Starknet/Stellar et toute autre intégration à l'état de squelette doivent être étiquetées placeholder |
| Le DvP sur une même chaîne est atomique | Vérifié uniquement pour les jetons à transfert exact et une seule transaction | Ajouter des contrôles de jambe exacts, des preuves de finalité/réorganisation et un rapprochement avec le registre légal |

## Registre des propositions de la phase 0 (auto-évalué, non revu)

| ID | Proposition | Auto-évaluation | État de suivi | Preuve enregistrée / bloqueur |
|---|---|---|---|---|
| M0-3525-A | Corriger le transfert ERC-3525 en forme d'adresse pour que la source diminue et la destination augmente exactement une fois | Proposé (non revu) | SELF_ASSESSED | Preuve de conservation du contrat uniquement : tests de régression plus suite Foundry complète, 449 réussis / 31 ignorés ; cela ne prouve pas le rapprochement indexé ni le rapprochement avec le registre légal |
| M0-3525-B | Appliquer la politique de pause/gel/liste blanche aux transferts de propriété pleine du jeton | Proposé, modifications notées (non revu) | IN_PROGRESS | Faire respecter chaque garde-fou via le hook de propriété ERC-721, préserver la sémantique de mint/burn à l'adresse zéro et le contournement des opérations forcées, et tester les deux API de transfert ainsi que l'échec atomique de la forme adresse |
| M0-3525-C | Faire respecter des plafonds globaux et par emplacement avec une sémantique cumulatif/en circulation explicite | Bloqué — décision requise | BLOCKED_DECISION | Décider la sémantique cumulatif contre en circulation, la marge de manœuvre pour brûlage/rachat/brûlage forcé, la hiérarchie des plafonds, le comportement de modification et d'abaissement ; rapprocher l'émission/l'encours hérités par emplacement |
| M0-7540-A | Désactiver les `deposit`, `mint`, `withdraw` et `redeem` synchrones hérités ; annoncer des maxima nuls | Proposé (non revu) | SELF_ASSESSED | Toutes les routes synchrones reviennent (revert), les maxima sont nuls, les tests de demande passent ; la suite Foundry complète se termine avec le code 0 |
| M0-7540-B | Lier l'exécution à des métadonnées de strike NAV immuables et à jour | Bloqué — décision requise | BLOCKED_DECISION | Décider la tarification à terme/historique, le calendrier/fuseau horaire de coupure, l'âge maximal, le strike éligible, la correction/le remplacement et l'autorité de valorisation ; les demandes héritées restent `UNVERIFIED_STRIKE` |
| M0-4626 | Faire respecter les métadonnées/fraîcheur de la NAV et un modèle de solvabilité des réserves | Bloqué — décision requise | BLOCKED_DECISION | Décider entre un modèle synchrone adossé au cash et un modèle asynchrone de portefeuille géré, les réserves/la conservation éligibles, le coussin de liquidité, les frais et la forme du rachat |
| M0-REPO-A | Brûler des parts mises à l'échelle arrondies au plafond lors d'un retrait d'actif et rejeter tout mouvement de valeur à parts nulles | Proposé (non revu) | SELF_ASSESSED | Test de limite au-delà de l'indice 1e18, plus invariants de pension : 3 réussis avec 256 exécutions / 5 120 appels chacune |
| M0-REPO-B | Empêcher qu'un retrait/réajout ne valorise un marché plus d'une fois | Proposé (non revu) | SELF_ASSESSED | Test de régression sur le réajout et suite Foundry complète réussie ; `marketCount` reste désormais unique |
| M0-REPO-C | Rendre l'apport, l'emprunt, le remboursement, la liquidation et les sorties complètes conservateurs en parts et sûrs contre les dépassements | Proposé, modifications notées (non revu) | PROPOSED | Utiliser un `mulDiv` sûr contre les dépassements, rejeter les unités comptables nulles, enregistrer la dette de manière prudente, fonder les mouvements partiels de trésorerie/garantie sur les deltas réels de dette et rendre les sorties complètes explicites ; les marchés en direct immuables nécessitent toujours des preuves d'inventaire/de dénouement/de remplacement |
| M0-REPO-RISK | Cadence/dérogation de l'oracle, relation LLTV/prime, facteur de clôture et cascade de créances irrécouvrables | Bloqué — décision requise | BLOCKED_DECISION | Décider le quorum oracle/cadence/dérogation, la relation LLTV/prime, le facteur de clôture/la règle d'obsolescence, la cascade de pertes et les conditions juridiques/de conservation de la garantie ; ne pas modifier ces éléments dans le lot arithmétique |
| M0-DVP | Vérification exacte des jambes de transfert, identifiants de transaction liés à un terme et états de finalité côté backend | Proposé, modifications notées (non revu) | PROPOSED | Lot technique uniquement : deltas de solde des deux comptes, identifiant terme/sel séparé par domaine, et cycle de vie provisoire des événements/accusés de réception ; les droits d'annulation, le seuil de finalité de chaîne et la voie de règlement légal restent des décisions produit |
| M0-BOND | Normaliser les décimales, l'échéance, les droits liés à la date d'enregistrement et le rachat fondé sur la quantité | Bloqué — décision requise | BLOCKED_DECISION | Décider la base de calcul des jours, le calendrier ouvré/fuseau horaire, l'autorité de date d'enregistrement/ex-date, l'arrondi, la retenue/le compte d'attente, le défaut/rappel/amendement et les conditions de rachat partiel ; mettre en quarantaine le desk actuel, à titre de référence uniquement |
| M0-LEDGER | Rendre les transitions de règlement monotones, restaurer l'inventaire exactement une fois et exiger une preuve indépendante de trésorerie/livraison | Proposé, modifications notées (non revu) | PROPOSED | Modèle additif d'état/transition/preuve/réservation ; l'ancien `SETTLED` devient non vérifié, les références de l'acheteur ne peuvent pas faire progresser l'état, et `LEGALLY_EFFECTIVE` reste inaccessible sans une politique d'autorité configurée |
| M0-INDEXER-A | Réparer la parité des signatures de gestionnaire configurées, les événements de déploiement en usine et le rendu d'adresse par composant | Proposé (non revu) | SELF_ASSESSED | Résultat technique limité : 16 ABI de contrats / 71 gestionnaires configurés, moteur de rendu d'adresse, génération de code, build WASM et passage du wrapper de validation uniquement ; cela ne prouve pas l'identité du code déployé |
| M0-INDEXER-B | Ajouter des curseurs provisoires/finaux, un retour arrière en cas de réorganisation et un rapprochement direct avec la chaîne | Proposé, modifications notées (non revu) | PROPOSED | Construire une plomberie fail-closed de rapprochement provisoire/orphelin/rembobinage et de point de contrôle ; aucun événement ne devient `FINAL` avant qu'une politique de chaîne approuvée séparément et une configuration RPC de confiance n'existent |
| M0-INDEXER-C | Suivre la valeur ERC-3525 par jeton/propriétaire/emplacement, le cycle de vie durable des demandes ERC-7540 y compris l'annulation, et l'état des flux de trésorerie de pension à l'échelle/coffre | Proposé, modifications notées (non revu) | SELF_ASSESSED | Les 25 entités disposent d'un statut de projection énuméré ; les historiques incomplets observés en premier restent `INCOMPLETE` ; RepoVault est un flux de trésorerie net d'actif signé, et non le principal ; l'ensemble des contrôles statiques passe. Aucune preuve de rediffusion/finalité n'existe |
| M0-INDEXER-D1 | Prendre en charge chaque instance BondDesk/AMM/RepoVault configurée, mettre à jour la documentation de migration opérateur, et faire compiler les correspondances par la porte de test | Proposé, modifications notées (non revu) | SELF_ASSESSED | Toutes les instances sont explicites ; `NONE` est une assertion de l'opérateur ; un déploiement en direct exige une nouvelle étiquette ; le rechargement du graph-node précède le déploiement ; les blocs par source et le retour arrière non destructif sont documentés et revus de manière croisée |
| M0-INDEXER-D2 | Vérifier le bytecode RPC et le hachage de code d'exécution/l'identité de composant approuvés avant déploiement | Bloqué — décision requise | BLOCKED_DECISION | Nécessite un inventaire par chaîne faisant autorité, des hachages d'artefact/exécution/proxy/administrateur approuvés, des attentes de clés et une politique de rotation ; les contrôles syntaxiques d'adresse ne constituent pas une vérification d'identité |
| F0-001 | Périmètre d'instrument versionné, capacités juridiques, autorisations réglementaires et politique d'autorité du grand livre | Bloqué — décision requise | BLOCKED_DECISION | Décisions d'avocat/d'opérateur par juridiction et par instrument ; F0-002 peut ajouter une coquille de schéma mais ne doit initialiser aucune autorisation générale active |
| F0-002 | `AssetOperationGate` central, appliqué dans les services et les chemins HTTP | Proposé, modifications notées (non revu) | PROPOSED | Instantanés de décision versionnés, circonscrits et à échéance/révocables au niveau de la couche service ; une politique manquante/obsolète/non reconnue refuse sans effet secondaire sur la base de données/la chaîne, et enregistre la corrélation politique/motif/audit |
| F0-003 | Preuves KYC de document examiné, de bénéficiaire effectif, de juridiction et de filtrage récent | Bloqué — décision requise | BLOCKED_DECISION | Décider les listes de contrôle, l'examen/l'acceptation, la cadence, la vigilance renforcée (EDD), l'exhaustivité/la source du bénéficiaire effectif et la conservation ; les documents hérités téléversés restent non examinés et la porte d'opération refuse |
| F0-004 | Conditions économiques explicites et immuables, échelles, devises, calendriers et arrondis | Proposé, modifications notées (non revu) | PROPOSED | Construire uniquement le schéma immuable/versionné et le cadre de conversion/calcul exact ; migrer les conditions actuelles en `LEGACY_UNVERIFIED` et ne pas inventer de conventions d'obligation/NAV |
| F0-005 | Modèle multidimensionnel d'état de règlement et de preuves | Proposé, modifications notées (non revu) | PROPOSED | Même limite sûre que M0-LEDGER ; `LEGALLY_EFFECTIVE` reste inaccessible sans F0-001, et l'ancien `SETTLED` devient `LEGACY_SETTLED_UNVERIFIED` |
| F0-006 | Instruction/accord autorisé et registre chronologique des changements du registre | Bloqué — décision requise | BLOCKED_DECISION | Décider l'autorité d'instruction/d'accord/de correction, les signatures/preuves, le séquencement et l'annulation par type d'entrée/juridiction ; un historique générique en ajout seul ne peut pas autoriser une mutation |
| F0-007 | Finalité de chaîne et rapprochement du bytecode/de l'administrateur/de la configuration déployés | Bloqué — décision requise | BLOCKED_DECISION | M0-INDEXER-B peut ajouter la plomberie provisoire, mais les politiques de finalité/point de contrôle, de RPC de confiance/quorum, d'exécution/proxy/administrateur/propriétaire/clé et de fiabilité juridique restent non résolues |
| F0-008 | Règlement vérifiable paiement/DvP ; désactivation en production des mutations canoniques simulées | Proposé, modifications notées (non revu) | IN_PROGRESS | Réglages/schéma par défaut sur initial et règlement immédiat faux ; les références de parties sont des métadonnées non vérifiées ; combiner des jambes DvP exactes avec une preuve d'adaptateur indépendante, et aucune mutation de titulaire sans trésorerie et livraison vérifiées |
| F0-009 | Instantané de droits verrouillé et paiements d'opérations sur titres vérifiés de manière indépendante | Bloqué — décision requise | BLOCKED_DECISION | Décider l'autorité de date d'enregistrement/ex-date, le fuseau horaire/calendrier, la fiscalité/retenue à la source, le compte d'attente pour titulaire bloqué, les corrections et les paiements en défaut ; les droits hérités restent non vérifiés |
| F0-010 | Interrupteur d'urgence pour le prêt jusqu'à l'existence de contrôles juridiques/de garantie et d'un rapprochement | Proposé, modifications notées (non revu) | IN_PROGRESS | Exposition backend/UI désactivée par défaut et fail-closed ; les nouveaux marchés suspendent par défaut l'apport et l'emprunt tandis que le retrait/remboursement réduisant le risque reste disponible ; les anciens marchés nécessitent inventaire/pause/dénouement/remplacement |
| F0-011 | Mettre en quarantaine les sorties MiFIR et DAC8/KStTG en tant que brouillon/non validé | Proposé, modifications notées (non revu) | SELF_ASSESSED | Désactivé par défaut et interdit s'il est activé en production ; espaces de noms prototype et `DRAFT_UNVALIDATED` ; états/événements de transport uniquement ; 20 tests unitaires/de migration ciblés réussissent, y compris la migration PostgreSQL V17→V18 amorcée. Les schémas officiels, le peuplement, le routage, les accusés de réception et la validation légale restent des bloqueurs |
| F0-012 | Registre d'affirmations lisible par machine, avec preuve, portée, propriétaire, échéance et application par la CI | Proposé, modifications notées (non revu) | SELF_ASSESSED | Schéma/validateur fermé, enregistrement canonique et hachages exacts de texte/fichier, comparaison en ajout seul avec la base, contrôles d'échéance/d'indépendance, une seule exception de migration immuable en liste blanche, analyse fail-closed du référentiel, et preuve de CI bloquante obligatoire — auto-évalués par un contributeur automatisé sans revue externe. Réexécution actuelle : vérificateur/régressions, ERC-3525 (17/17), reporting backend (20/20 y compris migration PostgreSQL), et l'ensemble des portes statiques/génération de code/WASM du sous-graphe passent. Il s'agit de gouvernance, pas de certification légale |

## Preuves de référence

| Surface | Résultat de référence | Constat |
|---|---|---|
| Backend `./mvnw verify -B` | Référence réussie en dehors du bac à sable contraint ; la suite combinée ciblée unité/migration de F0-011 passe 20/20 | Les tâches planifiées continuent après le démontage de l'application de test, génèrent d'importantes erreurs de base de données et retardent l'arrêt du fork ; la couverture JaCoCo réelle est d'environ 45,0 % en lignes / 38,6 % en branches contre une porte de 36 %/23 %, et une documentation contradictoire de 70 % |
| Foundry `forge test -q` | 449 réussis, 31 ignorés après le premier lot approuvé ; réexécution indépendante terminée avec le code 0 | Les tests de régression couvrent désormais la conservation du transfert en forme d'adresse ERC-3525, le contournement synchrone ERC-7540, l'arrondi de retrait de pension et la valorisation unique de marché |
| Cairo `snforge test` | 29/29 réussis | La surface Cairo a encore besoin d'une revue de domaine/sécurité |
| Relais confidentiel | Lint/build TypeScript 6 et 33/33 tests Vitest réussis | Migration Express 5 / ESM terminée ; aucun constat de dépendance |
| Sous-graphe EVM | 16 ABI / 71 gestionnaires, 25 entités de projection, rendu multi-instance, codegen et builds réussis | L'audit de production est propre ; un chemin amont Graph CLI vers `decompress` est isolé par l'allowlist exécutable de `SECURITY-EXCEPTIONS.md` |
| Applications Angular opérateur/investisseur | Lint/build Angular 22 réussis ; 124 tests opérateur et 125 tests client Vitest passent | Le runtime sans zone natif et Angular build/Vitest remplacent Karma |
| Documentation MkDocs | Build strict en cinq langues et tests navigateur réussis | Mermaid, changement de thème et conservation de l'origine/du port lors du changement de langue sont couverts ; audit de production propre |
| DAML | Non exécuté | `dpm` n'est pas disponible dans l'environnement actuel |

## Bloqueurs connus de déploiement et d'opérations

- Helm combine un unique volume de wallet `ReadWriteOnce` avec 3 à 10 répliques anti-affines.
- L'ingress route directement vers le backend et contourne Kong, alors que la politique réseau n'admet pas le chemin du contrôleur d'ingress.
- Les clés secrètes PostgreSQL référencées par Helm ne concordent pas.
- Les JWT du frontend sont stockés dans `localStorage` ; les en-têtes de durcissement des réponses sont incomplets.
- Promtail, les métriques Kong, les alertes de sauvegarde et les hypothèses de pushgateway ne forment pas un chemin de supervision fonctionnel.
- Une clé de déploiement unique brute n'a pas de procédure de transfert multisignature/timelock documentée.
- Il n'existe pas de couverture CI pour le code frontend partagé, le relais, Cairo, DAML, plusieurs indexeurs, la documentation, Compose/Kong, ou Helm.

Ces éléments restent des bloqueurs de mise en production tant que leur verdict de phase et leurs preuves de vérification ne sont pas consignés ici.
