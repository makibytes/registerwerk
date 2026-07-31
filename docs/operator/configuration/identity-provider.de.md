---
title: Identitätsanbieter
---

# Identitätsanbieter (OIDC)

## Integrierte Administratoranmeldung (Entwicklungs-/Kein-IdP-Modus)

Bei `ENTRA_ENABLED=false` (Standardeinstellung) zeigt das Operator-Frontend ein Benutzername/Passwort-Formular
anstelle der Schaltfläche „Mit Microsoft anmelden“ an. Das Backend stellt `POST /api/v1/public/auth/login`
bereit und gibt ein kurzlebiges, mit `JWT_DEV_SECRET` signiertes HS256-JWT aus.

Konfigurieren Sie über Umgebungsvariablen:

```dotenv
ENTRA_ENABLED=false
DEFAULT_ADMIN_EMAIL=admin@local
DEFAULT_ADMIN_PASSWORD=changeme-please
JWT_DEV_SECRET=change-me-for-staging
```

Beim Start legt das Backend eine Zeile in der Tabelle `app_user` mit der konfigurierten
E-Mail-Adresse und einem BCrypt-Hash des Passworts an (oder aktualisiert sie). Das Passwort zu rotieren ist so einfach wie
`DEFAULT_ADMIN_PASSWORD` zu ändern und den Dienst neu zu starten – der Hash wird bei jedem Start aktualisiert.

!!! warning "Nicht für die Produktion"
    Das HS256-Entwicklungsgeheimnis und der integrierte Administrator sind nur für lokale Entwicklungs- und Demo-
    Umgebungen gedacht. Konfigurieren Sie für die Produktion unten einen echten Identitätsanbieter und setzen Sie
    `ENTRA_ENABLED=true` + `JWT_ISSUER_URI=<your-issuer>`. Der Endpunkt `/api/v1/public/auth/login`
    liefert 404, wenn `ENTRA_ENABLED=true` gesetzt ist.



Das Backend ist ein OAuth2-Resource-Server. Es akzeptiert JWTs von jedem OIDC-konformen Anbieter.

## Microsoft Entra ID (empfohlen)

1. Registrieren Sie eine Anwendung im Azure-Portal → App-Registrierungen
2. Fügen Sie API-Berechtigungen hinzu: `openid`, `profile`, `email`
3. Definieren Sie App-Rollen: `REGISTRY_ADMIN`, `AUDIT`, `ISSUER`, `INVESTOR`, `COMPANY_ADMIN`
4. Umgebungsvariablen festlegen:
   ```dotenv
   JWT_ISSUER_URI=https://login.microsoftonline.com/<tenant-id>/v2.0
   ENTRA_ISSUER=https://login.microsoftonline.com/<tenant-id>/v2.0
   ENTRA_CLIENT_ID=<app-id>
   ENTRA_CLIENT_SECRET=<client-secret>
   ```

Optional können Sie bei Betrieb von Kong Enterprise/Konnect OIDC zusätzlich am Gateway
mit `gateway/plugins/oidc-entra.yml` terminieren – das Backend validiert das JWT so oder so selbst,
das ist also Tiefenverteidigung (Defense-in-Depth), keine Voraussetzung.

## Selbstverwaltetes Keycloak

1. Erstellen Sie einen Realm und einen Client
2. Fügen Sie Realm-Rollen hinzu, die den obigen Rollennamen entsprechen
3. Konfigurieren Sie den Token-Mapper so, dass Rollen in den JWT-`roles`-Claim einbezogen werden
4. Umgebungsvariablen festlegen:
   ```dotenv
   JWT_ISSUER_URI=https://keycloak.yourhost.com/realms/ewpg
   ENTRA_ISSUER=https://keycloak.yourhost.com/realms/ewpg
   ENTRA_CLIENT_ID=<client-id>
   ENTRA_CLIENT_SECRET=<client-secret>
   ```

Optional können Sie OIDC auch bei Kong terminieren, mit `gateway/plugins/oidc-self-managed.yml` (nur Enterprise/Konnect).

## Erwartete JWT-Claims

Der `JwtEntityClaimsConverter` des Backends liest Claims direkt vom validierten JWT — er verlässt sich
nicht auf einen vom Gateway eingefügten Header:
- `sub` – Benutzer-Subjekt
- `roles` – Liste von Rollen-Strings (z. B. `["ISSUER", "COMPANY_ADMIN"]`), umgewandelt in `ROLE_*`-Berechtigungen
- `entity_id` – die UUID der juristischen Person, für Multi-Tenant-Scoping

Konfigurieren Sie das Token-/Claims-Mapping Ihres IdP so, dass diese im ausgestellten JWT vorhanden sind. Im OSS-Kong-Setup dieses Repos gibt es keinen Kong-seitigen Schritt zur Entitätszuordnung.
