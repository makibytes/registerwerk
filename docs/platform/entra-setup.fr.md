---
title: Configuration de l'ID Microsoft Entra
description: Enregistrements d'applications, accès conditionnel, autorisations Graph et test de bon fonctionnement du locataire pour la 2FA en production.
---

# Configuration de l'ID Microsoft Entra { #microsoft-entra-id-setup }

Ceci est le runbook permettant de placer le portail client derrière l'ID Microsoft Entra avec une
authentification à deux facteurs imposée. Rien ici ne s'applique aux déploiements locaux ou de
démonstration — avec `ENTRA_ENABLED=false` (la valeur par défaut de `docker-compose.yml`), le
portail utilise la connexion intégrée par nom d'utilisateur/mot de passe, sans second facteur.

**Nécessite Microsoft Entra ID P1** pour l'accès conditionnel et les contextes d'authentification.

---

## Ce que Registerwerk peut et ne peut pas faire { #what-registerwerk-can-and-cannot-do }

Deux contraintes façonnent l'ensemble de la conception, et méritent d'être comprises avant de
commencer :

**Nous ne pouvons pas vous délivrer de code QR pour Microsoft Authenticator.** Microsoft Graph
n'expose aucun moyen de créer une méthode authenticator ou TOTP — `softwareOathMethods` et
`microsoftAuthenticatorMethods` ne prennent en charge que la liste, la lecture et la suppression, et
`secretKey` est documenté comme renvoyant toujours `null`. Entra détient le secret.
L'enregistrement se fait donc sur la
[page combinée d'informations de sécurité](https://learn.microsoft.com/en-us/entra/identity/authentication/concept-registration-mfa-sspr-combined)
de Microsoft, et la page `/security` de Registerwerk y guide les utilisateurs. Le code QR que nous
affichons encode le *lien* vers cette page, de sorte qu'un utilisateur sur un poste de bureau
puisse continuer sur le téléphone qui détiendra l'identifiant.

**Entra External ID (CIAM) ne peut pas être utilisé** si vous voulez Microsoft Authenticator : les
locataires externes ne prennent en charge que l'OTP par e-mail, le SMS (option payante) et les
clés d'accès (passkeys). Les clients doivent être membres ou invités B2B d'un locataire
professionnel (workforce tenant), ou fédérés depuis le leur.

---

## 1. Enregistrements d'application { #1-app-registrations }

Deux enregistrements. Gardez-les séparés : l'API détient un secret client et ne doit jamais être
un client public.

### API — le backend { #api-the-backend }

| Paramètre | Valeur |
|---|---|
| Nom | `Registerwerk API` |
| URI d'ID d'application | `api://<api-client-id>` |
| Portée exposée | `access_as_user` (consentement administrateur + utilisateur) |
| Secret client | Générez-en un → `ENTRA_CLIENT_SECRET` |

**Revendications facultatives sur le jeton d'accès** — ajoutez les trois sous *Configuration du
jeton* :

| Revendication | Pourquoi c'est important en cas d'absence |
|---|---|
| `acrs` | Entra n'ajoute jamais le contexte d'authentification de façon opportuniste, donc chaque action step-up coûte une redirection complète du navigateur. Cela ressemble exactement à un bug applicatif. |
| `xms_cc` | L'API ne peut pas savoir que le client comprend les défis de revendications (claims challenges). |
| `auth_time` | La fraîcheur du step-up retombe silencieusement sur `iat`, une garantie nettement plus faible. Le backend journalise un avertissement la première fois qu'il voit un jeton qui en est dépourvu. |

### SPA — l'interface client { #spa-the-customer-frontend }

| Paramètre | Valeur |
|---|---|
| Nom | `Registerwerk Customer Portal` |
| Plateforme | Application monopage (SPA) |
| URI de redirection | `https://<customer-portal-host>/` |
| Autorisation API | `api://<api-client-id>/access_as_user` |

Pas de secret client — c'est un client public. Le SPA annonce `clientCapabilities: ['CP1']` dans
le code ; rien à configurer ici.

---

## 2. Accès conditionnel { #2-conditional-access }

### Exiger le MFA à la connexion { #require-mfa-to-sign-in }

Créez une stratégie ciblant l'application API, n'accordant l'accès qu'avec **Require multifactor
authentication** — ou, mieux, avec un **niveau de force d'authentification**. Les niveaux
intégrés sont *Multifactor authentication*, *Passwordless MFA* et *Phishing-resistant MFA* ; les
deux contrôles d'accès ne peuvent pas être combinés dans une seule stratégie.

> Le niveau de force d'authentification ne s'applique qu'aux utilisateurs externes qui
> s'authentifient **via Entra ID**. Pour les invités par code à usage unique par e-mail, SAML/
> WS-Fed, ou fédérés via Google, utilisez à la place le contrôle d'accès MFA simple.

### Contexte d'authentification pour le step-up { #authentication-context-for-step-up }

1. **Entra ID → Accès conditionnel → Contexte d'authentification** → créez un contexte (c1–c99),
   par ex. `Registerwerk regulator-grade action`.
2. **Cochez « Publier dans les applications ».** Un contexte non publié est invisible pour les
   ressources et ne peut jamais être satisfait — le symptôme est une boucle de redirection de
   connexion sans rien dans les journaux. Registerwerk le vérifie au démarrage et refuse de
   démarrer en mode production s'il n'est pas publié.
3. Créez une stratégie ayant ce contexte comme ressource cible, n'accordant l'accès qu'avec le
   niveau de force d'authentification choisi, et réglez **Fréquence de connexion : à chaque
   fois**.
4. Renseignez son identifiant dans `ENTRA_STEPUP_AUTH_CONTEXT_ID`.

La fréquence de connexion est le véritable contrôle de fraîcheur pour le step-up : un jeton
d'accès vit 60 à 90 minutes et la revendication `acrs` persiste pendant toute sa durée de vie ;
sans ce réglage, un jeton reste « en step-up » longtemps après que l'utilisateur a quitté sa
session.

### Enregistrer les informations de sécurité { #register-security-information }

Forcez l'inscription dès la première connexion avec l'**action utilisateur « Register security
information »** (c'est une action utilisateur, pas une application cloud), ou avec la stratégie
d'inscription MFA d'ID Protection.

---

## 3. Microsoft Graph — la console de support de l'opérateur { #3-microsoft-graph-the-operator-support-console }

Uniquement nécessaire pour la page de statut 2FA côté client et la console « téléphone perdu » de
l'opérateur. Réglez `ENTRA_SUPPORT_ENABLED=true` et accordez à l'enregistrement API :

| Autorisation | Type |
|---|---|
| `UserAuthenticationMethod.ReadWrite.All` | Application |
| `User.RevokeSessions.All` | Application |

Accordez le consentement administrateur, puis attribuez au principal de service le rôle
d'annuaire **Authentication Administrator**. Délibérément *pas* Privileged Authentication
Administrator : Authentication Administrator peut agir sur les membres mais pas sur les
administrateurs, ce qui est exactement le confinement voulu pour un identifiant qui réside dans
la configuration d'une application.

Activez également **Temporary Access Pass** sous *Authentication methods → Policies* et
restreignez-le au groupe clients — un TAP peut être créé pour n'importe quel utilisateur, mais
seuls les utilisateurs dans le périmètre de la stratégie peuvent s'en servir pour se connecter.

---

## 4. Clients fédérés { #4-federated-customers }

Pour un client qui conserve son propre locataire Entra :

1. Réglez le `identity_model` de son entité juridique sur `FEDERATED` et enregistrez l'URL de son
   émetteur (l'identifiant du locataire en est dérivé).
2. Configurez les **paramètres d'accès inter-locataires** dans Entra pour la collaboration B2B
   entrante.
3. Décidez s'il faut faire confiance au MFA de leur locataire, et enregistrez ce choix dans
   `idp_mfa_trusted`. Ce réglage est contrôlé par l'opérateur : un client qui se porterait garant
   de son propre MFA pourrait sinon abaisser le niveau d'exigence appliqué à ses propres
   utilisateurs.

Registerwerk ne peut pas gérer les méthodes d'authentification d'un utilisateur fédéré — la
console de support affiche l'identifiant de son locataire et refuse toute action de mutation avec
un 409, plutôt que de faire un appel Graph qui échouerait de manière confuse.

Notez qu'un **Temporary Access Pass ne peut absolument pas être délivré à un invité externe**. La
console le détecte (`userType` invité, plus `#EXT#` dans l'UPN) et désactive le bouton avec une
explication.

---

## 5. Environnement { #5-environment }

```bash
ENTRA_ENABLED=true
JWT_ISSUER_URI=https://login.microsoftonline.com/<tenant-id>/v2.0
JWT_AUDIENCE=api://<api-client-id>          # or the bare client id — must match the token's aud

ENTRA_TENANT_ID=<tenant-id>
ENTRA_CLIENT_ID=<api-client-id>
ENTRA_CLIENT_SECRET=<api-client-secret>
ENTRA_SPA_CLIENT_ID=<spa-client-id>
ENTRA_API_SCOPE=api://<api-client-id>/access_as_user

ENTRA_SUPPORT_ENABLED=true
ENTRA_STEPUP_AUTH_CONTEXT_ID=c1
```

`JWT_AUDIENCE` n'est pas facultatif en production. Entra signe tous les jetons d'un même locataire
avec les mêmes clés ; sans contrôle d'audience, un jeton émis pour *n'importe quelle autre
application de votre locataire* serait accepté ici comme une session Registerwerk.
`ProductionReadinessCheck` refuse de démarrer sans ce réglage.

Le portail opérateur n'est affecté par rien de tout cela : il conserve la connexion HS256 intégrée
et le step-up TOTP local, ce qui explique pourquoi `JWT_DEV_SECRET` reste important même dans un
déploiement entièrement basé sur Entra.

---

## 6. Test de bon fonctionnement du locataire { #6-tenant-smoke-test }

Plusieurs comportements ne peuvent pas être vérifiés sans un vrai locataire. Parcourez cette liste
avant de déclarer le déploiement satisfaisant.

- [ ] **`/actuator/health/entra` renvoie UP**, avec un nombre de contextes d'authentification
      publiés non nul. Ceci couvre en un seul appel l'accessibilité de Graph, l'acquisition de
      jeton et la disponibilité du contexte.
- [ ] **Connectez-vous en tant que client de test.** L'accès conditionnel doit forcer
      l'inscription au MFA si aucune méthode n'existe.
- [ ] **Décodez le jeton d'accès.** Vérifiez que `aud` correspond à `JWT_AUDIENCE`, et que `acrs`,
      `xms_cc` et `auth_time` sont présents. Si `acrs` est absent, revérifiez les revendications
      facultatives — c'est de loin l'erreur de configuration la plus fréquente.
- [ ] **Appelez un point de terminaison step-up.** Attendez-vous à un 401 avec
      `error="insufficient_claims"`, puis une redirection, puis un succès. Si au contraire chaque
      appel redirige, `acrs` n'est pas émis de façon opportuniste.
- [ ] **Ouvrez `/security`.** La page doit afficher les méthodes enregistrées et une heure de
      « dernière vérification ».
- [ ] **Exécutez le flux « téléphone perdu » de bout en bout** sur un compte de test :
      réinitialiser les méthodes → révoquer les sessions → délivrer un TAP → se connecter avec le
      TAP → enregistrer une nouvelle méthode. Vérifiez que le TAP apparaît exactement une fois
      dans l'interface et n'apparaît nulle part dans `audit_event`.
- [ ] **Essayez le flux TAP sur un invité externe.** Le bouton doit être désactivé avec une
      explication, pas échouer au niveau de Graph.
- [ ] **Vérifiez que des lignes `audit_event` existent** pour chaque action de l'opérateur, avec
      le bon `actor_id` — c'est précisément ce que le filtre de normalisation du principal est
      censé garantir.

### Incertitudes connues { #known-uncertainties }

Elles dépendent de la configuration du locataire et d'un comportement Microsoft qui n'est pas
entièrement documenté :

- Si Entra refuse de supprimer la méthode d'authentification **par défaut** d'un utilisateur tant
  que d'autres méthodes subsistent. L'adaptateur supprime la méthode par défaut en dernier et
  rapporte les échecs méthode par méthode plutôt que de faire une supposition.
- Le comportement exact du TAP pour un compte interne mais de type invité ; l'heuristique `#EXT#`
  permet de distinguer les invités externes et devrait être confirmée empiriquement.
- Si la confiance MFA inter-locataires satisfait une exigence de contexte d'authentification pour
  les utilisateurs fédérés. Microsoft documente que FIDO2, Windows Hello et l'authentification par
  certificat ne satisfont le niveau de force que dans le locataire *d'origine* (home tenant) de
  l'utilisateur.
- La limitation de débit (throttling) de Graph sous interrogation soutenue de
  `/two-factor/refresh`. Le backend limite par utilisateur, mais les limites à l'échelle du
  locataire s'appliquent quand même.
