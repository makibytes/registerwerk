---
title: Zwei-Faktor-Support (Telefon verloren)
description: Das Handbuch für den Fall „Telefon verloren", warum Sie selbst keinen QR-Code ausstellen können, und die Vier-Augen-Kontrollen rund um die Kontowiederherstellung.
---

# Zwei-Faktor-Support

Ein Kunde hat das Telefon mit seinem Authenticator verloren. Er kann sich nicht anmelden. Er ruft Sie an.

Diese Seite ist das Handbuch dafür — und beschreibt die Beschränkungen, die Sie vorher verstehen müssen.

!!! info "Das gilt nur im Entra-Modus"
    Alles hier betrifft Installationen, in denen `ENTRA_ENABLED=true` ist und Kunden sich über Microsoft Entra ID anmelden, wobei Conditional Access den zweiten Faktor erzwingt.

    Im lokalen Modus gibt es für Kunden gar keinen zweiten Faktor bei der Anmeldung, und es gibt nichts wiederherzustellen. Das TOTP-Step-up der Betreiber ist davon getrennt und unberührt.

    Die Support-Konsole verlangt `ENTRA_SUPPORT_ENABLED=true` und die Graph-Berechtigungen aus der [Entra-Einrichtung](../../platform/entra-setup.md).

---

## Zwei Beschränkungen, die zuerst zu verstehen sind

### Sie können keinen QR-Code für den Kunden erzeugen

!!! danger "Microsoft besitzt das Geheimnis und bietet keinen Weg, eines zu erzeugen"
    Microsoft Graph bietet keine Operation, um eine Authenticator- oder TOTP-Methode anzulegen. Die einschlägigen Endpunkte unterstützen nur Auflisten, Abrufen und Löschen, und das Feld für den geheimen Schlüssel liefert dokumentiert stets `null`.

    Das ist keine fehlende Funktion in Registerwerk. **Keine Software kann es**, weil Entra das Geheimnis nie preisgibt.

    Die Registrierung geschieht deshalb auf Microsofts eigener Security-Info-Seite. Ihre Aufgabe ist es, den Kunden in einen Zustand zu bringen, in dem er sich registrieren kann — nicht, ihn zu registrieren.

    Zeigt die Seite `/security` des Kunden einen QR-Code, kodiert dieser einen **Link auf Microsofts Registrierungsseite** — damit jemand am Rechner auf dem Telefon weitermachen kann, das die Zugangsdaten halten wird. Der eigentliche Registrierungs-QR ist Microsofts, auf Microsofts Seite.

### Eine Methode zu löschen beendet die Sitzungen nicht

!!! warning "Sitzungen überleben Änderungen an Zugangsdaten"
    Eine Authentifizierungsmethode zu entfernen — oder ein Passwort zurückzusetzen — macht bestehende Sitzungen **nicht** ungültig.

    Wer auf dem verlorenen Gerät eine laufende Sitzung hält, behält sie bis zum Ablauf. Ist das Telefon verloren statt kaputt, zählt das.

    **Widerrufen Sie Anmeldesitzungen immer als Teil der Wiederherstellung.** Das ist ein eigener, ausdrücklicher Schritt; ihn zu überspringen belässt genau die Gefährdung, wegen der man Sie angerufen hat.

---

## Das Handbuch

*Users → der Nutzer des Kunden → Manage 2FA.*

```mermaid
graph LR
    A["1 Verify<br/>who is calling"] --> B["2 Reset<br/>methods"] --> C["3 Revoke<br/>sessions"] --> D["4 Issue<br/>TAP"] --> E["5 Deliver<br/>out of band"] --> F["6 They<br/>re-enrol"]
```

### 1. Prüfen, mit wem Sie sprechen

Alles Folgende übergibt jemandem die vollständige Kontrolle über ein Konto. Ihr Verfahren zur Identitätsprüfung ist hier die eigentliche Sicherheitskontrolle; die Software kann Ihnen dabei nicht helfen.

!!! danger "Auf diesen Schritt zielen Angreifer"
    Ein überzeugender Anrufer, der ein verlorenes Telefon behauptet, ist der klassische Weg zur Kontoübernahme — und er verlangt keinerlei technischen Durchbruch.

    Wie Ihr Verfahren auch aussieht — Rückruf auf eine hinterlegte Nummer, Bestätigung durch einen bekannten Kontakt, eine Prüfung vor Ort —, folgen Sie ihm genau, und lassen Sie Dringlichkeit es nicht verkürzen. Dringlichkeit gehört zum Angriff.

### 2. Authentifizierungsmethoden zurücksetzen

Entfernt die registrierten Methoden, damit der Kunde neue anlegen kann.

**Erfordert Step-up und das [Vier-Augen-Prinzip](../../compliance/step-up-mfa.md).**

Die Konsole löscht die Standardmethode des Kunden **zuletzt** und meldet Fehlschläge je Methode, statt auf halber Strecke abzubrechen. Lässt sich eine Methode nicht entfernen, sehen Sie welche, statt bei einem halb erledigten Reset raten zu müssen.

### 3. Anmeldesitzungen widerrufen

Ausdrücklich, eigenständig und nicht optional. Siehe oben.

### 4. Einen Temporary Access Pass ausstellen

Ein TAP ist ein kurzlebiges Zugangsmittel, mit dem sich der Kunde einmal **ohne** zweiten Faktor anmelden kann, um einen neuen zu registrieren.

**Erfordert Step-up und das Vier-Augen-Prinzip.**

!!! danger "Ein TAP authentifiziert vollständig als der Kunde"
    Wer ihn hat, kann sich als der Kunde anmelden. Er ist ein Werkzeug zur Kontoübernahme — deshalb trägt er dieselbe Vier-Augen-Kontrolle wie eine Operation an Wallet-Schlüsseln.

    Registerwerk zeigt den Wert **genau einmal** und ist so gebaut, dass er danach nicht wiederbeschaffbar ist: Er wird in keine Tabelle geschrieben, nicht einmal auf Debug-Ebene protokolliert, aus der Audit-Nutzlast ausgeschlossen (die nur Pass-ID, Lebensdauer und Einmalkennzeichen festhält), mit `Cache-Control: no-store` ausgeliefert und in einem Komponentenfeld gehalten, das beim Schließen des Dialogs geleert wird — bewusst nie in einer Benachrichtigungsmeldung, denn die bleiben in der Seite bestehen.

    Verlieren Sie ihn vor der Übergabe, stellen Sie einen neuen aus. Nachschlagen können Sie ihn nicht.

**Einem externen Gast kann kein TAP ausgestellt werden.** Die Konsole erkennt das und deaktiviert die Schaltfläche mit einer Erklärung, statt Graph verwirrend scheitern zu lassen. Bei Gastkonten setzen Sie die Methoden zurück und lassen den Kunden sich über den normalen Einladungsweg neu registrieren.

### 5. Über einen anderen Kanal übergeben

Nicht über den Kanal, über den er Sie kontaktiert hat, wenn dieser kompromittiert sein könnte. Ein Anruf auf eine hinterlegte Nummer, wenn er Sie per E-Mail erreicht hat.

### 6. Der Kunde registriert sich neu

Er meldet sich mit dem TAP an und registriert auf Microsofts Security-Info-Seite eine neue Methode. Seine Seite `/security` führt ihn hindurch und fragt so lange nach, bis sie die neue Registrierung sieht.

---

## Föderierte Kunden

Ist die Organisation des Kunden **föderiert** — leben seine Nutzer in seinem eigenen Entra-Mandanten —, können Sie dessen Authentifizierungsmethoden überhaupt nicht verwalten. Es sind nicht die Nutzer Ihres Verzeichnisses.

Die Konsole zeigt die Mandanten-ID und **weist jede verändernde Aktion mit einem `409` ab**, statt einen Graph-Aufruf abzusetzen, der verwirrend scheitern würde.

Verweisen Sie den Kunden an seine eigene IT-Abteilung. Das ist die richtige Antwort, keine zu umgehende Beschränkung.

---

## Was der Kunde sieht

Seine Seite `/security` zeigt einen von vier Zuständen:

| Zustand | Bedeutung |
|---|---|
| **Not applicable** | Lokaler Modus. Zwei-Faktor wird hier nicht genutzt. |
| **Managed by your organisation** | Föderiert. Die eigene IT kümmert sich. |
| **Not registered** | Nummerierte Schritte, ein QR-Code zu Microsofts Seite und eine Schaltfläche „erneut prüfen". |
| **Registered** | Die Methoden und wann zuletzt geprüft wurde. |

Der Status ist ein **beratender Zwischenspeicher**, auf Anforderung aktualisiert und gedrosselt, damit Nachfragen nicht zu einer Überlastung von Graph werden. Er ist nie eine Grundlage für Autorisierung — Conditional Access ist der Durchsetzungspunkt, und ein veralteter Zwischenspeicher darf Zugang weder gewähren noch verweigern können.

---

## Warum Registerwerk Zwei-Faktor nicht selbst erzwingt

Eine berechtigte Frage, und die Antwort ist betrieblicher Natur.

Conditional Access blockiert nicht registrierte Nutzer **bei der Anmeldung** — sie erreichen die Anwendung gar nicht. Ein zweites Tor innerhalb der Anwendung hieße, dass eine Störung von Microsoft Graph zu einem Totalausfall des Portals für jeden Kunden wird, auch für die, die sich vor Jahren korrekt registriert haben.

Es gibt ein zuschaltbares Kennzeichen, das die Registrierung in der Anwendung verlangt. Es ist standardmäßig aus und **fällt bei einem Statusfehler offen aus** — genau aus diesem Grund.

---

## Wohin als Nächstes

- [Entra-ID-Einrichtung](../../platform/entra-setup.md) — das Konfigurationshandbuch
- [Step-up-MFA und Vier-Augen-Prinzip](../../compliance/step-up-mfa.md)
- [Identitätsübernahme](impersonation.md) — das andere zentrale Support-Werkzeug
