---
title: Ce que fait un opérateur
description: Le rôle de l'opérateur dans son intégralité : les décisions qui vous appartiennent, le portail et un démarrage local d'un quart d'heure.
---

# Ce que fait un opérateur

Vous exécutez le registre. Les clients comptent sur le fait qu'il soit correct, disponible et géré par quelqu'un qui comprend ce qu'ils approuvent.

Cette page est le travail. [Comment Registerwerk est construit](architecture.md) est le système ; [Au service des clients](customers/index.md) est le détail de chaque processus.

---

## Le rôle, honnêtement

La plupart du travail est **un jugement sur les personnes et les instruments**, pas sur l'infrastructure. Vous passerez beaucoup plus de temps à décider si une entité est bien celle qu'elle prétend être et si une émission doit être admise, plutôt qu'à redémarrer des conteneurs.

Les pouvoirs qui n'appartiennent qu'à vous partagent tous une propriété : **chacun peut causer un préjudice difficile ou impossible à inverser.**

| | Pourquoi c'est le vôtre |
|---|---|
| **Admettre une organisation** | Tout en aval suppose que cette vérification a eu lieu. |
| **Approuver une émission** | Crée quelque chose qui devient une obligation légale pour les investisseurs. |
| **Corriger le registre** | Les transferts forcés et les destructions (burning) au titre des §§24/26 eWpG déplacent les biens d'autrui. |
| **Agir en tant que client** | Le [mode support](customers/impersonation.md) vous place dans leur portail. |

---

## Votre journée

### Routine

- **La file d'attente d'approbation.** Entités en attente d'examen du KYC, émissions en attente d'approbation.
- **Le journal d'audit.** Lisez-le quand tout va bien, afin que vous sachiez à quoi ressemble la normale.
- **Santé.** Décalage de l'indexeur, santé du RPC de la chaîne, disponibilité du filtrage des sanctions, [marge de la partition d'audit](maintenance/monitoring.md).
- **Support.** Généralement l'une de trois choses — voir ci-dessous.

### Sur un calendrier régulier

- **Vérifiez l'appartenance à `REGISTRY_ADMIN`.** Chaque titulaire peut approuver les émissions, corriger le registre et prendre le mode support pour n'importe quel client.
- **Vérifiez les expirations de KYC à venir.** Avertir un client un mois à l'avance évite une panne qu'il vivra comme étant de votre faute.
- **Vérifiez la chaîne d'audit**, et conservez les preuves. Un contrôle d'intégrité que personne n'exerce est impossible à distinguer d'un contrôle qui ne fonctionne pas.
- **Testez les restaurations.** Une sauvegarde que personne n'a restaurée est une hypothèse.

### Le triage en trois questions

Avant d'enquêter sur quoi que ce soit d'exotique, un problème client est généralement :

1. **KYC expiré** — les transferts s'arrêtent, tout le reste semble normal.
2. **Portefeuille non enregistré ou non admis** — les transferts échouent on-chain plutôt que de rester en attente.
3. **Rôle manquant** — ils obtiennent un `403` et le décrivent comme « la page est cassée ».

Un `401` signifie que le jeton est mauvais. Un `403` signifie que le jeton est correct et que le rôle ne l'est pas. Cette seule distinction résout une grande partie des tickets.

---

## Le portail opérateur

À `:44200`. Il contourne entièrement la passerelle et utilise la connexion intégrée par nom d'utilisateur/mot de passe avec TOTP local pour l'authentification renforcée (step-up) — dans chaque configuration, y compris les déploiements où les clients utilisent Microsoft Entra ID.

| Zone | |
|---|---|
| **Clients** | Entités juridiques, leur statut, leur KYC. |
| **Intégration** | Créez des entités, générez des jetons d'invitation. |
| **Actifs** | Chaque émission pour chaque client. |
| **Utilisateurs** | Comptes et rôles, y compris le [support 2FA](customers/two-factor-support.md). |
| **Conformité** | Dossiers de filtrage des sanctions, examen du KYC. |
| **Audit** | Le journal inviolable. |
| **Organisations / Autorisations** | Identité on-chain et autorisations de l'écosystème. |
| **Revue dApp** | Soumissions au marketplace. |
| **Rails de paiement** | Curation du catalogue cash-leg. |
| **Portefeuilles / Nœuds de réseau** | Portefeuilles gardés, santé de la chaîne et du RPC. |

!!! warning "La navigation du portail ne constitue pas une frontière de sécurité"
    Les routes du portail opérateur ne sont pas filtrées par rôle dans le navigateur. L'accès est appliqué par le **backend**, à chaque requête, à partir de votre jeton.

    Ainsi, un utilisateur ayant seulement le rôle `AUDIT` voit des entrées de menu pour des choses qu'il ne peut pas faire, et obtient un refus en les ouvrant. Rien n'est exposé — mais ne déduisez pas d'un élément de menu visible que quelqu'un puisse s'en servir.

---

## Quinze minutes pour un registre local

```bash
git clone <your-registerwerk-remote> && cd registerwerk
git submodule update --init --recursive
cp .env.example.test .env
# CHAINCACHE_IMAGE dans .env doit désigner une image fournie indépendamment.
docker compose up -d --build
```

Avec `CHAINCACHE_ENABLED=true`, la même commande démarre les deux workloads Chaincache ainsi que
leur PostgreSQL privé. Registerwerk exige seulement l'image indiquée par
`CHAINCACHE_IMAGE` et ne construit pas `../chaincache`. Avec `false`, la pile principale démarre
indépendamment.

!!! danger "Laissez `JWT_ISSUER_URI` vide pour un démarrage local"
    Le configurer fait basculer le portail client en mode OIDC, qui nécessite un véritable tenant Entra, des enregistrements d'applications et l'accès conditionnel. Un URI d'émetteur à moitié configuré produit des échecs de connexion qui ressemblent à des bugs.

    Le mode local est le mode par défaut et le bon point de départ. Activez Entra délibérément, en suivant [Configuration de l'ID Microsoft Entra](../platform/entra-setup.md).

Ensuite :

| | |
|---|---|
| Portail opérateur | `http://localhost:44200` |
| Portail client | `http://localhost:44201` |
| Santé du backend | `curl http://localhost:48080/actuator/health` |
| Via la passerelle | `curl http://localhost:48000/api/v1/public/chains` |
| Documentation | `docker compose --profile docs up` → `http://localhost:48003` |

Connectez-vous avec `DEFAULT_ADMIN_EMAIL` / `DEFAULT_ADMIN_PASSWORD` depuis votre `.env`.

Kong fonctionne sans base de données (DB-less) à partir de `gateway/kong.yml` ; il n'y a donc pas d'identifiants de base de données pour la passerelle, et il n'y a pas de base de données `kong` ou `konga`. Son API d'administration est liée en local (loopback) — atteignez-la avec `docker compose exec kong kong health`, ne l'exposez jamais.

Pour tout ce qui va au-delà d'un essai local, consultez [Prérequis](installation/prerequisites.md) et lisez correctement [Environnement](configuration/environment.md).

---

## Avant de servir de vrais clients

- [ ] `DEFAULT_ADMIN_PASSWORD` et `JWT_DEV_SECRET` modifiés par rapport à leurs valeurs par défaut.
- [ ] `JWT_AUDIENCE` défini, si Entra est activé. **Non facultatif** — sans cela, un jeton émis à toute autre application de votre tenant est accepté ici comme une session valide.
- [ ] Sauvegardes configurées **et restaurées au moins une fois** — y compris le magasin d'objets, qui n'est pas dans la base de données.
- [ ] [Surveillance](maintenance/monitoring.md) en place, avec alerte sur la marge de partition d'audit.
- [ ] Plus d'un `REGISTRY_ADMIN`, détenus par **des personnes différentes**, afin que les contrôles à [quatre yeux](../compliance/step-up-mfa.md) soient réels.
- [ ] Une procédure de [reprise après sinistre](dr/runbook.md) testée.
- [ ] Vos critères de KYC et d'approbation des émissions consignés par écrit, afin que les décisions soient cohérentes et explicables.

---

## Où suivant

- [Comment Registerwerk est construit](architecture.md)
- [Au service des clients](customers/index.md)
- [Dépannage](troubleshooting.md)
