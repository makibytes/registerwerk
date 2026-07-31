---
title: Onboarding
---

# Onboarding

Diese Anleitung führt Sie durch die Registrierung Ihrer Organisation im eWpG-Register — von der ersten Einladungs-E-Mail bis zum vollständig eingerichteten Konto.

## So läuft das Onboarding ab

Das Onboarding wird vom Registerbetreiber angestoßen, nicht durch Selbstregistrierung. Der Ablauf umfasst diese vier Schritte:

```
Operator creates entity
        |
        v
You receive an invitation email with a one-time token
        |
        v
You redeem the token and configure your organization
        |
        v
Admin activates your account — you can start working
```

## Schritt 1 — Ihre Einladung erhalten

Der Registerbetreiber legt in Ihrem Namen einen Rechtsträger an (Unternehmen oder natürliche Person). Sie erhalten vom Register eine E-Mail mit dem Betreff **„Your eWpG Registry Invitation"**, die Folgendes enthält:

- Ein einmaliges **Onboarding-Token** (48 Stunden gültig)
- Einen Link zum Kundenportal

!!! warning "Ablauf des Tokens"
    Das Onboarding-Token verfällt nach 48 Stunden. Ist es abgelaufen, wenden Sie sich an den Registerbetreiber und bitten Sie um ein neues. Geben Sie das Token nicht weiter — es gewährt vollen Einrichtungszugriff auf Ihr Konto.


## Schritt 2 — Das Token einlösen

1. Klicken Sie den Link in der Einladungs-E-Mail. Sie gelangen zum Kundenportal.
2. Sie werden gebeten, sich über Ihren Identity Provider anzumelden (siehe [Anmelden](./authentication.md)). Für neue Nutzer ist das typischerweise Microsoft Entra ID (früher Azure AD) mit Ihrer Unternehmens-E-Mail-Adresse.
3. Nach der Anmeldung erkennt das Portal Ihr Onboarding-Token aus der URL und aktiviert Ihren Rechtsträger automatisch.
4. Sie werden auf den **Welcome**-Bildschirm geleitet, der Ihre zugewiesene Rolle zeigt (Issuer, Investor oder Auditor).

## Schritt 3 — Ihre Organisation konfigurieren

Nach dem Einlösen des Tokens können Sie das Profil Ihrer Organisation konfigurieren:

### Angaben zur Organisation

Gehen Sie zu **Settings → Organization** und füllen Sie aus:

| Feld | Beschreibung |
|-------|-------------|
| Legal name | Ihr eingetragener Firmenname |
| LEI | Legal Entity Identifier (für Emittenten erforderlich) |
| Registration number | Handelsregisternummer |
| Jurisdiction | Land der Gründung |
| Contact email | Hauptkontakt für aufsichtsrechtliche Mitteilungen |

### Nutzerverwaltung

Hat Ihre Organisation mehrere Nutzer, gehen Sie zu **Settings → Users** und laden Sie diese per E-Mail ein. Jeder eingeladene Nutzer:
- erhält eine eigene Einladungs-E-Mail
- meldet sich mit seiner eigenen Unternehmensidentität an
- erhält eine der Rollen Ihrer Organisation zugewiesen

### Einen eigenen Identity Provider konfigurieren (optional)

Nutzt Ihre Organisation einen eigenen Identity Provider (etwa Ihr eigenes Keycloak, Okta oder einen anderen OIDC-kompatiblen IdP), können Sie ihn unter **Settings → Identity Provider** konfigurieren.

Sie hinterlegen dazu:

```
OIDC Issuer URL:       https://your-idp.example.com/realms/your-realm
Client ID:             registerwerk-client
```

!!! info "Es gibt kein Feld für ein Client Secret"
    Die Föderation wird von Mandant zu Mandant in Ihrem eigenen Identity Provider eingerichtet. Registerwerk führt niemals einen Authorization-Code-Flow gegen Ihren Mandanten aus und hat für ein Client Secret von Ihnen daher keine Verwendung — das Feld wurde entfernt, statt es Zugangsdaten sammeln zu lassen, die niemand braucht. Siehe [Unternehmensadministrator](workspaces/company-admin.md).


Ist die Konfiguration eingerichtet und verifiziert, werden alle Nutzer Ihrer Organisation zur Authentifizierung an Ihren IdP weitergeleitet statt an die standardmäßige Entra-ID-Anmeldung.

## Schritt 4 — Kontoaktivierung

Ihr Konto ist nun aktiv. Je nach Rolle:

- **Emittenten**: Möglicherweise müssen Sie eine KYC/AML-Prüfung abschließen, bevor Sie Token im Mainnet ausbringen können. Siehe [Eine Emission anlegen](lifecycle/primary-issuance.md).
- **Anleger**: Ihr Konto ist bereit. Sie können eine Wallet verbinden und Ihre Bestände einsehen.
- **Prüfer**: Ihr Konto ist bereit. Sie haben lesenden Zugriff auf alle Registerdaten.

## Hilfe nötig?

Treten beim Onboarding Probleme auf, wenden Sie sich über den Support-Link in der Einladungs-E-Mail oder die Schaltfläche **Help** in der Fußzeile des Portals an den Registerbetreiber.
