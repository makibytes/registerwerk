---
title: Passerelle API (Kong)
---

# Passerelle API (Kong)

Kong 3.8 (OSS, sans DB) se trouve devant le **trafic API de l'interface client uniquement**. Il gère la limitation de débit, la mise en cache des réponses et les en-têtes de sécurité. Il ne dessert **pas** l'interface utilisateur de l'un ou l'autre frontend — les deux applications sont toujours ouvertes directement par le navigateur sur leur propre port (`:4200`, `:4201`) — et l'**interface opérateur contourne entièrement Kong**, même pour ses propres appels API (son nginx achemine `/api/` directement vers `backend:8080`). La validation JWT et l'extraction d'entité/rôle se produisent toujours dans le backend Spring lui-même, à partir des revendications propres au jeton — et non via un en-tête injecté par Kong, dans la configuration OSS livrée par ce dépôt.

## Démarrage de Kong

```bash
docker compose up -d kong
```

Kong fonctionne en mode sans base de données (déclaratif) — il lit `gateway/kong.yml` directement via
`KONG_DECLARATIVE_CONFIG` et n'a besoin d'aucune base de données propre.

## Configuration déclarative

Kong est configuré via `gateway/kong.yml` au format deck. Pour appliquer les modifications :

```bash
deck sync --config gateway/kong.yml
```

## Plugins clés

Seuls les plugins Kong OSS fournis sont actifs par défaut (voir `gateway/kong.yml`) :

| Plugin | Objectif |
|---|---|
| `proxy-cache` | Met en cache 200 réponses GET de voie publique pendant 30 à 60 secondes |
| `request-transformer` | Supprime tous les `X-Entity-Id`/`X-Entity-Roles` fournis par le client sur les routes publiques, de sorte que rien ne puisse être introduit clandestinement avant même que le backend ne voie la demande |
| `rate-limiting` | 300 requêtes/minute, 10 000/heure par consommateur |
| `bot-detection` | Bloque les agents utilisateurs courants des robots d'exploration/scanner |
| `ip-restriction` | Restreint `/api/v1/admin/**` aux CIDR du réseau de l'opérateur |
| `cors` | En-têtes d'origine croisée pour le frontend client Angular |
| `request-size-limiting` | Corps de requête maximum de 20 Mo |
| `response-transformer` | Ajoute des en-têtes de sécurité standard (HSTS, CSP, X-Frame-Options, …) |

`openid-connect` (terminaison JWT au niveau de la passerelle) est **Kong Enterprise/Konnect uniquement** et n'est pas
actif dans cette configuration OSS — un extrait prêt à fusionner se trouve dans `gateway/plugins/oidc-entra.yml` pour les déploiements
qui exécutent Kong Enterprise. Sans cela, la validation JWT et l'extraction d'entité/rôle se produisent entièrement dans le backend Spring, en lisant les revendications sur le jeton lui-même — Kong n'injecte jamais les en-têtes `X-Entity-Id`/`X-Entity-Roles` ici.

## API d'administration de Kong

Kong fonctionne sans base de données et ne fournit **aucune interface graphique d'administration** dans cette pile (pas de Konga, pas de Kong Manager — les deux ont été supprimés/jamais câblés). L'accès à l'API d'administration est volontairement limité au loopback :

```bash
# Bound to 127.0.0.1:8001 on the host — never expose this publicly, it's unauthenticated
docker compose exec kong kong health
curl http://127.0.0.1:8001/status
```

Pour modifier le routage/les plugins, modifiez `gateway/kong.yml` et redémarrez le service `kong` — c'est la source unique de vérité
en mode sans base de données.
