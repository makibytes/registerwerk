---
title: DAC8 / CARF
description: Prototype d'exportation des avoirs DRAFT_UNVALIDATED ; il ne s'agit pas d'une implémentation de dépôt DAC8, CARF ou KStTG.
---

# Prototype d'exportation des avoirs au format DAC8 / CARF

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Le statut d'entité déclarante, la portée, les utilisateurs et crypto-actifs à déclarer, les obligations de
    diligence raisonnable, les périodes, les délais, la juridiction, l'autorité compétente, les corrections et la
    conservation nécessitent un examen actuel, spécifique à l'opérateur, au client, à l'actif, à la transaction, à
    la juridiction et au déploiement, mené par un conseiller fiscal/juridique qualifié et le responsable déclarant.
    Cette page ne constitue pas un conseil juridique ou fiscal et n'établit pas la conformité DAC8, CARF ou KStTG.

!!! danger "DRAFT_UNVALIDATED — NE PAS DÉPOSER"
    Le résultat actuel est un prototype d'avoirs incomplet, construit à la main. Il n'est pas validé par rapport
    à un schéma officiel DAC8, CARF ou KStTG et ne doit pas être utilisé pour un dépôt légal. La génération, le
    stockage d'objets, le hachage ou le transport SFTP ne signifient pas qu'un rapport a été déposé, accusé
    réception, accepté ou juridiquement complet.

## Comportement actuel du dépôt

`Dac8ExportService` s'exécute le 31 janvier pour une année antérieure demandée et peut être déclenché à la demande.
Pour chacune des quatre étiquettes de juridiction configurées, il :

1. interroge les soldes des détenteurs actuellement stockés pour les actifs émis ;
2. compte les lignes de transfert de jetons entrants pour chaque détenteur sélectionné ;
3. construit un petit document XML en utilisant l'année demandée comme métadonnées de rapport ;
4. stocke les octets générés et un hachage ; et
5. appelle la passerelle générique configurée.

La requête ne reconstitue ni un instantané au 31 décembre, ni les flux annuels d'acquisition/de cession.
Chaque document généré et l'enregistrement de suivi associé doivent être traités comme `DRAFT_UNVALIDATED`,
quels que soient les noms de statut hérités de la base de données.

## Contrôles de diligence raisonnable et de population manquants

Le prototype n'implémente actuellement pas :

- une classification d'entité déclarante/CASP ou une décision de périmètre KStTG allemande ;
- la diligence raisonnable des utilisateurs à déclarer et la classification des personnes détenant le contrôle ;
- une collecte complète de la résidence fiscale et du NIF, sa validation, les codes de motif ou l'autocertification ;
- la classification des crypto-actifs à déclarer et leurs exclusions ;
- l'agrégation annuelle brute des acquisitions, cessions, échanges, transferts, ou de la juste valeur de marché ;
- un instantané de solde de fin d'année fiable ;
- une sélection de population spécifique à la juridiction ou un routage vers la juridiction partenaire ; ou
- le traitement des corrections, annulations, déclarations néant, doublons et déclarations tardives.

La même population de prototype est actuellement émise sous plusieurs étiquettes de juridiction. Un
`crossBorderIndicator`, le traitement complet des juridictions partenaires CRS, et les champs
utilisateur/entité à déclarer précédemment décrits dans cette documentation ne sont pas implémentés.

## Données cibles — non implémentées actuellement

L'identité fiscale, la résidence, la personne détenant le contrôle, la classification des actifs, le type de
transaction, la valorisation, la devise, l'agrégation annuelle et les champs de fin d'année sont des exigences
cibles pour une analyse externe ; leur présence dans une table de conception ne doit pas être présentée comme un
mappage de source actuel.

## Limite XML et transport

Le service génère du XML construit à la main et n'établit pas de conformité avec un schéma ou des règles
métier officiels de l'OCDE, de l'UE ou allemands. Il n'existe pas d'adaptateurs de portail spécifiques à une
autorité, ni de traitement authentifié des accusés de réception.

La passerelle générique peut être `NOOP` ou SFTP. Un téléversement SFTP réussi prouve uniquement que des octets
ont été transportés vers un serveur configuré. Il ne prouve ni la livraison à une autorité fiscale, ni le dépôt
légal, ni l'accusé de réception, ni la validation, ni l'acceptation. Les statuts hérités tels que `SUBMITTED`,
`PENDING_ACK`, `ACCEPTED` ou `REJECTED` ne doivent pas être présentés comme des résultats d'autorité sans un
accusé de réception d'autorité authentifié et analysé de manière indépendante.

## Calendrier et droit applicable

Ne vous fiez pas aux déclarations historiques selon lesquelles la première année de déclaration était 2025 ou
que les portails des États membres étaient encore en cours de mise en œuvre en 2025. Les périodes applicables,
les exigences KStTG allemandes, les dates de dépôt, les schémas, les portails et les règles transitoires
doivent être vérifiés par rapport aux sources officielles actuelles lors de l'examen externe.

## Relation avec MiFIR

La déclaration de transactions MiFIR et la déclaration fiscale DAC8/CARF/KStTG ont des périmètres juridiques,
des populations, des données, des destinataires, des périodes et des processus de correction différents. Le
partage d'une table de persistance ou d'une interface de transport ne démontre pas la conformité de l'un ou
l'autre des prototypes à son régime cible.

## Condition de mise en production

L'utilisation en production reste bloquée tant que le périmètre de déclaration, le modèle de diligence
raisonnable, les données sources complètes et les instantanés historiques, la validation du schéma officiel et
des règles métier, le canal certifié par l'autorité, le cycle de vie des accusés de réception authentifiés, le
modèle de correction, la responsabilité opérationnelle et la validation juridique/fiscale ne sont pas
implémentés et vérifiés de bout en bout.
