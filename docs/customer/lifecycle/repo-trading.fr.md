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

!!! warning "Le contrat-cadre reste indispensable"
    Le flux ne remplace ni contrat-cadre, barème d'éligibilité, agent de valorisation, conservation, procédure de litige ni avis de compensation. Le DvP reste préférable au FoP.

