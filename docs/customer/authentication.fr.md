---
title: Se connecter
description: Comment vous vous connectez, ce que fait l'authentification à deux facteurs, et que faire quand vous n'arrivez pas à entrer.
---

# Se connecter

La façon dont vous vous connectez dépend de la configuration retenue par l'opérateur de votre registre. Il existe deux modes, et ils se comportent assez différemment pour qu'il vaille la peine de savoir dans lequel vous êtes.

**Le moyen le plus rapide de le savoir :** si la page de connexion affiche un champ courriel et un champ mot de passe, vous êtes en mode local. Si elle affiche un bouton **Sign in with Microsoft**, vous êtes en mode Entra.

---

## Les deux modes

=== "Mode local — par défaut"

    Vous vous connectez avec une adresse électronique et un mot de passe détenus par le registre lui-même.

    **Pas de second facteur à la connexion.** C'est la configuration par défaut, celle qu'un simple `docker compose up` vous donne. Elle est destinée aux installations locales, aux démonstrations et aux évaluations.

    Votre mot de passe peut être réinitialisé par le parcours habituel de réinitialisation.

=== "Mode Entra — production"

    Vous vous connectez avec **Microsoft Entra ID**, avec le compte Microsoft de votre organisation, et **l'authentification à deux facteurs est obligatoire**.

    Le registre ne voit jamais votre mot de passe. Microsoft vous authentifie et délivre un jeton ; le registre le valide.

!!! info "Le personnel de l'opérateur utilise toujours la connexion intégrée"
    Même en mode Entra, le personnel de l'opérateur du registre se connecte avec un identifiant et un mot de passe et utilise une application d'authentification locale pour les actions sensibles.

    Seul le portail **client** bascule vers Entra. Si vous avez lu qu'Entra était la valeur par défaut pour tout le monde, y compris le personnel de l'opérateur, c'était faux — la plateforme ne s'est jamais comportée ainsi.

---

## Authentification à deux facteurs

S'applique en mode Entra.

L'authentification à deux facteurs est **obligatoire** pour le portail client en production. Elle est appliquée par l'accès conditionnel de Microsoft au moment de la connexion, **et non par le portail** — si vous n'avez pas enregistré de second facteur, Microsoft vous y invite avant de vous laisser continuer. Vous n'atteignez jamais Registerwerk sans être enrôlé.

La page **Security** (menu utilisateur → Security) affiche votre statut et vous guide dans la configuration.

!!! note "Pourquoi le registre ne peut pas vous fournir de QR code de configuration"
    Microsoft est propriétaire de l'identifiant. Son API n'offre aucun moyen de créer une méthode d'authentification ou TOTP — le secret n'est divulgué à personne, pas même au registre.

    Le code que vous scannez est donc affiché sur **la page d'informations de sécurité de Microsoft**. Le QR code de notre page Security est simplement un **lien vers cette page**, afin que vous puissiez passer de votre ordinateur au téléphone qui hébergera l'authentificateur.

    C'est une contrainte d'Entra, pas une fonctionnalité manquante. Aucun logiciel ne peut faire autrement.

**Pour configurer Microsoft Authenticator :**

1. Installez **Microsoft Authenticator** sur votre téléphone.
2. Ouvrez **Security** dans le portail et scannez le QR code, ou choisissez **Set up now**.
3. Ajoutez une méthode de connexion sur la page de Microsoft et suivez ses instructions.
4. Revenez au portail et choisissez **I've finished** — la page revérifie et confirme.

### Téléphone perdu ou remplacé

Contactez l'opérateur du registre. Après avoir vérifié votre identité par un autre canal, il supprimera vos anciennes méthodes, **déconnectera vos sessions existantes** et délivrera un **Temporary Access Pass** — un code éphémère, généralement à usage unique, vous permettant de vous connecter une fois pour enregistrer une nouvelle méthode.

Utilisez-le rapidement ; il expire typiquement dans l'heure.

!!! warning "Si votre organisation exploite son propre locataire Entra, l'opérateur ne peut rien"
    Vos utilisateurs sont dans *votre* annuaire, pas le sien. Il ne peut pas réinitialiser vos méthodes d'authentification et la console d'assistance refusera d'essayer.

    Contactez votre propre service d'assistance informatique.

---

## Si votre organisation utilise son propre fournisseur d'identité

Les organisations ayant configuré un fournisseur d'identité lors de l'[intégration](onboarding.md) se connectent via **leur propre locataire Microsoft Entra**.

L'accès s'établit **de locataire à locataire** dans Entra, au moyen de la collaboration B2B et des paramètres d'accès inter-locataires. Le registre n'exécute jamais de flux d'autorisation par code contre votre locataire et **ne demande donc jamais de secret client** — seulement votre URL d'émetteur et votre identifiant client, à des fins d'identification.

Avec ce modèle :

- Vos administrateurs décident quelles méthodes d'authentification sont disponibles et à quel niveau de robustesse.
- L'authentification multifacteur effectuée dans votre locataire est acceptée ici **uniquement si l'opérateur du registre a configuré la confiance MFA entrante**. C'est sa décision, pas la vôtre — un client se portant garant de sa propre MFA serait un moyen d'abaisser le niveau appliqué à ses propres utilisateurs.
- **L'opérateur du registre ne peut pas réinitialiser les seconds facteurs de vos utilisateurs.** C'est votre service d'assistance qui le fait.

---

## D'où viennent vos permissions

!!! danger "Votre fournisseur d'identité ne décide pas de ce que vous pouvez faire"
    Cela surprend les administrateurs, et se tromper là-dessus a des conséquences réelles.

    Entra répond à *qui est cette personne*. **Registerwerk répond à ce qu'elle peut faire**, à partir de sa propre fiche utilisateur. Les rôles d'application Entra ne sont consultés qu'une seule fois, à la création initiale de votre compte, pour choisir une valeur par défaut sensée.

    Donc : **retirer à quelqu'un son rôle d'application dans Entra ne lui retire pas ses permissions Registerwerk.** L'administrateur qui le fait en croyant l'accès révoqué se trompera.

    Pour modifier ce que quelqu'un peut faire, modifiez-le dans Registerwerk — votre [administrateur d'entreprise](workspaces/company-admin.md) s'en charge. Pour l'empêcher de se connecter du tout, désactivez le compte dans Entra.

Une documentation plus ancienne décrivait des rôles issus d'une revendication `roles` ou `groups` dans votre jeton. Ce n'est pas ainsi que cela fonctionne, et configurer une telle revendication n'aura ici aucun effet.

---

## Sessions

Les sessions durent **8 heures** par défaut, après quoi vous vous reconnectez.

En mode Entra, les politiques d'accès conditionnel de votre organisation peuvent exiger une réauthentification plus tôt, et des actions sensibles peuvent réclamer une preuve d'identité fraîche quel que soit le temps restant à votre session. C'est l'[authentification renforcée](../compliance/step-up-mfa.md), et elle fonctionne comme prévu plutôt qu'il ne s'agisse d'un problème de session.

---

## Appeler l'API directement

Pour les intégrations, obtenez un jeton et envoyez-le sous la forme `Authorization: Bearer <token>`.

En **mode Entra**, obtenez le jeton auprès d'Entra avec votre propre enregistrement d'application et la portée que votre opérateur vous indique. En **mode local**, `POST /api/v1/public/auth/login` en renvoie un.

!!! warning "Ne placez jamais un jeton dans du code frontal ou dans un dépôt"
    Utilisez des variables d'environnement ou un gestionnaire de secrets. Un jeton divulgué est une session à votre nom, pour toute sa durée restante.

[:octicons-arrow-right-24: Aperçu de l'API](../platform/api.md)

---

## Quand vous n'arrivez pas à entrer

| Ce que vous voyez | Signifie généralement | Faites |
|---|---|---|
| **Account not recognised** | Votre compte Microsoft n'est pas dans un locataire admis par l'opérateur | Contactez l'opérateur |
| **Access denied** après connexion | La connexion a réussi ; il vous manque un rôle | Demandez à votre administrateur d'entreprise |
| **Une invitation à enregistrer des informations de sécurité** | Deux facteurs pas encore configurés | Suivez-la — c'est obligatoire |
| **Token expired** | Session terminée | Reconnectez-vous |
| **Boucle de redirection** | Mauvaise configuration côté opérateur | Contactez l'opérateur — vous ne pouvez pas y remédier |
| **Tout semble normal mais rien ne fonctionne** | Le [KYC](kyc.md) de votre organisation a peut-être expiré | Vérifiez la page KYC |

!!! tip "La différence entre 401 et 403 mérite d'être connue"
    Si vous signalez un problème, préciser lequel vous avez obtenu fera gagner du temps à tout le monde.

    **401** — votre jeton n'est pas accepté. Un problème de connexion.
    **403** — votre jeton est bon, vos permissions non. Un problème de rôle, que votre administrateur d'entreprise peut probablement régler sans l'opérateur.

---

## Et ensuite

- [Obtenir votre compte](onboarding.md)
- [Administrateur d'entreprise](workspaces/company-admin.md) — gérer les utilisateurs et les réglages IdP
- [Authentification renforcée (step-up)](../compliance/step-up-mfa.md) — pourquoi certaines actions redemandent
