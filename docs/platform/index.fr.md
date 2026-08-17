---
title: Architecture de la plateforme
description: Architecture interne du backend Registerwerk — modules, sécurité, audit et API.
---

# Architecture de la plateforme { #platform-architecture }

Cette section couvre la conception interne de la plateforme Registerwerk pour les ingénieurs et les opérateurs.

- [Architecture des modules](modules.md) — 22 contextes limités Spring Modulith, graphe de dépendances
- [Sécurité et authentification](security.md) — JWT, OIDC, application des rôles, contrôles fail-fast
- [Journal d'audit](audit-log.md) — chaîne de hachage inviolable, gestion des partitions
- [Aperçu de l'API REST](api.md) — structure des URL, réponses d'erreur, pagination
- [Développement de dApps](dapp-development.md) — cadre de permissions de l'écosystème, workflow de publication sur la marketplace
- [Interopérabilité DeFi](defi-interoperability.md) — questions de juridiction, pont prête-nom/omnibus, et une facilité de référence de repo/prêt qui n'est pas approuvée pour un usage en production
- [Abstraction de compte et transactions sponsorisées](account-abstraction.md) — prise en charge ERC-4337/EIP-7702, gas sponsorisé, clés d'accès (passkeys)
