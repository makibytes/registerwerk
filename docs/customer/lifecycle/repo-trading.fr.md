---
title: 5a. Opérations de pension
description: Négocier et gérer des pensions bilatérales par RFQ ciblée ou diffusée.
---

# Étape 5a — Opérations de pension

Une **pension livrée (repo)** associe deux opérations convenues ensemble : vente de titres contre espèces à la date de départ, puis rachat de titres équivalents à un montant fixé à l'échéance. L'écart constitue le rendement repo.

Le Repo Desk modélise ce processus bilatéral. Il est distinct du [prêt garanti par titres](repo-lending.md), où la garantie est déposée dans un pool on-chain.

| | Repo Desk | Prêt garanti |
|---|---|---|
| Contrepartie | Entreprises identifiées | Marché mutualisé |
| Structure | Vente et rachat convenu | Prêt avec garantie |
| Prix | Cotation et montant de rachat fixes | Taux variable selon l'utilisation |
| Risque | Décote, appel de marge, substitution | LTV, oracle, liquidation |

## Flux de travail

1. Dans **Trader → Repo Desk → New RFQ**, indiquez emprunt/prêt d'espèces, garantie, montant, dates, taux indicatif et décote.
2. Une RFQ **ciblée** n'est visible que par les sociétés sélectionnées ; une RFQ **broadcast** par tous les traders éligibles.
3. Un dealer ne voit jamais les offres concurrentes. Le demandeur compare montant, taux annuel, décote et validité, puis accepte une cotation.
4. Le montant de rachat est fixé selon ACT/360. `3,25` signifie 3,25 % par an.
5. À l'ouverture et à la clôture, chaque destinataire confirme la jambe espèces ou titres réellement reçue avec une référence.
6. Appels de marge et substitutions de garantie restent dans l'historique partagé et immuable.

## Contrôles du desk

- **Les cotations sont versionnées.** Une cotation remplacée devient `SUPERSEDED` et ne peut plus être acceptée. L'acceptation porte le `termsHash` fourni par le serveur ; en cas d'écart (409), examinez les conditions actuelles. Les conditions acceptées sont figées sur la transaction. Montants et intérêts sont arrondis à la sous-unité de la devise (ACT/360, ACT/365 pour GBP notamment).
- **Chaque jambe a un payeur** (déclare « envoyé » avec référence) **et un receveur** (confirme ou conteste). Un appel de marge exige une référence de valorisation et un montant, ne peut dépasser l'insuffisance qui en découle et laisse au moins 24 heures ; **seule la confirmation du prêteur le clôt**.
- **Le défaut se déroule en deux temps :** notification de défaut par le créancier, puis, après le délai de grâce (24 heures par défaut), déclaration – tant que l'obligation reste inexécutée et que la contrepartie n'a pas déclaré l'avoir exécutée. Si l'emprunteur a payé et que le prêteur ne restitue pas les titres, c'est l'*emprunteur* qui peut déclarer le défaut.
- **Litige :** chaque partie peut geler la transaction ; l'opérateur consigne l'issue avec base juridique et second approbateur, sans trancher sur le fond.
- **Substitution :** demande distincte ; la garantie ne change qu'après confirmation des deux jambes, jamais sur une transaction clôturée, en défaut ou litigieuse.
- **Accès :** adhésion de la société, client professionnel ou contrepartie éligible, contrôle KYC/filtrage. L'emprunteur doit détenir les titres au registre ; les titres déjà nantis ou mis en vente sont indisponibles (grèvement interne, sans Sperrvermerk au registre). Le terme doit précéder l'échéance ou le rappel de la garantie ; le remboursement est bloqué tant qu'un repo est ouvert. Les opérations sur titres sont consignées ; les paiements compensatoires relèvent des parties.
- **Mesures de protection du créancier :** l'appel de marge, la mise en demeure et la déclaration de défaut protègent une exposition existante et ne sont refusés qu'en cas d'**arrêt dur** : société non active ou résultat de filtrage des sanctions non résolu. Un KYC expiré ou un Sperrvermerk sur un wallet quelconque ne désarme pas le prêteur : l'action est exécutée, le trade est signalé (`PARTY_FLAGGED`) et l'opérateur du registre reçoit une tâche. Les nouvelles expositions, les substitutions et leur approbation conservent le contrôle complet.
- **SFTR :** les deux parties ont besoin d'un LEI ; chaque transaction reçoit un UTI et expose les champs SFTR détenus (`/sftr-fields`). Registerwerk ne déclare pas ; les parties restent responsables. Le règlement est bilatéral et auto-confirmé, non atomique.

!!! warning "Le contrat-cadre reste indispensable"
    Le flux ne remplace ni contrat-cadre, barème d'éligibilité, agent de valorisation, conservation, procédure de litige ni avis de compensation. Le DvP reste préférable au FoP.
