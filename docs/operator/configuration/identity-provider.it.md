---
title: Fornitore di identità
---

# Fornitore di identità (OIDC) { #identity-provider-oidc }

## Accesso amministratore integrato (modalità sviluppo/no-IdP) { #built-in-admin-login-development-no-idp-mode }

Quando `ENTRA_ENABLED=false` (impostazione predefinita), il frontend dell'operatore mostra un modulo nome utente/password
invece del pulsante "Accedi con Microsoft". Il backend espone `POST /api/v1/public/auth/login`
ed emette un HS256 JWT di breve durata firmato con `JWT_DEV_SECRET`.

Configura tramite variabili di ambiente:

```dotenv
ENTRA_ENABLED=false
DEFAULT_ADMIN_EMAIL=admin@local
DEFAULT_ADMIN_PASSWORD=changeme-please
JWT_DEV_SECRET=change-me-for-staging
```

All'avvio il backend semina (o aggiorna) una riga nella tabella `app_user` con l'email
configurata e un hash BCrypt della password. Ruotare la password è semplice come cambiare
`DEFAULT_ADMIN_PASSWORD` e riavviare il servizio: l'hash viene aggiornato ad ogni avvio.

!!! warning "Non per la produzione"
    Il segreto di sviluppo HS256 e l'amministratore integrato sono destinati esclusivamente allo sviluppo locale e agli ambienti demo. Per la produzione, configura un provider di identità reale di seguito e imposta
    `ENTRA_ENABLED=true` + `JWT_ISSUER_URI=<your-issuer>`. L'endpoint `/api/v1/public/auth/login`
    restituisce 404 quando `ENTRA_ENABLED=true`.



Il backend è un server di risorse OAuth2. Accetta JWT da qualsiasi provider compatibile con OIDC.

## Microsoft Entra ID (consigliato) { #microsoft-entra-id-recommended }

1. Registra un'applicazione in Azure Portal → App registrations
2. Aggiungi le autorizzazioni API: `openid`, `profile`, `email`
3. Definisci i ruoli dell'app: `REGISTRY_ADMIN`, `AUDIT`, `ISSUER`, `INVESTOR`, `COMPANY_ADMIN`
4. Imposta le variabili di ambiente:
   ```dotenv
   JWT_ISSUER_URI=https://login.microsoftonline.com/<tenant-id>/v2.0
   ENTRA_ISSUER=https://login.microsoftonline.com/<tenant-id>/v2.0
   ENTRA_CLIENT_ID=<app-id>
   ENTRA_CLIENT_SECRET=<client-secret>
   ```

Opzionalmente, se si esegue Kong Enterprise/Konnect, è possibile inoltre terminare OIDC sul gateway
utilizzando `gateway/plugins/oidc-entra.yml`: il backend convalida JWT stesso in entrambi i casi,
quindi si tratta di una difesa approfondita, non di un requisito.

## Keycloak autogestito { #self-managed-keycloak }

1. Crea un realm e un client
2. Aggiungi ruoli realm corrispondenti ai nomi di ruolo sopra
3. Configura il mapping dei token per includere i ruoli nell'attestazione `roles` del JWT
4. Imposta le variabili di ambiente:
   ```dotenv
   JWT_ISSUER_URI=https://keycloak.yourhost.com/realms/ewpg
   ENTRA_ISSUER=https://keycloak.yourhost.com/realms/ewpg
   ENTRA_CLIENT_ID=<client-id>
   ENTRA_CLIENT_SECRET=<client-secret>
   ```

Opzionalmente, termina OIDC anche a Kong utilizzando `gateway/plugins/oidc-self-managed.yml` (solo Enterprise/Konnect).

## Attestazioni JWT previste { #jwt-claims-expected }

`JwtEntityClaimsConverter` del backend legge le attestazioni direttamente dallo JWT convalidato — non si basa su alcuna intestazione inserita dal gateway:
- `sub` — soggetto utente
- `roles` — elenco di stringhe di ruoli (ad esempio `["ISSUER", "COMPANY_ADMIN"]`), trasformato in autorità `ROLE_*`
- `entity_id` — l'UUID del soggetto giuridico, per l'ambito multi-tenant

Configura la mappatura dei token/attestazioni del tuo IdP in modo che siano presenti nello JWT emesso. Non è presente alcun passaggio di mappatura delle entità sul lato
Kong nella configurazione OSS Kong di questo repository.
