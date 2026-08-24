---
title: Déclaration de transactions MiFIR
description: Prototype d'exportation de transactions au format MiFIR (DRAFT_UNVALIDATED) ; il ne s'agit pas d'une implémentation de dépôt RTS 22.
---

# Prototype d'exportation de transactions au format MiFIR {#mifir-shaped-transaction-export-prototype}

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    La classification de l'instrument, le statut d'entité déclarante, la déclarabilité, les exemptions, les
    délais, l'autorité compétente, la voie de soumission, les obligations de correction et la conservation
    nécessitent un examen actuel, spécifique à l'opérateur, à l'instrument, à la transaction, au lieu
    d'exécution, à la juridiction et au déploiement, mené par un conseiller qualifié et le responsable
    déclarant. Cette page ne constitue pas un avis juridique ni une preuve de conformité MiFIR.

!!! danger "DRAFT_UNVALIDATED — NE PAS DÉPOSER"
    Le résultat actuel est un prototype incomplet, construit à la main. Il n'est pas validé par rapport à un
    schéma officiel RTS 22 et ne doit pas être utilisé pour un dépôt légal. La génération, le stockage
    d'objets, le hachage ou le transport SFTP ne signifient pas qu'un rapport a été déposé, accusé réception,
    accepté ou juridiquement complet.

## Comportement actuel du dépôt {#current-repository-behavior}

`MifirReportingService` s'exécute selon une planification et peut être déclenché via
`POST /api/v1/regulatory-reporting/mifir/generate`. Pour chacune de ses étiquettes d'autorité configurées, il :

1. sélectionne les lignes d'exécution de transaction créées à la date demandée pour les actifs émis, dans le
   sous-ensemble de juridictions DE/FR codé en dur ;
2. construit un petit document XML contenant un ensemble limité d'identifiants, ainsi que les valeurs de
   quantité, de prix et d'horodatage ;
3. stocke les octets générés et un hachage ; et
4. appelle la passerelle générique configurée.

Chaque document généré et l'enregistrement de suivi associé doivent être traités comme `DRAFT_UNVALIDATED`,
quels que soient les noms de statut hérités de la base de données.

## Contrôles de population manquants {#missing-population-controls}

Le prototype n'applique pas actuellement :

- une décision de classification ou de déclarabilité MiFID II/MiFIR au niveau de l'instrument ;
- la capacité de l'entité déclarante ou du lieu d'exécution ;
- le statut de règlement ;
- les exemptions de transaction ;
- l'identification de l'acheteur/du vendeur et du décideur requise par le régime cible ;
- la déduplication des déclarations antérieures, les corrections, les annulations ou le traitement des
  déclarations tardives ; ou
- le routage complet vers la juridiction et l'autorité compétente.

La sélection utilise `TradeExecution.created_at` ; il ne s'agit pas d'une population fondée sur la date de
règlement ou sur une exécution confirmée de manière indépendante.

## Champs cibles — non implémentés actuellement {#target-fields-not-currently-implemented}

Des champs tels que les identifiants de l'acheteur et du vendeur, l'identité de l'entreprise déclarante, le MIC
du lieu d'exécution, la capacité, les données du décideur, les indicateurs de vente à découvert, les champs de
dérogation/marchandise et les autres contenus cibles du RTS 22 restent des exigences cibles. Leur mention dans
un document de conception ne doit pas être lue comme un mappage de source actuel.

## Limite XML et transport {#xml-and-transport-boundary}

Il n'existe pas d'implémentations `MifirFilingStrategy` par juridiction, ni d'adaptateurs de dépôt certifiés par
une autorité. Le service génère du XML construit à la main ; il ne prouve pas la conformité au schéma, aux
règles métier, aux données de référence, ni à la signature.

La passerelle générique peut être `NOOP` ou SFTP. Un téléversement SFTP réussi prouve uniquement que des octets
ont été transportés vers un serveur configuré. Il ne prouve ni la livraison à l'autorité compétente, ni le
dépôt légal, ni l'accusé de réception, ni la validation, ni l'acceptation. Les statuts hérités tels que
`SUBMITTED`, `PENDING_ACK`, `ACCEPTED` ou `REJECTED` ne doivent pas être présentés comme des résultats
d'autorité sans un accusé de réception d'autorité authentifié et analysé de manière indépendante.

La nouvelle tentative automatique en trois essais, l'ingestion des accusés de réception spécifiques à
l'autorité, la correction des rejets et la notification du régulateur ne sont pas implémentées.

## Condition de mise en production {#release-condition}

L'utilisation en production reste bloquée tant que le périmètre de déclaration, les données sources complètes,
la validation du schéma officiel et des règles métier, le canal certifié par l'autorité, le cycle de vie des
accusés de réception authentifiés, le modèle de déduplication/correction, la responsabilité opérationnelle et
la validation juridique ne sont pas implémentés et vérifiés de bout en bout.
