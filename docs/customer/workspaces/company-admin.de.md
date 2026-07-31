---
title: Unternehmensadministrator
description: Die Nutzer Ihrer Organisation, ihre On-Chain-Identität und die Anmeldung Ihrer Leute verwalten.
---

# Unternehmensadministrator

**Sie sind für Ihre Organisation innerhalb des Registers verantwortlich.** Wer ein Konto hat, was er tun darf, wie er sich anmeldet und wie Ihr Unternehmen on-chain identifiziert wird.

Das ist kein eigener Arbeitsbereich — es erscheint als **Company Admin** innerhalb des Arbeitsbereichs Issuer. Es ist eine Verantwortung, die sich über alles legt, was Sie sonst tun.

---

## Was es hier gibt

| Reiter | Wofür |
|---|---|
| **Users** | Personen einladen, Rollen zuweisen, Ausscheidende deaktivieren. |
| **IdP Settings** | Ihr unternehmensweites Single Sign-on anbinden. |
| **Organization** | Ihre On-Chain-Identität und die daran gebundenen Wallets. |
| **External IDs** | Kennungen, die Ihre Organisation mit Außensystemen verbinden. |

---

## Users

*Company Admin → Users.*

Sie laden Personen ein, weisen Rollen zu und deaktivieren sie beim Ausscheiden. Rollen, die Sie innerhalb Ihrer Organisation vergeben können:

| Rolle | Erlaubt |
|---|---|
| `INVESTOR` | Wertpapiere halten und einsehen. |
| `TRADER` | Kaufen, verkaufen und Liquiditätsmärkte nutzen. |
| `ISSUER` | Emissionen anlegen und verwalten. |
| `COMPANY_ADMIN` | Alles auf dieser Seite. |
| `DAPP_PUBLISHER` | Anwendungen im Marketplace veröffentlichen. |

Eine Person kann mehrere halten. Rollen bestimmen, welche [Arbeitsbereiche](index.md) erscheinen — und, wichtiger, was das Backend tatsächlich zulässt.

!!! danger "Ausscheidende noch am selben Tag deaktivieren"
    Ein Konto, das nach dem Ausscheiden weiter funktioniert, ist ein Konto, das weiterhin Wertpapiere bewegen kann.

    Die Deaktivierung wirkt sofort und ist umkehrbar. Sie löscht nichts: Vergangene Handlungen bleiben dauerhaft im [Audit-Log](../../platform/audit-log.md), der Person zugeordnet. Genau das ist der Sinn — Sie können jemandem den Zugang nehmen, ohne die Aufzeichnung seines Tuns zu tilgen.

!!! warning "Sie können nicht mehr vergeben, als Sie haben"
    Ebenso wenig eine Rolle, die Ihre Organisation nicht hält. Ist Ihr Rechtsträger als Anleger registriert, können Sie keinen Ihrer Nutzer zum Emittenten machen. Das ist eine Entscheidung des Betreibers.

### Wenn die Anmeldung anderswo verwaltet wird

Läuft Ihr Register auf Microsoft Entra ID und ist Ihre Organisation **föderiert** — Ihre Leute melden sich mit Ihren eigenen Unternehmenskonten an —, dann liegt der Nutzer-Lebenszyklus in *Ihrem* Identity Provider, nicht hier. Die Seite sagt Ihnen das.

Die Registerwerk-Rollen vergeben Sie weiterhin hier. Wer existiert, ist Sache Ihres IdP; was er darf, ist Ihre.

---

## IdP-Einstellungen

*Company Admin → IdP Settings.* Binden Sie Ihren OIDC-konformen Identity Provider an, damit Ihre Leute sich mit Unternehmenszugangsdaten statt mit einem separaten Passwort anmelden.

Sie hinterlegen eine **Issuer-URL** und eine **Client-ID**.

!!! info "Es gibt bewusst kein Client Secret"
    Vielleicht erwarten Sie ein drittes Feld. Es gibt keines, und das ist kein Versehen.

    Eingehende Föderation wird **im Identity Provider von Mandant zu Mandant** eingerichtet. Registerwerk führt niemals einen Authorization-Code-Flow gegen Ihren Mandanten aus und hat für Ihr Client Secret daher keine Verwendung — es zu speichern hieße, ein Geheimnis von Ihnen vorzuhalten, das gar nicht gebraucht wird.

    Das Feld wurde entfernt und vorhandene Werte wurden gelöscht.

Zwei Zeilen dieser Seite sind **schreibgeschützt**, und beide setzt der Registerbetreiber:

| | |
|---|---|
| **Identity model** | Ob Ihre Nutzer Gäste im Mandanten des Betreibers sind, Mitglieder darin, oder aus Ihrem eigenen föderiert. |
| **Inbound MFA trust** | Ob eine in *Ihrem* Mandanten durchgeführte Zwei-Faktor-Authentifizierung hier anerkannt wird. |

!!! warning "Warum MFA-Vertrauen nicht Ihre Entscheidung ist"
    Wenn ein Kunde behaupten könnte „vertraut unserer MFA", wäre das ein Weg zur Rechteausweitung: Sie könnten die Anmeldehürde für Ihre eigenen Nutzer senken, indem Sie Ihre eigenen Vorkehrungen für ausreichend erklären.

    Das entscheidet der Betreiber. Bitten Sie ihn um eine Änderung; selbst können Sie sie nicht vornehmen.

[:octicons-arrow-right-24: Anmelden](../authentication.md) · [:octicons-arrow-right-24: Entra-ID-Einrichtung](../../platform/entra-setup.md)

---

## Organization — Ihre On-Chain-Identität

*Company Admin → Organization.*

Ihre Organisation hat eine Identität **auf der Blockchain** ebenso wie im Register. Sie ist der Anker für Berechtigungen im Ökosystem: welche Wallets für Sie handeln und was Anwendungen in Ihrem Namen dürfen.

### Eine Wallet binden

Um eine Wallet an Ihre Organisation zu binden, weisen Sie deren Kontrolle nach, indem Sie eine **Nonce-Challenge** signieren — die Plattform gibt einen Zufallswert aus, Sie signieren ihn mit dem Schlüssel der Wallet, und die Signatur belegt den Besitz, ohne den Schlüssel je preiszugeben.

Einmal gebunden, handelt diese Wallet on-chain für Ihre Organisation.

!!! warning "Eine Organisation je Wallet und Chain"
    Eine Wallet kann auf derselben Chain nicht zwei Organisationen vertreten. Brauchen Sie getrennte Identitäten, nutzen Sie getrennte Wallets.

### Berechtigungen und Delegation

Der Betreiber erteilt Ihrer Organisation **Berechtigungen** — das Recht, eine bestimmte Fähigkeit zu nutzen. Sie delegieren diese dann an Rollen innerhalb Ihrer Organisation und können eine Berechtigung wahlweise als **rollengebunden** markieren: Sie auf Organisationsebene zu halten genügt dann nicht; das einzelne Mitglied braucht zusätzlich die delegierte Rolle.

```mermaid
graph LR
    O["Operator"] -->|"grants permission"| ORG["Your organisation"]
    ORG -->|"delegates to role"| M["Your members"]
```

So kann eine dApp darauf vertrauen, dass die aufrufende Wallet zu einer Organisation gehört, die zu dem Verlangten berechtigt ist — ohne dass die dApp irgendetwas über Ihre interne Struktur wüsste.

??? note "Für Spezialisten: die Contracts darunter"

    **OrgRegistry** hält die Bindungen von Wallets an Organisationen; die Organisation *ist* ihre ONCHAINID-Adresse. Die Autorisierung ist zweigleisig: entweder ein Betreiber mit `OPERATOR_ROLE` oder ein ERC-734-MANAGEMENT-Schlüssel auf der eigenen ONCHAINID der Organisation.

    **PermissionRegistry** hält vom Betreiber erteilte Berechtigungen als `keccak256("<slug>.<action>")`, dazu die Delegation durch Org-Admins an Mitgliederrollen und das Rollenbindungs-Flag.

    **PermissionOracle** ist die stabile Fassade, die eine dApp speichert. Kunden-dApps erben `RegisterwerkGated` mit `requiresPermission`, `requiresClaim` und `requiresActiveMember`. Diese Indirektion bedeutet, dass dApps nicht neu ausgebracht werden müssen, wenn die Registries umziehen.

    [:octicons-arrow-right-24: dApp-Entwicklung](../../platform/dapp-development.md)

---

## External IDs

Kennungen, die Ihre Organisation mit Systemen außerhalb des Registers verbinden — LEI, nationale Registernummern, Referenzen von Verwahrstellen.

Unglamourös — und genau das, was den Abgleich mit der Außenwelt möglich macht.

---

## Ihre wiederkehrenden Aufgaben

- **Jeder Zugang und jeder Abgang.** Deaktivieren Sie noch am Tag des Ausscheidens.
- **Vierteljährlich Rollen prüfen.** Berechtigungen sammeln sich an. Menschen wechseln Teams und behalten Zugänge, die sie nicht mehr brauchen.
- **Den KYC-Ablauf im Blick behalten.** Läuft die Verifizierung Ihrer Organisation aus, stoppen Übertragungen für alle. Die Erneuerung braucht Zeit — beginnen Sie davor, nicht danach.
- **Wallet-Bindungen aktuell halten.** Eine gebundene Wallet, die niemand mehr kontrolliert, ist ein Risiko.

---

## Wohin als Nächstes

- [Rollen und Berechtigungen](../../operator/customers/roles.md) — das vollständige Modell
- [Anmelden](../authentication.md)
- [dApp-Herausgeber](dapp-publisher.md)
