---
title: Step-Up MFA et 4 yeux
description: Authentification renforcée (step-up) et double contrôle (4 yeux) pour les opérations réglementées à haut risque.
---

# Step-Up MFA et 4 yeux {#step-up-mfa-4-eyes}

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Cette page décrit les mappages de contrôle prévus. Elle ne constitue pas une preuve que le MFA configuré ou
    le flux de double contrôle satisfait une exigence légale, réglementaire, de sécurité ou de séparation des
    tâches particulière. Les rôles, les actions protégées, le niveau d'assurance, la récupération et les preuves
    d'audit nécessitent un examen spécifique au déploiement.

Certaines opérations dans Registerwerk sont si conséquentes — ou la réglementation exige si clairement une
double surveillance — qu'une session de connexion normale n'est pas suffisante. **L'authentification
renforcée (step-up)** exige que l'opérateur prouve à nouveau son identité au moment de l'exécution de
l'opération. Le **principe des quatre yeux** (Vier-Augen-Prinzip) exige en outre qu'un deuxième approbateur
indépendant confirme l'action avant son exécution.

---

## Pourquoi cela existe {#why-this-exists}

| Réglementation | Obligation |
|---|---|
| GwG §6(2) | Systèmes de contrôle interne — les décisions à haut risque nécessitent une double surveillance documentée |
| eWpG §16 | Les opérations de blocage (Sperrvermerk) doivent être traçables jusqu'à un opérateur nommé et vérifié |
| BaFin KAIT | La sécurité informatique exige le MFA pour tout accès privilégié aux systèmes critiques |
| DSGVO Art. 32 | Mesures techniques appropriées pour protéger les données personnelles — le MFA est la base |

---

## Opérations protégées {#protected-operations}

Chaque annotation `@RequiresStepUp` du backend est listée, avec son point d'entrée, son motif, son âge maximal, l'exigence d'un deuxième approbateur et la liaison du corps, dans la [matrice step-up](step-up-matrix.md) générée (en anglais uniquement). Cette page est la liste complète, régénérée à partir du code (un contrôle de CI échoue si elle est périmée) ; le tableau ci-dessous est un extrait court et sélectionné, et **n'est pas exhaustif**.

| Opération | Step-up | 4 yeux | Motif (`@RequiresStepUp`) |
|---|---|---|---|
| Transfert forcé, destruction forcée, approbation forcée (points d'entrée opérateur et émetteur) | oui | oui | `FORCED_TRANSFER_EWG24`, `FORCE_BURN_EWG26`, `FORCED_APPROVE_OVERRIDE`, variantes `ISSUER_*` |
| Fixer le plafond d'offre (supply cap) | oui | oui | `SUPPLY_CAP_CHANGE_MICAR46` |
| ERC-3525 : création de slot, mint de slot, transfert de valeur forcé | oui | oui | `ERC3525_SLOT_CREATE`, `ERC3525_SLOT_MINT`, `ERC3525_FORCED_VALUE_TRANSFER_EWG24` |
| Approuver / rejeter le KYC (y compris approbation avec dérogation) | oui | oui | `KYC_APPROVE`, `KYC_REJECT` |
| Inscrire / lever un Sperrvermerk | oui | oui | `SPERRVERMERK_CREATE`, `SPERRVERMERK_LIFT` |
| Démarrer le mode support (lecture seule) | oui | non | `ADMIN_IMPERSONATION` |
| Démarrer le mode support, agir au nom du client (mode démo uniquement) | oui | oui | `ADMIN_IMPERSONATION_ACT_ON_BEHALF` |
| Accepter une correspondance de filtrage, confirmer un PEP (toujours, quel que soit le score) | oui, 15 min | oui | `SCREENING_HIT_ACCEPT`, `SCREENING_PEP_CONFIRM` |
| Export de clé de portefeuille, import de clé brute, import de keystore | oui | oui | `WALLET_KEYSTORE_EXPORT`, `WALLET_IMPORT_RAW`, `WALLET_IMPORT_KEYSTORE` |
| Rotation de la KEK, un portefeuille | oui | non | `WALLET_KEK_ROTATION` |
| Rotation de la KEK, tous les portefeuilles | oui | oui | `WALLET_KEK_ROTATION_ALL` |
| Réintégration d'une entité après clôture | oui | oui | `ENTITY_REINSTATE` |
| Acquittement d'une vérification de la chaîne d'audit | oui | oui | `AUDIT_CHAIN_VERIFICATION_ACK` |
| Réinitialisation TOTP d'un autre opérateur | oui | oui | `TOTP_RESET` |
| Entra : supprimer une méthode d'authentification | oui | non | `ENTRA_AUTH_METHOD_DELETE` |
| Entra : réinitialiser toutes les méthodes d'authentification | oui | oui | `ENTRA_MFA_RESET` |
| Entra : révoquer les sessions de connexion | oui | non | `ENTRA_REVOKE_SIGNIN_SESSIONS` |
| Entra : émettre un Temporary Access Pass | oui | oui | `ENTRA_TEMPORARY_ACCESS_PASS` |

L'exigence du deuxième approbateur peut aussi dépendre de la requête : les changements de comptes opérateur, les déclassements et clôtures d'incidents DORA et les déclassements de classification client l'imposent dans le service (`DualControlGate`) ; ces motifs figurent dans le second tableau de la matrice.

Le démarrage d'une session en mode support est décrit dans [Mode support](../operator/customers/impersonation.md) ; la session est refusée d'emblée lorsque `ENTRA_ENABLED=true`.

---

## Deux filières {#two-tracks}

La manière dont le deuxième facteur est prouvé dépend de qui émet les jetons de session. Les deux filières sont
appliquées par la même annotation `@RequiresStepUp` et le même aspect ; seule la vérification diffère.

### TOTP local — `ENTRA_ENABLED=false`, et toujours pour le portail opérateur {#local-totp-entraenabledfalse-and-the-operator-portal-always}

TOTP RFC 6238 (HMAC-SHA1, fenêtre de 30 secondes, 6 chiffres), vérifié par `StepUpTokenIssuer`. Inscription via
`POST /api/v1/auth/step-up/enroll`, confirmation via `/enroll/confirm`, puis échange d'un code via
`POST /api/v1/auth/step-up` contre un jeton de courte durée portant `acr=stepup`, valable 10 minutes.
L'appelant envoie ce jeton à la place de son jeton de session sur la requête protégée. Le rejet est **403**.

> **WebAuthn / FIDO2 n'est pas implémenté.** Le champ `method` de la requête de step-up est accepté et ignoré.
> D'anciennes versions de ce document le décrivaient comme le facteur principal ; il n'a jamais existé dans le
> code. Sous une connexion Entra, un MFA résistant au phishing est disponible — mais via l'accès conditionnel,
> pas via ce module.

### Contexte d'authentification Entra — `ENTRA_ENABLED=true` {#entra-authentication-context-entraenabledtrue}

Le jeton d'accès doit porter le contexte d'authentification d'accès conditionnel requis dans sa revendication
`acrs`. Registerwerk ne vérifie pas lui-même un facteur ; il énonce une exigence et laisse l'accès conditionnel
décider de ce qui la satisfait — ce qui permet à un opérateur d'exiger un MFA résistant au phishing pour les
transferts forcés sans changement de code.

Le rejet est un **défi de revendications 401**, de sorte que le SPA se réauthentifie pour cette action précise
au lieu de déconnecter l'utilisateur :

```
WWW-Authenticate: Bearer realm="", authorization_uri="…",
                  error="insufficient_claims", claims="<base64>"
```

L'identifiant de contexte est une donnée de configuration, indexée sur `@RequiresStepUp(reason = …)` :

```yaml
registerwerk.auth.step-up.entra:
  auth-context-id: c1                 # ENTRA_STEPUP_AUTH_CONTEXT_ID
  reason-overrides:
    FORCE_BURN_EWG26: c2
    "Payment rail creation": c1       # quote reasons containing spaces
```

Il est validé par rapport au tenant au démarrage : un contexte qui n'existe pas, ou qui existe mais n'est
**pas publié pour les applications**, fait échouer le démarrage en mode production. Un contexte non publié ne
peut jamais être satisfait et produit une boucle de redirection de connexion, sans rien dans les journaux pour
l'expliquer.

#### La fraîcheur fonctionne différemment ici {#freshness-works-differently-here}

Un jeton d'accès Entra vit 60 à 90 minutes et `acrs` persiste pendant toute sa durée de vie, si bien
qu'appliquer `maxAgeMinutes` à `iat` forcerait une redirection complète du navigateur sur presque chaque appel
protégé. À la place :

- le contrôle de fraîcheur **principal** est la politique d'accès conditionnel sur le contexte
  d'authentification (définir *Fréquence de connexion : à chaque fois* pour les actions de niveau
  réglementaire) ;
- `maxAgeMinutes` est vérifié par rapport à la revendication `auth_time`, en filet de sécurité.

`auth_time` est une revendication optionnelle qui doit être demandée sur l'enregistrement de l'application API.
En son absence, la vérification retombe sur `iat`, ce qui est plus faible — le backend journalise un
avertissement la première fois qu'il rencontre un jeton Entra qui en est dépourvu.

---

## Implémentation des 4 yeux {#4-eyes-implementation}

Le deuxième approbateur doit être un autre utilisateur actuellement actif avec le rôle `REGISTRY_ADMIN` **ou** `COMPLIANCE_OFFICER` (`StepUpTokenValidator.ELIGIBLE_APPROVER_ROLES` ; les rôles de l'approbateur sont relus dans la base de données). Il n'existe pas de rôle `SECOND_APPROVER` distinct. Les initiateurs des décisions KYC et de filtrage peuvent eux-mêmes être `REGISTRY_ADMIN` ou `COMPLIANCE_OFFICER`, de sorte qu'une paire de `COMPLIANCE_OFFICER` est possible pour ces actions ; nul ne peut approuver sa propre demande.

**Le principe des 4 yeux est identique dans les deux filières** : un jeton de double contrôle est toujours émis
localement après vérification TOTP, et toujours validé par rapport au décodeur HS256 local — il ne dépend donc
pas de la manière dont le facteur principal a été prouvé.

```mermaid
sequenceDiagram
    participant Initiator
    participant Approver
    participant Backend

    Approver->>Backend: POST /api/v1/auth/step-up { code, action, target[, targetBody] }
    Backend-->>Approver: approver token (acr=stepup, stepup_scope, stepup_target, jti; 5 min, single use)
    Approver->>Initiator: Hand over the approver token
    Initiator->>Backend: POST /api/v1/auth/step-up { code, action }
    Backend-->>Initiator: initiator step-up token
    Initiator->>Backend: Protected call — Authorization: initiator token,<br/>X-Dual-Control-Token: approver token
    Backend->>Backend: Validate both, then execute + audit with both identities
```

Au lieu de transmettre un jeton à la main, l'initiateur peut utiliser la file d'approbation de l'application (section suivante). Les deux voies aboutissent au même contrôle sur le point d'entrée protégé.

Invariants clés appliqués par `StepUpEnforcementAspect` et `StepUpTokenValidator` :

- L'initiateur et l'approbateur **doivent être des utilisateurs différents** (comparaison sur `sub`)
- Le jeton de l'approbateur doit porter un `stepup_scope` **exactement égal** au `reason` de l'annotation —
  sinon une approbation deviendrait un identifiant générique valable pour n'importe quelle action à 4 yeux
  dans sa fenêtre de validité
- L'approbateur doit toujours être un `REGISTRY_ADMIN` **ou** un `COMPLIANCE_OFFICER` **actif dans la base de données**, pas seulement selon les revendications du jeton, qui ne reflètent le statut qu'au moment de l'émission
- L'approbation est **liée à la requête pour laquelle elle a été donnée** (K3). L'approbateur l'émet avec `action` *et* `target` (`"METHOD /chemin?query"` de l'appel exact ; aussi `targetBody`, le corps JSON de la requête, que tout motif lie). Le jeton porte `stepup_target`, le SHA-256 en base64url de la requête canonique (`v1`, méthode en majuscules, chemin sans barre oblique finale, query triée et le hachage du corps JSON canonique : clés triées, sans espaces, nombres décimaux exacts en notation simple). Le backend calcule le même condensat à partir de la requête réelle ; s'il diffère, l'appel est refusé avec **403**. Les jetons sans cible ne sont plus acceptés
- **Les jetons d'approbation ne valent que dans l'en-tête.** Une approbation porte `use=dual_control` et l'audience `registerwerk-dual-control`. Elle est acceptée dans `X-Dual-Control-Token` et nulle part ailleurs : en `Authorization: Bearer` (ou cookie de session) sur n'importe quel endpoint, y compris `@RequiresStepUp`, elle est refusée avec **403** ; une approbation détenue par quelqu'un ne peut donc jamais être rejouée comme la session d'une autre personne. Les jetons de step-up ordinaires (sans portée ni marquage) restent la preuve propre de l'appelant.
- **Le corps est toujours lié.** Chaque motif lie le corps canonique de la requête (`targetBody`, omis si la requête n'en a pas). Les exceptions figurent dans `registerwerk.auth.step-up.dual-control.body-opt-out-reasons` : contenus qui ne sont pas du JSON (dépôt de term sheet, import de keystore, import CSV CASP) et contenus qui sont du matériel de clé secret ou le mot de passe d'un keystore (import de clé brute, export de keystore) ; méthode, chemin et query restent liés. Les nombres sont des décimaux exacts, jamais des `double` ; un corps avec une clé JSON répétée ou une requête avec un paramètre de query répété ne peut pas être lié et est refusé. Le jeton d'approbation reste à usage unique même si `bind-target-reasons` est restreint.
- **Le bootstrap est une porte à sens unique.** L'exception « un seul step-up suffit » ne vaut que jusqu'à ce que deux `REGISTRY_ADMIN` activés et enrôlés en TOTP aient existé en même temps. La base de données consigne ce moment (`dual_control_bootstrap`, posé par trigger, jamais effacé) ; ensuite l'exception ne revient plus, même si un administrateur est désactivé ou perd son authentificateur. Désactiver ou supprimer un compte du personnel opérateur (sans rattachement à une entreprise) ou un compte détenant un rôle protégé (`REGISTRY_ADMIN`, `COMPLIANCE_OFFICER`, `SUPPORT_AGENT`, `AUDIT`) exige le second approbateur (`OPERATOR_USER_DISABLE`, `OPERATOR_USER_DELETE`).
- L'approbation est à **usage unique** : son `jti` est écrit dans `dual_control_token_use` avec l'événement d'audit, dans une seule transaction (une seconde utilisation, sur n'importe quel réplica, donne **403**). Une action qui échoue après la consommation de l'approbation nécessite une nouvelle approbation
- L'approbation n'est acceptée que pendant une **fenêtre courte** après son émission (`registerwerk.auth.step-up.dual-control.window-seconds`, 300 s par défaut) ; le jeton d'authentification renforcée de l'initiateur conserve ses 10 minutes

---

## File d'approbation de l'application { #in-app-approval-queue }

Les deux portails déposent et décident les approbations via `/api/v1/approvals`, au lieu de faire circuler des jetons :

1. L'initiateur dépose la requête exacte (action = le motif `@RequiresStepUp` du point d'entrée, méthode, chemin, requête et corps JSON). La demande n'est acceptée que si cette action est le motif de cette route ; le corps est stocké sous forme canonique pour que l'approbateur voie ce qui sera exécuté.
2. Un approbateur éligible autre que l'initiateur (`REGISTRY_ADMIN` ou `COMPLIANCE_OFFICER`) la voit dans la boîte **Approvals** et approuve avec un code TOTP frais, ou rejette. L'auto-approbation est impossible, y compris au niveau de la base de données.
3. L'initiateur récupère un jeton d'approbation à usage unique, lié à cet empreinte (digest) et à l'initiateur (un jeton récupéré par un utilisateur est inutile entre d'autres mains), puis envoie la vraie requête avec son propre jeton step-up et `X-Dual-Control-Token`.

Les demandes que personne ne décide, ou qui ne sont pas récupérées, expirent après 15 minutes (`registerwerk.auth.step-up.approval-queue.ttl`). La file refuse les sessions en mode support. Événements d'audit : `APPROVAL_REQUEST_CREATED`, `_APPROVED`, `_REJECTED`, `_CANCELLED`, `_CLAIMED`, `_EXPIRED` (le corps ne figure jamais dans l'événement ; son empreinte, si).

---

## Enrôlement TOTP, stockage et réinitialisation { #totp-enrolment-storage-reset }

- **Pas de confiance au premier usage.** Démarrer un enrôlement (`POST /api/v1/auth/step-up/enroll`) exige le mot de passe actuel du compte dans le corps (`{ "currentPassword": "…" }`) : une session volée ou laissée ouverte ne peut donc pas lier l'authentificateur d'un attaquant. Les mots de passe erronés comptent dans le même verrouillage que les codes erronés. Les comptes dont le second facteur est géré par un fournisseur d'identité externe ne peuvent pas enrôler d'authentificateur local. La confirmation (`/enroll/confirm`) consomme le pas de temps du code, qui ne peut donc pas être rejoué comme code d'authentification renforcée.
- **Chiffré au repos.** Le secret TOTP est chiffré par enveloppe (AES-256-GCM, une clé de données neuve par valeur, enveloppée par la KEK de la plateforme, l'identifiant de l'utilisateur comme données authentifiées additionnelles) dans `app_user.totp_secret` ; `totp_secret_kid` consigne le fournisseur de KEK. Les secrets stockés en clair par les versions antérieures sont chiffrés par une tâche de démarrage ; la vérification refuse un secret encore en clair (un administrateur réinitialise l'enrôlement).
- **État partagé entre réplicas.** La protection contre le rejeu (RFC 6238 §5.2 : un code situé au pas de temps accepté en dernier ou avant est refusé) et le verrouillage anti-force brute (5 codes erronés ou rejoués verrouillent l'authentification renforcée pendant 15 minutes) résident dans la table `totp_state` et sont mis à jour de façon atomique : un code accepté sur un réplica est refusé sur tous les autres. Chaque tentative est réservée avant la comparaison du code, de sorte que les essais parallèles partagent un budget de cinq.
- **Retrait en libre-service.** `POST /api/v1/auth/step-up/disenroll { "code": "…" }` exige un code actuel valide, supprime l'enrôlement et met fin aux sessions de l'utilisateur. L'utilisateur doit s'enrôler à nouveau avant toute action d'authentification renforcée.
- **Réinitialisation par l'opérateur (appareil perdu).** `POST /api/v1/admin/users/{id}/totp-reset` exige l'authentification renforcée **et** un second approbateur (motif `TOTP_RESET`). Elle supprime l'enrôlement, met fin aux sessions de l'utilisateur et écrit l'événement d'audit `TOTP_RESET` avec les deux identités ; on ne peut pas réinitialiser ainsi son propre enrôlement. L'utilisateur s'enrôle à nouveau à la prochaine connexion.
- **Supervision.** La jauge `registerwerk_stepup_unenrolled_operators` compte les comptes locaux actifs `REGISTRY_ADMIN` / `COMPLIANCE_OFFICER` de plus de sept jours sans authentificateur. C'est une métrique d'alerte, pas un échec de démarrage ; déclenchez une alerte au-dessus de zéro.

Événements d'audit du cycle de vie : `TOTP_ENROLMENT_STARTED`, `TOTP_ENROLLED`, `TOTP_DISENROLLED`, `TOTP_RESET` ; `DUAL_CONTROL_APPROVED` consigne désormais aussi l'identifiant du jeton d'approbation et le condensat de la cible, et `DUAL_CONTROL_BOOTSTRAP_USED` signale l'exception à acteur unique tant qu'il existe moins de deux administrateurs enrôlés en TOTP.

---

## Application par AOP {#aop-enforcement}

Le `StepUpEnforcementAspect` intercepte toute méthode annotée `@RequiresStepUp` et :

1. Lit le JWT authentifié depuis le contexte de sécurité
2. Se branche selon la filière active :
   - **locale** — exige `acr=stepup` et un `iat` dans la fenêtre `maxAgeMinutes` (10 par défaut) ; l'échec est
     **403**
   - **Entra** — exige que `acrs` contienne le contexte d'authentification configuré et que `auth_time` soit
     dans la fenêtre `maxAgeMinutes` ; l'échec est un **défi de revendications 401**
3. Si `requireSecondApprover = true`, valide l'en-tête `X-Dual-Control-Token` et expose l'identifiant de
   l'approbateur en tant qu'attribut de requête `stepup.dualControlApproverId`, que les contrôleurs lisent via
   `@RequestAttribute` — ils ne doivent pas redécoder le jeton eux-mêmes
4. Le défi de revendications est émis par `ClaimsChallengeAdvice`, et non par Spring Security : l'exception est
   levée depuis un `@Around` AOP et donc résolue par `@RestControllerAdvice`, et le
   `BearerTokenAuthenticationEntryPoint` de Spring Security n'a de toute façon aucun chemin de code permettant
   de sérialiser un paramètre `claims=`

---

## Événements d'audit {#audit-events}

L'activité step-up et quatre yeux est consignée par ces types d'événements (la liste correspond à ce qui existe dans le code ; il n'existe pas d'événement distinct « step-up émis ») :

| Type d'événement | Contenu |
|---|---|
| `TOTP_ENROLMENT_STARTED`, `TOTP_ENROLLED`, `TOTP_DISENROLLED`, `TOTP_RESET` | Utilisateur concerné ; pour une réinitialisation, aussi l'acteur et l'approbateur |
| `DUAL_CONTROL_APPROVED` | Initiateur, approbateur, motif, identifiant du jeton d'approbation et empreinte de la cible ; écrit avant que l'opération protégée ne se poursuive |
| `DUAL_CONTROL_BOOTSTRAP_USED` | L'exception à acteur unique a été utilisée alors que moins de deux administrateurs enrôlés en TOTP existaient |
| `APPROVAL_REQUEST_CREATED / _APPROVED / _REJECTED / _CANCELLED / _CLAIMED / _EXPIRED` | Transitions de la file d'approbation : acteur et rôle, approbateur à l'approbation et à la récupération, empreinte et identifiant du jeton (jamais le corps) |

L'action auditée elle-même (par exemple `FORCED_TRANSFER` ou `SPERRVERMERK_CREATE`) porte son propre événement. Ces événements font partie de la [chaîne d'audit](../platform/audit-log.md) inviolable.
