---
title: Fournisseur d'identité
---

# Fournisseur d'identité (OIDC)

## Connexion administrateur intégrée (mode développement / sans IdP)

Lorsque `ENTRA_ENABLED=false` (valeur par défaut), l'interface de l'opérateur affiche un formulaire de nom d'utilisateur/mot de passe
au lieu du bouton « Se connecter avec Microsoft ». Le backend expose `POST /api/v1/public/auth/login`
et émet un HS256 JWT de courte durée signé avec `JWT_DEV_SECRET`.

Configurez via des variables d'environnement :

```dotenv
ENTRA_ENABLED=false
DEFAULT_ADMIN_EMAIL=admin@local
DEFAULT_ADMIN_PASSWORD=changeme-please
JWT_DEV_SECRET=change-me-for-staging
```

Au démarrage, le backend amorce (ou actualise) une ligne dans la table `app_user` avec l'e-mail
configuré et un hachage BCrypt du mot de passe. La rotation du mot de passe est aussi simple que de changer
`DEFAULT_ADMIN_PASSWORD` et de redémarrer le service : le hachage est mis à jour à chaque démarrage.

!!! warning "Pas pour la production"
    Le secret de développement HS256 et l'administrateur intégré sont destinés uniquement au développement local et aux environnements de démonstration. Pour la production, configurez un véritable fournisseur d'identité ci-dessous et définissez
    `ENTRA_ENABLED=true` + `JWT_ISSUER_URI=<your-issuer>`. Le point de terminaison `/api/v1/public/auth/login`
    renvoie 404 lorsque `ENTRA_ENABLED=true`.



Le backend est un serveur de ressources OAuth2. Il accepte les JWT de tout fournisseur conforme à OIDC.

## Microsoft Entra ID (recommandé)

1. Enregistrez une application dans le portail Azure → Inscriptions d'applications
2. Ajoutez les autorisations API : `openid`, `profile`, `email`
3. Définissez les rôles d'application : `REGISTRY_ADMIN`, `AUDIT`, `ISSUER`, `INVESTOR`, `COMPANY_ADMIN`
4. Définissez les variables d'environnement :
   ```dotenv
   JWT_ISSUER_URI=https://login.microsoftonline.com/<tenant-id>/v2.0
   ENTRA_ISSUER=https://login.microsoftonline.com/<tenant-id>/v2.0
   ENTRA_CLIENT_ID=<app-id>
   ENTRA_CLIENT_SECRET=<client-secret>
   ```

En option, si vous exécutez Kong Enterprise/Konnect, vous pouvez également mettre fin à OIDC sur la passerelle
en utilisant `gateway/plugins/oidc-entra.yml` — le backend valide lui-même le JWT dans les deux cas, il s'agit donc d'une défense en profondeur, pas d'une exigence.

## Keycloak auto-géré

1. Créez un domaine et un client
2. Ajoutez des rôles de domaine correspondant aux noms de rôle ci-dessus
3. Configurez le mappeur de jetons pour inclure les rôles dans la revendication `roles` du JWT
4. Définissez les variables d'environnement :
   ```dotenv
   JWT_ISSUER_URI=https://keycloak.yourhost.com/realms/ewpg
   ENTRA_ISSUER=https://keycloak.yourhost.com/realms/ewpg
   ENTRA_CLIENT_ID=<client-id>
   ENTRA_CLIENT_SECRET=<client-secret>
   ```

En option, terminez OIDC chez Kong également en utilisant `gateway/plugins/oidc-self-managed.yml` (Enterprise/Konnect uniquement).

## Revendications JWT attendues

Le `JwtEntityClaimsConverter` du backend lit les revendications directement à partir du JWT validé — il ne
repose sur aucun en-tête injecté par la passerelle :
- `sub` — sujet utilisateur
- `roles` — liste de chaînes de rôles (par exemple `["ISSUER", "COMPANY_ADMIN"]`), transformées en autorités `ROLE_*`
- `entity_id` — l'UUID de l'entité juridique, pour la portée multi-locataires

Configurez le mappage des jetons/revendications de votre IdP afin qu'ils soient présents dans le JWT émis. Il n'y a pas d'étape de mappage d'entité côté
Kong dans la configuration OSS Kong de ce dépôt.
