---
title: Octrois d'administration de jetons — Autorité d'action forcée délégable
description: ASSET_TOKEN_ADMIN — l'autorisation délégable contrôlant forcedTransfer/forcedApprove/forceBurn au-delà de REGISTRY_ADMIN.
---

# Octrois d'administration de jetons — Autorité d'action forcée délégable {#token-admin-grants-delegatable-forced-action-authority}

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Cette page enregistre les mappages de contrôle prévus. Elle ne constitue pas une preuve qu'une délégation
    est légalement valide, qu'elle relève de l'autorisation d'un opérateur, ou qu'elle est suffisante pour une
    correction, une annulation, un gel, une destruction (burn) ou un transfert forcé. La capacité, les preuves
    d'instruction, la séparation des tâches et les règles propres à l'instrument/à la juridiction nécessitent un
    examen externe.

Les opérations de jetons à caractère obligatoire du registre de Registerwerk — **forcedTransfer**,
**forcedApprove** et **forceBurn** — permettent au registre de déplacer, de réapprouver ou de détruire les
jetons d'un détenteur sans son consentement. Ce sont les outils les plus tranchants de la plateforme : un appel
abusif déplace une valeur réelle vers une adresse choisie par un attaquant, ou la détruit purement et
simplement. Jusqu'ici, ils n'étaient accessibles que par `REGISTRY_ADMIN` (plus, pour
`forcedTransfer`/`forcedApprove`, tout émetteur agissant sur son propre actif du seul fait d'en être
propriétaire).

**`ASSET_TOKEN_ADMIN`** remplace le raccourci fondé sur la propriété de l'émetteur par une autorisation
explicite, accordée par l'opérateur. Par défaut, **personne ne la détient — pas même l'émetteur propre d'un
actif.** Un opérateur doit la déléguer délibérément à une entité cliente nommée (émetteur ou investisseur), et
seulement après avoir vérifié que le portefeuille de cette entité est un participant authentique, inscrit sur
liste blanche (et, pour les actifs ERC-3643, vérifié par ONCHAINID).

Notez ce qui ne change **pas** : la transaction on-chain elle-même reste signée par le portefeuille opérateur
du registre, exactement comme avant. `ASSET_TOKEN_ADMIN` est purement une **porte d'autorisation au niveau
API** — elle décide qui peut *demander* au registre d'exécuter une action forcée, pas qui *l'exécute* on-chain.

---

## Ce que cela contrôle {#what-it-gates}

| Action | Chemin opérateur | Chemin client |
|---|---|---|
| `forcedTransfer` / `forcedTransferSingle` | `TokenAdminController` | `IssuerTokenController` |
| `forcedApprove` | `TokenAdminController` | `IssuerTokenController` |
| `forceBurn` / `forceBurnSingle` | `TokenAdminController` | — (opérateur uniquement) |
| Équivalents ERC-3643 (y compris par lot) | `Erc3643Controller` | `Erc3643Controller` |
| Canton `force-transfer-canton` / `burn-holding` | `TokenAdminController` | — (opérateur uniquement) |

Chaque point de terminaison ci-dessus exige désormais `hasRole('REGISTRY_ADMIN')` **ou** un octroi
`ASSET_TOKEN_ADMIN` actif pour l'entité de l'appelant sur cet actif précis (voir
`AssetAccessChecker.canForceAdmin`). Tout le reste — pause, gel, liste blanche, mint, burn (la variante non
forcée) — n'est pas affecté.

---

## §24 / §26 eWpG comme fondement de la délégation (Allemagne) {#ewpg-24-26-as-the-delegation-basis-germany}

Les actions forcées correspondent à des dispositions concrètes de l'eWpG : `forcedTransfer` relève de la
**§24 Berichtigung** (rectification du registre sur décision de la BaFin ou d'un tribunal), `forceBurn` relève
de la **§26 Einziehung** (annulation obligatoire). Les deux dispositions décrivent le pouvoir du *teneur de
registre* de corriger ou d'annuler une inscription — elles n'envisagent pas elles-mêmes de déléguer ce pouvoir
à un client. La position retenue par cette fonctionnalité est que le teneur de registre (l'opérateur) reste
légalement responsable de chaque action forcée, quelle que soit la personne qui a initié l'appel API ;
`ASSET_TOKEN_ADMIN` est une **délégation opérationnelle de l'initiation**, et non une délégation d'autorité
légale — c'est le double contrôle par step-up propre à l'opérateur (voir ci-dessous) qui autorise réellement
l'exécution, à chaque appel, que l'initiateur soit `REGISTRY_ADMIN` ou un client bénéficiaire d'un octroi.

**Autres juridictions :** le FR, le LU et le LI ne disposent pas encore, dans cette base de code, d'un concept
directement analogue de « délégation de l'initiation d'une rectification obligatoire du registre ». Traitez la
délégation à une entité cliente au titre des régimes locaux de valeurs mobilières/DLT de ces juridictions comme
**non examinée** — obtenez la confirmation d'un conseiller juridique local avant d'accorder
`ASSET_TOKEN_ADMIN` à une entité non allemande en production, conformément à la convention d'avertissement
utilisée ailleurs dans ce répertoire (par ex. [Sperrvermerk](sperrvermerk.md)) et à
l'[aperçu des juridictions](../legal/index.md).

---

## Modèle d'octroi {#grant-model}

Deux variantes, toutes deux créées/révoquées exclusivement par `REGISTRY_ADMIN` avec
`@RequiresStepUp(requireSecondApprover = true)` (le même circuit TOTP + 4 yeux que celui utilisé pour les
actions forcées elles-mêmes) :

- **À l'échelle d'un actif** (`POST /api/v1/assets/{assetId}/token-admin-grants`) — le cas courant : un actif,
  une entité bénéficiaire.
- **À l'échelle d'une entité** (`POST /api/v1/entities/{entityId}/token-admin-grants`) — s'applique à tous les
  actifs sur lesquels cette entité est émetteur/détenteur, présents et futurs. Une délégation de confiance
  nettement plus large ; à réserver à un émetteur récurrent de confiance, pas au cas par défaut.

### Éligibilité, validée une seule fois au moment de l'octroi {#eligibility-validated-once-at-grant-time}

| Bénéficiaire | Vérification du portefeuille |
|---|---|
| L'émetteur propre de l'actif (portée actif) | Portefeuille lié à l'identité d'organisation de l'entité (`orgidentity.PermissionGate.isWalletBoundToEntity`) |
| Un détenteur/investisseur de l'actif (portée actif) | `AssetHolder.whitelisted = true` pour ce portefeuille sur cet actif, **plus** `IdentityRegistry.isVerified` (T-REX) si l'actif est ERC-3643/CONF_ERC3643 |
| À l'échelle de l'entité | Portefeuille lié à l'identité d'organisation de l'entité (aucun actif unique sur lequel vérifier l'inscription sur liste blanche) |

Le contrôle qui a été validé est enregistré sur l'octroi (`eligibilityBasis`) à des fins d'audit — il n'est
**pas** revérifié en direct à chaque appel d'action forcée ultérieur ; seul le statut `ACTIVE`/non expiré de
l'octroi lui-même l'est. Si un portefeuille est ultérieurement retiré de la liste blanche ou bloqué, l'opérateur
doit révoquer l'octroi séparément.

### Cycle de vie {#lifecycle}

Reprend le cycle de vie du `HolderBlock` de [Sperrvermerk](sperrvermerk.md) : `ACTIVE → REVOKED` (manuel,
step-up + 4 yeux) ou `ACTIVE → EXPIRED` (job `@Scheduled` nocturne après dépassement d'`expiresAt`, si une
échéance avait été définie).

---

## Interface opérateur {#operator-ui}

- **À l'échelle d'un actif** — onglet « Token Admin Grants » sur la page de détail de l'actif : liste des
  octrois actifs, nouvel octroi (entité, portefeuille, configuration de chaîne facultative, base juridique,
  expiration facultative), révocation.
- **À l'échelle d'une entité** — `/compliance/token-admin-grants` : recherche d'une entité, gestion de ses
  octrois à l'échelle de l'entité. Volontairement une page distincte de l'écran Permissions de l'écosystème
  `orgidentity` (`/permissions`), sans rapport avec celle-ci — cet écran-là régit les permissions
  d'organisation du marketplace de dApps et n'a aucune dimension liée aux actifs.

---

## Piste d'audit {#audit-trail}

Chaque octroi et chaque révocation publient des événements d'audit `ASSET_TOKEN_ADMIN_GRANTED` /
`ASSET_TOKEN_ADMIN_REVOKED` (`asset.events.AssetTokenAdminGrantedEvent` / `...RevokedEvent`), capturés
automatiquement via la [chaîne de hachage d'audit](../platform/audit-log.md) — l'acteur, l'entité, l'actif (ou
« à l'échelle de l'entité »), le portefeuille, la base juridique et la base d'éligibilité sont tous enregistrés.
