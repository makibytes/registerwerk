---
title: Anmelden
description: Wie Sie sich anmelden, was die Zwei-Faktor-Authentifizierung leistet und was zu tun ist, wenn Sie nicht hineinkommen.
---

# Anmelden

Wie Sie sich anmelden, hängt davon ab, wie Ihr Registerbetreiber die Plattform konfiguriert hat. Es gibt zwei Modi, und sie verhalten sich unterschiedlich genug, dass es sich lohnt zu wissen, in welchem Sie sich befinden.

**Der schnellste Weg, das zu erkennen:** Zeigt die Anmeldeseite Felder für E-Mail und Passwort, sind Sie im lokalen Modus. Zeigt sie eine Schaltfläche **Sign in with Microsoft**, sind Sie im Entra-Modus.

---

## Die beiden Modi

=== "Lokaler Modus — die Voreinstellung"

    Sie melden sich mit einer E-Mail-Adresse und einem Passwort an, das das Register selbst vorhält.

    **Kein zweiter Faktor bei der Anmeldung.** Das ist die Standardkonfiguration und das, was ein gewöhnliches `docker compose up` liefert. Sie ist für lokale Installationen, Demonstrationen und Evaluierungen gedacht.

    Ihr Passwort lässt sich über den üblichen Zurücksetzen-Ablauf neu setzen.

=== "Entra-Modus — Produktion"

    Sie melden sich mit **Microsoft Entra ID** über das Microsoft-Konto Ihrer Organisation an, und **Zwei-Faktor-Authentifizierung ist Pflicht**.

    Das Register sieht Ihr Passwort nie. Microsoft authentifiziert Sie und stellt ein Token aus; das Register prüft es.

!!! info "Mitarbeitende des Betreibers nutzen immer die eingebaute Anmeldung"
    Auch im Entra-Modus melden sich Mitarbeitende des Registerbetreibers mit Benutzername und Passwort an und nutzen für sensible Handlungen eine lokale Authenticator-App.

    Nur das **Kunden**-Portal wechselt zu Entra. Falls Sie gelesen haben, Entra sei die Voreinstellung für alle einschließlich der Betreibermitarbeitenden: Das war falsch — so hat sich die Plattform nie verhalten.

---

## Zwei-Faktor-Authentifizierung

Gilt im Entra-Modus.

Zwei-Faktor-Authentifizierung ist im Kundenportal in der Produktion **Pflicht**. Durchgesetzt wird sie von Microsoft Conditional Access bei der Anmeldung, **nicht vom Portal** — haben Sie keinen zweiten Faktor registriert, fordert Microsoft Sie dazu auf, bevor Sie fortfahren können. Nicht registriert erreichen Sie Registerwerk gar nicht erst.

Die Seite **Security** (Nutzermenü → Security) zeigt Ihren Status und führt Sie durch die Einrichtung.

!!! note "Warum das Register Ihnen keinen Einrichtungs-QR-Code geben kann"
    Microsoft besitzt die Zugangsdaten. Deren API bietet keine Möglichkeit, eine Authenticator- oder TOTP-Methode anzulegen — das Geheimnis wird niemandem offengelegt, auch dem Register nicht.

    Der Code, den Sie scannen, wird deshalb auf **Microsofts eigener Security-Info-Seite** angezeigt. Der QR-Code auf unserer Security-Seite ist schlicht ein **Link auf diese Seite**, damit Sie vom Rechner zu dem Telefon wechseln können, das den Authenticator halten wird.

    Das ist eine Beschränkung von Entra, keine fehlende Funktion. Keine Software kann es anders machen.

**So richten Sie Microsoft Authenticator ein:**

1. Installieren Sie **Microsoft Authenticator** auf Ihrem Telefon.
2. Öffnen Sie **Security** im Portal und scannen Sie den QR-Code oder wählen Sie **Set up now**.
3. Fügen Sie auf der Microsoft-Seite eine Anmeldemethode hinzu und folgen Sie den dortigen Hinweisen.
4. Kehren Sie ins Portal zurück und wählen Sie **I've finished** — die Seite prüft erneut und bestätigt.

### Telefon verloren oder ersetzt

Wenden Sie sich an den Registerbetreiber. Nach einer Identitätsprüfung über einen anderen Kanal entfernt er Ihre alten Methoden, **beendet Ihre bestehenden Sitzungen** und stellt einen **Temporary Access Pass** aus — einen kurzlebigen, meist einmalig nutzbaren Code, mit dem Sie sich einmal anmelden können, um eine neue Methode zu registrieren.

Nutzen Sie ihn zügig; er verfällt typischerweise binnen einer Stunde.

!!! warning "Betreibt Ihre Organisation einen eigenen Entra-Mandanten, kann der Betreiber nicht helfen"
    Ihre Nutzer liegen in *Ihrem* Verzeichnis, nicht in seinem. Er kann Ihre Authentifizierungsmethoden nicht zurücksetzen, und die Support-Konsole verweigert den Versuch.

    Wenden Sie sich an Ihren eigenen IT-Helpdesk.

---

## Wenn Ihre Organisation einen eigenen Identity Provider nutzt

Organisationen, die beim [Onboarding](onboarding.md) einen Identity Provider konfiguriert haben, melden sich über ihren **eigenen Microsoft-Entra-Mandanten** an.

Der Zugang wird **von Mandant zu Mandant** in Entra eingerichtet, über B2B-Zusammenarbeit und mandantenübergreifende Zugriffseinstellungen. Das Register führt niemals einen Authorization-Code-Flow gegen Ihren Mandanten aus und **fragt daher nie nach einem Client Secret** — nur nach Ihrer Issuer-URL und Client-ID, zur Identifikation.

In diesem Modell gilt:

- Ihre Administratoren steuern, welche Authentifizierungsmethoden verfügbar sind und wie stark sie sind.
- Eine in Ihrem Mandanten durchgeführte Mehr-Faktor-Authentifizierung wird hier **nur anerkannt, wenn der Registerbetreiber eingehendes MFA-Vertrauen konfiguriert hat**. Das entscheidet der Betreiber, nicht Sie — ein Kunde, der für die eigene MFA bürgt, wäre ein Weg, die Hürde für die eigenen Nutzer zu senken.
- **Der Registerbetreiber kann die zweiten Faktoren Ihrer Nutzer nicht zurücksetzen.** Das tut Ihr Helpdesk.

---

## Woher Ihre Berechtigungen kommen

!!! danger "Ihr Identity Provider entscheidet nicht, was Sie dürfen"
    Das überrascht Administratoren, und es falsch zu verstehen hat echte Folgen.

    Entra beantwortet *wer ist diese Person*. **Registerwerk beantwortet, was sie darf** — aus dem eigenen Nutzerdatensatz. Entra-App-Rollen werden genau einmal herangezogen, beim erstmaligen Anlegen Ihres Kontos, um eine sinnvolle Voreinstellung zu wählen.

    Also: **Jemandem in Entra die App-Rolle zu entziehen entzieht ihm nicht die Registerwerk-Berechtigungen.** Ein Administrator, der das tut und den Zugang für widerrufen hält, irrt.

    Um zu ändern, was jemand darf, ändern Sie es in Registerwerk — das tut Ihr [Unternehmensadministrator](workspaces/company-admin.md). Um die Anmeldung ganz zu unterbinden, deaktivieren Sie das Konto in Entra.

Ältere Dokumentation beschrieb, Rollen würden aus einem `roles`- oder `groups`-Claim in Ihrem Token abgebildet. So funktioniert es nicht, und einen solchen Claim zu konfigurieren hat hier keine Wirkung.

---

## Sitzungen

Sitzungen dauern voreingestellt **8 Stunden**, danach melden Sie sich erneut an.

Im Entra-Modus können die Conditional-Access-Richtlinien Ihrer Organisation eine frühere erneute Authentifizierung verlangen, und sensible Handlungen können unabhängig von der Restlaufzeit Ihrer Sitzung einen frischen Identitätsnachweis fordern. Das ist die [Step-up-Authentifizierung](../compliance/step-up-mfa.md) und funktioniert wie vorgesehen — es ist kein Sitzungsproblem.

---

## Die API direkt aufrufen

Für Integrationen holen Sie ein Token und senden es als `Authorization: Bearer <token>`.

Im **Entra-Modus** holen Sie das Token bei Entra über Ihre eigene App-Registrierung und den Scope, den Ihr Betreiber Ihnen nennt. Im **lokalen Modus** liefert `POST /api/v1/public/auth/login` eines.

!!! warning "Legen Sie nie ein Token in Frontend-Code oder ein Repository"
    Nutzen Sie Umgebungsvariablen oder einen Secrets-Manager. Ein durchgesickertes Token ist eine Sitzung als Sie, für seine verbleibende Lebensdauer.

[:octicons-arrow-right-24: API-Überblick](../platform/api.md)

---

## Wenn Sie nicht hineinkommen

| Was Sie sehen | Heißt meist | Tun Sie |
|---|---|---|
| **Account not recognised** | Ihr Microsoft-Konto liegt in keinem Mandanten, den der Betreiber zugelassen hat | Betreiber kontaktieren |
| **Access denied** nach der Anmeldung | Die Anmeldung hat geklappt; Ihnen fehlt eine Rolle | Ihren Unternehmensadministrator fragen |
| **Aufforderung, Sicherheitsinfos zu registrieren** | Zwei-Faktor noch nicht eingerichtet | Folgen Sie ihr — sie ist Pflicht |
| **Token expired** | Sitzung beendet | Erneut anmelden |
| **Weiterleitungsschleife** | Fehlkonfiguration auf Seiten des Betreibers | Betreiber kontaktieren — das können Sie nicht beheben |
| **Alles sieht gut aus, nichts funktioniert** | Das [KYC](kyc.md) Ihrer Organisation ist womöglich abgelaufen | Die KYC-Seite prüfen |

!!! tip "Der Unterschied zwischen 401 und 403 ist es wert, gekannt zu werden"
    Wenn Sie ein Problem melden, spart es allen Zeit, wenn Sie sagen, was Sie bekommen haben.

    **401** — Ihr Token wird nicht akzeptiert. Ein Anmeldeproblem.
    **403** — Ihr Token ist in Ordnung, Ihre Berechtigungen nicht. Ein Rollenproblem, das Ihr Unternehmensadministrator wahrscheinlich ohne den Betreiber lösen kann.

---

## Wohin als Nächstes

- [Zugang erhalten](onboarding.md)
- [Unternehmensadministrator](workspaces/company-admin.md) — Nutzer und IdP-Einstellungen verwalten
- [Step-up-MFA](../compliance/step-up-mfa.md) — warum manche Handlungen erneut fragen
