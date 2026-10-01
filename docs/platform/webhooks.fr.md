---
title: Webhooks
description: Envoi de webhooks sortants pour les clients API - exigences du point de terminaison, schéma de signature, fenêtre anti-rejeu, identifiants d'événement et de livraison, rotation du secret, nouvelles tentatives.
---

# Webhooks { #webhooks }

L'**administrateur de l'entreprise** d'une personne morale peut abonner des points de terminaison HTTPS à
un ensemble sélectionné d'événements (`KYC_APPROVED`, `KYC_REJECTED`, `TRADE_EXECUTED`, événements d'ordres
de souscription, etc.). Registerwerk envoie en POST un document JSON signé à chaque point de terminaison
abonné. Cette page est le contrat applicable aux destinataires.

!!! note "Portée"
    Les webhooks sont un canal de notification, non une preuve juridique. Le registre et le journal d'audit
    font foi ; utilisez les webhooks pour déclencher vos propres lectures.

---

## Exigences du point de terminaison { #endpoint-requirements }

Registerwerk refuse (`400`) toute URL ne satisfaisant pas toutes ces conditions, et la revérifie à
**chaque livraison** :

- schéma `https`, sans informations d'utilisateur (`user:pass@`), port `443` ou `8443` ;
- l'hôte doit se résoudre **uniquement vers des adresses publiques** - boucle locale, adresses privées
  (RFC 1918), lien local (dont les métadonnées cloud `169.254.169.254`), NAT de niveau opérateur
  (`100.64.0.0/10`), IPv6 unique-local (`fc00::/7`), IPv6 mappées IPv4 et plages réservées/multicast sont
  refusées ;
- les redirections ne sont **pas suivies** - un `3xx` compte comme une livraison échouée ;
- délai de connexion 3 s, délai de réponse 5 s ; le corps de la réponse est ignoré. Répondez vite par
  n'importe quel `2xx` et traitez de façon asynchrone.

La connexion est établie vers l'adresse qui a été validée ; modifier le DNS après l'enregistrement ne peut
donc pas rediriger les livraisons vers une adresse interne.

---

## Gérer les abonnements { #managing-subscriptions }

Tous les points d'accès se trouvent sous `/api/v1/me/webhooks` et exigent le rôle `COMPANY_ADMIN`.

| Appel | Objet |
|---|---|
| `POST /` `{ "url", "eventTypes": [] }` | Créer. La réponse contient le `secret` de signature **une seule fois**. `eventTypes` vide signifie tous les événements. |
| `GET /` | Lister les abonnements (sans jamais renvoyer de secret). `disabledReason` est renseigné lorsque la plateforme en a désactivé un (`URL_POLICY`, `CIRCUIT_BREAKER`). |
| `PUT /{id}/enabled` | Activer ou désactiver. La réactivation revalide l'URL. |
| `POST /{id}/rotate-secret` | Émettre un nouveau secret (step-up requis). Renvoyé une seule fois. |
| `GET /{id}/deliveries` | Journal des livraisons : `id` (identifiant de livraison), `eventId`, `status`, `outcome`, `attemptCount`, `lastAttemptedAt`, `nextAttemptAt`. |
| `DELETE /{id}` | Supprimer. |

`outcome` est volontairement grossier : `OK`, `RECEIVER_ERROR` (non 2xx), `UNREACHABLE` (connexion ou
délai dépassé) ou `BLOCKED` (politique d'URL). Les codes HTTP et les messages d'erreur ne sont pas exposés.

---

## Format de livraison { #delivery-format }

En-têtes :

| En-tête | Signification |
|---|---|
| `X-Registerwerk-Event` | Type d'événement, p. ex. `TRADE_EXECUTED` |
| `X-Registerwerk-Event-Id` | Identifie l'événement ; **identique pour tous les abonnés** |
| `X-Registerwerk-Delivery` | Identifie cette livraison ; **stable d'une nouvelle tentative à l'autre** |
| `X-Registerwerk-Timestamp` | Secondes Unix, **renouvelées à chaque tentative** |
| `X-Registerwerk-Signature` | `v1=<hex>` ; pendant une rotation du secret, deux valeurs `v1=` séparées par une virgule |

Corps :

```json
{
  "eventId": "6f0c...",
  "deliveryId": "b21e...",
  "eventType": "TRADE_EXECUTED",
  "occurredAt": "2026-09-30T12:00:00Z",
  "data": { "executionId": "..." }
}
```

---

## Vérifier une livraison { #verifying }

La signature est `hex(HMAC-SHA256(secret, timestamp + "." + deliveryId + "." + rawBody))`, où `timestamp` et
`deliveryId` sont les valeurs des en-têtes et `rawBody` les octets exacts reçus (ne pas resérialiser le JSON).

1. Lire le corps **brut** et les en-têtes.
2. Rejeter si `abs(now - timestamp) > 300` secondes (fenêtre anti-rejeu).
3. Calculer la valeur attendue et la comparer à **chaque** valeur `v1=` en temps constant.
4. Dédupliquer : ignorer le traitement si ce `deliveryId` (nouvelle tentative) ou cet `eventId` (plusieurs
   abonnements) a déjà été traité. Renvoyer `2xx` pour les doublons.

```python
import hashlib, hmac, time

def verify(secret: str, headers: dict, raw_body: bytes, tolerance: int = 300) -> bool:
    ts = headers["X-Registerwerk-Timestamp"]
    if abs(time.time() - int(ts)) > tolerance:
        return False
    msg = f"{ts}.{headers['X-Registerwerk-Delivery']}.".encode() + raw_body
    expected = hmac.new(secret.encode(), msg, hashlib.sha256).hexdigest()
    offered = [p.strip()[3:] for p in headers["X-Registerwerk-Signature"].split(",")
               if p.strip().startswith("v1=")]
    return any(hmac.compare_digest(expected, o) for o in offered)
```

!!! warning "Anciennes intégrations"
    L'ancien en-tête de signature portant uniquement sur le corps a été **supprimé** : il pouvait être rejoué
    indéfiniment. Les destinataires doivent passer au schéma `v1` ci-dessus.

---

## Rotation du secret { #secret-rotation }

`POST /{id}/rotate-secret` renvoie un nouveau secret. Pendant 24 heures, l'ancien secret continue de
signer, de sorte que chaque livraison porte deux valeurs `v1=` ; vérifiez avec le secret que vous détenez
encore, déployez le nouveau, et l'ancien n'est plus utilisé après le chevauchement. Les secrets sont stockés
chiffrés et ne peuvent pas être relus - effectuez une rotation en cas de perte.

---

## Nouvelles tentatives et désactivation automatique { #retries }

Une livraison échouée est retentée avec un backoff exponentiel (environ 1, 2, 4 ... minutes, plafonné à 1
heure, avec gigue) jusqu'à 8 tentatives. Chaque tentative est resignée avec un nouvel horodatage. Après 20
échecs consécutifs, l'abonnement est désactivé automatiquement (`disabledReason = CIRCUIT_BREAKER`) ;
corrigez le destinataire puis réactivez-le.

---

## Événements de rejet KYC { #kyc-rejection }

`KYC_REJECTED` ne transporte qu'une catégorie fixe et jamais le raisonnement de l'analyste :

```json
{ "entityId": "...", "reasonCode": "INFORMATION_INCOMPLETE" }
```

`reasonCode` vaut `INFORMATION_INCOMPLETE`, `DOCUMENTS_UNREADABLE`, `INFORMATION_INCONSISTENT` ou
`CONTACT_SUPPORT`. Le motif interne est conservé uniquement dans le journal d'audit.
