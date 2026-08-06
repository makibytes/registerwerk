---
title: Microsoft Entra ID-Setup
description: App-Registrierungen, bedingter Zugriff, Graph-Berechtigungen und der Mandanten-Rauchtest für Produktions-2FA.
---

# Microsoft Entra ID-Setup { #microsoft-entra-id-setup }

Dies ist das Runbook, um das Kundenportal mit erzwungener Zwei-Faktor-Authentifizierung hinter
Microsoft Entra ID zu stellen. Nichts davon gilt für lokale oder Demo-Bereitstellungen – bei
`ENTRA_ENABLED=false` (dem Standardwert in `docker-compose.yml`) verwendet das Portal die
integrierte Anmeldung mit Benutzername/Passwort und keinen zweiten Faktor.

**Erfordert Microsoft Entra ID P1** für bedingten Zugriff und Authentifizierungskontexte.

---

## Was Registerwerk kann und nicht kann { #what-registerwerk-can-and-cannot-do }

Zwei Einschränkungen prägen das gesamte Design und sollten Sie verstehen, bevor Sie beginnen:

**Wir können Ihnen keinen QR-Code für Microsoft Authenticator ausstellen.** Microsoft Graph bietet
keine Möglichkeit, eine Authenticator- oder TOTP-Methode zu erstellen – `softwareOathMethods` und
`microsoftAuthenticatorMethods` unterstützen nur Auflisten, Abrufen und Löschen, und `secretKey`
gibt laut Dokumentation immer `null` zurück. Entra besitzt das Geheimnis. Die Registrierung
erfolgt daher auf Microsofts
[kombinierter Sicherheitsinformationsseite](https://learn.microsoft.com/en-us/entra/identity/authentication/concept-registration-mfa-sspr-combined),
und die `/security`-Seite von Registerwerk leitet Nutzer dorthin. Der von uns gerenderte QR-Code
kodiert den *Link* zu dieser Seite, sodass eine Person am Desktop auf dem Telefon fortfahren kann,
das die Anmeldeinformationen erhalten soll.

**Entra External ID (CIAM) kann nicht verwendet werden**, wenn Sie Microsoft Authenticator
einsetzen möchten: externe Mandanten unterstützen nur E-Mail-Einmalpasscode, SMS (ein
kostenpflichtiges Add-on) und Passkeys. Kunden müssen Mitglieder oder B2B-Gäste in einem
Workforce-Mandanten sein, oder aus ihrem eigenen Mandanten föderiert werden.

---

## 1. App-Registrierungen { #1-app-registrations }

Zwei Registrierungen. Halten Sie sie getrennt: Die API trägt ein Client-Geheimnis und darf niemals
ein öffentlicher Client sein.

### API – das Backend { #api-the-backend }

| Einstellung | Wert |
|---|---|
| Name | `Registerwerk API` |
| Anwendungs-ID-URI | `api://<api-client-id>` |
| Freigegebener Scope | `access_as_user` (Administrator- + Benutzereinwilligung) |
| Client-Geheimnis | Eines generieren → `ENTRA_CLIENT_SECRET` |

**Optionale Ansprüche im Zugriffstoken** – fügen Sie alle drei unter *Token-Konfiguration* hinzu:

| Anspruch | Warum es wichtig ist, wenn er fehlt |
|---|---|
| `acrs` | Entra fügt den Authentifizierungskontext nie opportunistisch hinzu, sodass jede Step-up-Aktion eine vollständige Browser-Weiterleitung kostet. Das sieht exakt wie ein Anwendungsfehler aus. |
| `xms_cc` | Die API kann nicht erkennen, dass der Client Claims-Challenges versteht. |
| `auth_time` | Die Aktualitätsprüfung für Step-up fällt stillschweigend auf `iat` zurück, eine wesentlich schwächere Garantie. Das Backend protokolliert beim ersten Auftreten eines Tokens ohne diesen Anspruch eine Warnung. |

### SPA – das Kunden-Frontend { #spa-the-customer-frontend }

| Einstellung | Wert |
|---|---|
| Name | `Registerwerk Customer Portal` |
| Plattform | Single-Page-Anwendung |
| Weiterleitungs-URI | `https://<customer-portal-host>/` |
| API-Berechtigung | `api://<api-client-id>/access_as_user` |

Kein Client-Geheimnis – es ist ein öffentlicher Client. Das SPA wirbt im Code mit
`clientCapabilities: ['CP1']`; hier ist nichts zu konfigurieren.

---

## 2. Bedingter Zugriff { #2-conditional-access }

### MFA-Anmeldung erzwingen { #require-mfa-to-sign-in }

Erstellen Sie eine Richtlinie, die auf die API-Anwendung abzielt und den Zugriff nur mit
**Multifaktor-Authentifizierung erforderlich** gewährt – oder besser mit einer
**Authentifizierungsstärke**. Die integrierten Stärken sind *Multifaktor-Authentifizierung*,
*Passwortlose MFA* und *Phishing-resistente MFA*; die beiden Zugriffskontrollen (Grant Controls)
lassen sich nicht in einer Richtlinie kombinieren.

> Die Authentifizierungsstärke gilt nur für externe Benutzer, die sich **mit Entra ID**
> authentifizieren. Für E-Mail-Einmalpasscode, SAML/WS-Fed oder über Google föderierte Gäste
> verwenden Sie stattdessen die einfache MFA-Zugriffskontrolle.

### Authentifizierungskontext für Step-up { #authentication-context-for-step-up }

1. **Entra ID → Bedingter Zugriff → Authentifizierungskontext** → erstellen Sie einen Kontext
   (c1–c99), z. B. `Registerwerk regulator-grade action`.
2. **Aktivieren Sie „In Apps veröffentlichen".** Ein unveröffentlichter Kontext ist für Ressourcen
   unsichtbar und kann niemals erfüllt werden – das Symptom ist eine Anmeldeumleitungsschleife ohne
   Einträge in den Protokollen. Registerwerk überprüft dies beim Start und verweigert den Start im
   Produktionsmodus, wenn der Kontext nicht veröffentlicht ist.
3. Erstellen Sie eine Richtlinie mit diesem Kontext als Zielressource, gewähren Sie den Zugriff nur
   mit der von Ihnen gewählten Authentifizierungsstärke, und legen Sie **Anmeldehäufigkeit: Jedes
   Mal** fest.
4. Legen Sie dessen ID als `ENTRA_STEPUP_AUTH_CONTEXT_ID` fest.

Die Anmeldehäufigkeit ist die eigentliche Aktualitätskontrolle für Step-up: Ein Zugriffstoken lebt
60–90 Minuten, und der `acrs`-Anspruch bleibt für dessen gesamte Lebensdauer bestehen – ohne diese
Einstellung bleibt ein Token also noch lange „hochgestuft", nachdem der Nutzer sich längst entfernt
hat.

### Registrierung von Sicherheitsinformationen erzwingen { #register-security-information }

Erzwingen Sie die Registrierung bei der ersten Anmeldung mit der Benutzeraktion **„Sicherheitsinformationen registrieren"**
(das ist eine Benutzeraktion, keine Cloud-App), oder mit der MFA-Registrierungsrichtlinie von ID
Protection.

---

## 3. Microsoft Graph – die Operator-Support-Konsole { #3-microsoft-graph-the-operator-support-console }

Nur erforderlich für die 2FA-Statusseite des Kunden und die Operator-Konsole für verlorene
Telefone. Setzen Sie `ENTRA_SUPPORT_ENABLED=true` und gewähren Sie der API-Registrierung:

| Berechtigung | Typ |
|---|---|
| `UserAuthenticationMethod.ReadWrite.All` | Anwendung |
| `User.RevokeSessions.All` | Anwendung |

Erteilen Sie die Administratoreinwilligung und weisen Sie dann dem Dienstprinzipal die
Verzeichnisrolle **Authentifizierungsadministrator** zu. Bewusst *nicht* Privilegierter
Authentifizierungsadministrator: Der Authentifizierungsadministrator kann auf Mitglieder
einwirken, aber nicht auf Administratoren – genau die Eindämmung, die Sie für einen
Berechtigungsnachweis wollen, der in der Konfiguration einer Anwendung liegt.

Aktivieren Sie außerdem **Temporary Access Pass** unter *Authentifizierungsmethoden → Richtlinien*
und beschränken Sie ihn auf die Kundengruppe – ein TAP kann für jeden Benutzer erstellt werden,
aber nur Benutzer im Richtlinienbereich können sich damit anmelden.

---

## 4. Föderierte Kunden { #4-federated-customers }

Für einen Kunden, der seinen eigenen Entra-Mandanten behält:

1. Setzen Sie das `identity_model` seiner juristischen Person auf `FEDERATED` und erfassen Sie
   dessen Aussteller-URL (die Mandanten-ID wird daraus abgeleitet).
2. Konfigurieren Sie die **Einstellungen für mandantenübergreifenden Zugriff** in Entra für
   eingehende B2B-Zusammenarbeit.
3. Entscheiden Sie, ob der MFA aus dessen Mandanten vertraut werden soll, und vermerken Sie dies in
   `idp_mfa_trusted`. Das ist betreiberseitig gesteuert: Ein Kunde, der für die eigene MFA bürgt,
   könnte andernfalls die Messlatte senken, die für die eigenen Nutzer gilt.

Registerwerk kann die Authentifizierungsmethoden eines föderierten Nutzers nicht verwalten – die
Support-Konsole zeigt dessen Mandanten-ID an und lehnt jede verändernde Aktion mit einem 409 ab,
statt einen Graph-Aufruf zu tätigen, der verwirrend fehlschlagen würde.

Beachten Sie, dass ein **Temporary Access Pass einem externen Gast überhaupt nicht ausgestellt
werden kann**. Die Konsole erkennt dies (Gast-`userType` plus `#EXT#` in der UPN) und deaktiviert
die Schaltfläche mit einer Erklärung.

---

## 5. Umgebung { #5-environment }

```bash
ENTRA_ENABLED=true
JWT_ISSUER_URI=https://login.microsoftonline.com/<tenant-id>/v2.0
JWT_AUDIENCE=api://<api-client-id>          # or the bare client id — must match the token's aud

ENTRA_TENANT_ID=<tenant-id>
ENTRA_CLIENT_ID=<api-client-id>
ENTRA_CLIENT_SECRET=<api-client-secret>
ENTRA_SPA_CLIENT_ID=<spa-client-id>
ENTRA_API_SCOPE=api://<api-client-id>/access_as_user

ENTRA_SUPPORT_ENABLED=true
ENTRA_STEPUP_AUTH_CONTEXT_ID=c1
```

`JWT_AUDIENCE` ist in der Produktion nicht optional. Entra signiert jedes Token für einen
Mandanten mit denselben Schlüsseln, sodass ohne Audience-Prüfung ein Token, das für *irgendeine
andere Anwendung in Ihrem Mandanten* ausgestellt wurde, hier als Registerwerk-Sitzung akzeptiert
würde. `ProductionReadinessCheck` verweigert ohne sie den Start.

Das Betreiberportal ist von alledem nicht betroffen: Es behält die integrierte HS256-Anmeldung und
das lokale TOTP-Step-up bei, weshalb `JWT_DEV_SECRET` auch in einer vollständig
Entra-aktivierten Bereitstellung weiterhin wichtig ist.

---

## 6. Mandanten-Rauchtest { #6-tenant-smoke-test }

Mehrere Verhaltensweisen lassen sich ohne einen echten Mandanten nicht überprüfen. Arbeiten Sie
diese Liste durch, bevor Sie die Bereitstellung für gut befinden.

- [ ] **`/actuator/health/entra` meldet UP**, mit einer Anzahl veröffentlichter
      Authentifizierungskontexte größer null. Das deckt Graph-Erreichbarkeit, Token-Erwerb und
      Kontextverfügbarkeit in einem einzigen Aufruf ab.
- [ ] **Als Test-Kunde anmelden.** Bedingter Zugriff sollte die MFA-Registrierung erzwingen, falls
      noch keine besteht.
- [ ] **Das Zugriffstoken dekodieren.** Bestätigen Sie, dass `aud` mit `JWT_AUDIENCE`
      übereinstimmt und dass `acrs`, `xms_cc` und `auth_time` vorhanden sind. Fehlt `acrs`,
      prüfen Sie die optionalen Ansprüche erneut – das ist die mit Abstand häufigste
      Fehlkonfiguration.
- [ ] **Einen Step-up-Endpunkt aufrufen.** Erwartet wird ein 401 mit
      `error="insufficient_claims"`, dann eine Weiterleitung, dann Erfolg. Wird stattdessen jeder
      Aufruf umgeleitet, wird `acrs` nicht opportunistisch ausgestellt.
- [ ] **`/security` öffnen.** Sollte registrierte Methoden und einen Zeitpunkt „zuletzt geprüft"
      anzeigen.
- [ ] **Den Lost-Phone-Ablauf end-to-end durchspielen** an einem Testkonto: Methoden zurücksetzen
      → Sitzungen widerrufen → einen TAP ausstellen → sich mit dem TAP anmelden → eine neue
      Methode registrieren. Bestätigen Sie, dass der TAP genau einmal in der Benutzeroberfläche
      erscheint und nirgends in `audit_event` auftaucht.
- [ ] **Den TAP-Ablauf gegen einen externen Gast versuchen.** Die Schaltfläche sollte mit einer
      Erklärung deaktiviert sein, nicht bei Graph fehlschlagen.
- [ ] **Bestätigen, dass `audit_event`-Zeilen existieren** für jede Operator-Aktion, mit der
      korrekten `actor_id` – genau dafür existiert der Principal-Normalisierungsfilter.

### Bekannte Unsicherheiten { #known-uncertainties }

Diese hängen von der Mandantenkonfiguration und von Microsoft-Verhalten ab, das nicht vollständig
dokumentiert ist:

- Ob Entra das Löschen der **Standard-Authentifizierungsmethode** eines Benutzers verweigert,
  solange andere bestehen bleiben. Der Adapter löscht die Standardmethode zuletzt und meldet
  Fehler pro Methode, statt es einfach anzunehmen.
- Das genaue TAP-Verhalten für ein internes, aber Gastkonto; die `#EXT#`-Heuristik unterscheidet
  externe Gäste und sollte empirisch bestätigt werden.
- Ob mandantenübergreifendes MFA-Vertrauen eine Authentifizierungskontext-Anforderung für
  föderierte Nutzer erfüllt. Microsoft dokumentiert, dass FIDO2, Windows Hello und
  zertifikatsbasierte Authentifizierung die Stärke nur im *Home*-Mandanten des Nutzers erfüllen.
- Graph-Drosselung bei anhaltendem Polling von `/two-factor/refresh`. Das Backend drosselt pro
  Nutzer, aber mandantenweite Grenzwerte gelten weiterhin.
