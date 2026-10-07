---
title: Step-Up MFA & 4-Augen
description: Erweiterte Authentifizierung und Doppelkontrolle (4 Augen) für regulierte Vorgänge mit hohem Risiko.
---

# Step-Up MFA & 4-Augen { #step-up-mfa-4-eyes }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Auf dieser Seite werden die beabsichtigten Steuerungszuordnungen beschrieben. Es ist kein Beweis
    dafür, dass der konfigurierte MFA- oder Doppelkontroll-Ablauf eine bestimmte gesetzliche,
    regulatorische, sicherheitsbezogene oder auf Aufgabentrennung bezogene Anforderung erfüllt.
    Rollen, geschützte Aktionen, Sicherheitsstufe, Wiederherstellung und Prüfnachweise erfordern
    eine einsatzspezifische Überprüfung.

Bestimmte Vorgänge in Registerwerk sind so folgenreich – oder durch Vorschriften so eindeutig auf eine doppelte Aufsicht angewiesen –, dass eine normale Anmeldesitzung nicht ausreicht. Bei der **Step-up-Authentifizierung** muss der Betreiber seine Identität im Moment der Ausführung des Vorgangs erneut nachweisen. Das **Vier-Augen-Prinzip** verlangt zusätzlich, dass ein zweiter, unabhängiger Genehmiger bestätigt, bevor die Aktion ausgeführt wird.

---

## Warum das erforderlich ist { #why-this-exists }

| Verordnung | Verpflichtung |
|---|---|
| GwG §6(2) | Interne Kontrollsysteme — Entscheidungen mit hohem Risiko erfordern eine dokumentierte doppelte Aufsicht |
| eWpG §16 | Blockierungsvorgänge (Sperrvermerk) müssen auf einen benannten, verifizierten Betreiber zurückzuführen sein |
| BaFin KAIT | Die IT-Sicherheit erfordert MFA für den privilegierten Zugriff auf kritische Systeme |
| DSGVO Art. 32 | Geeignete technische Maßnahmen zum Schutz personenbezogener Daten — MFA ist die Grundlinie |

---

## Geschützte Vorgänge { #protected-operations }

Jede `@RequiresStepUp`-Annotation im Backend ist mit Endpunkt, Grund, Höchstalter, Pflicht zum zweiten Genehmiger und Body-Bindung in der generierten [Step-up-Matrix](step-up-matrix.md) aufgeführt (nur Englisch). Diese Seite ist die vollständige Liste und wird aus dem Code erzeugt (eine CI-Prüfung schlägt fehl, wenn sie veraltet ist); die folgende Tabelle ist ein kurzer, kuratierter Auszug und **nicht vollständig**.

| Vorgang | Step-up | 4-Augen | Grund (`@RequiresStepUp`) |
|---|---|---|---|
| Zwangsübertragung, Zwangsvernichtung, Zwangsfreigabe (Betreiber- und Emittenten-Endpunkte) | ja | ja | `FORCED_TRANSFER_EWG24`, `FORCE_BURN_EWG26`, `FORCED_APPROVE_OVERRIDE`, `ISSUER_*`-Varianten |
| Supply-Cap setzen | ja | ja | `SUPPLY_CAP_CHANGE_MICAR46` |
| ERC-3525: Slot anlegen, Slot-Mint, erzwungene Wertübertragung | ja | ja | `ERC3525_SLOT_CREATE`, `ERC3525_SLOT_MINT`, `ERC3525_FORCED_VALUE_TRANSFER_EWG24` |
| KYC genehmigen / ablehnen (auch Genehmigung mit Override) | ja | ja | `KYC_APPROVE`, `KYC_REJECT` |
| Sperrvermerk eintragen / aufheben | ja | ja | `SPERRVERMERK_CREATE`, `SPERRVERMERK_LIFT` |
| Identitätsübernahme starten (schreibgeschützt) | ja | nein | `ADMIN_IMPERSONATION` |
| Identitätsübernahme starten, im Namen handeln (nur Demo-Modus) | ja | ja | `ADMIN_IMPERSONATION_ACT_ON_BEHALF` |
| Prüftreffer annehmen, PEP bestätigen (immer, unabhängig vom Score) | ja, 15 Min. | ja | `SCREENING_HIT_ACCEPT`, `SCREENING_PEP_CONFIRM` |
| Wallet-Schlüsselexport, Roh-Schlüsselimport, Keystore-Import | ja | ja | `WALLET_KEYSTORE_EXPORT`, `WALLET_IMPORT_RAW`, `WALLET_IMPORT_KEYSTORE` |
| Wallet-KEK-Rotation, eine Wallet | ja | nein | `WALLET_KEK_ROTATION` |
| Wallet-KEK-Rotation, alle Wallets | ja | ja | `WALLET_KEK_ROTATION_ALL` |
| Wiedereinsetzung eines Rechtsträgers nach Schließung | ja | ja | `ENTITY_REINSTATE` |
| Quittierung einer Audit-Ketten-Prüfung | ja | ja | `AUDIT_CHAIN_VERIFICATION_ACK` |
| TOTP-Reset für einen anderen Betreiber | ja | ja | `TOTP_RESET` |
| Entra: eine Authentifizierungsmethode löschen | ja | nein | `ENTRA_AUTH_METHOD_DELETE` |
| Entra: alle Authentifizierungsmethoden zurücksetzen | ja | ja | `ENTRA_MFA_RESET` |
| Entra: Anmeldesitzungen widerrufen | ja | nein | `ENTRA_REVOKE_SIGNIN_SESSIONS` |
| Entra: Temporary Access Pass ausstellen | ja | ja | `ENTRA_TEMPORARY_ACCESS_PASS` |

Ob der zweite Genehmiger verlangt wird, kann auch von der Anfrage abhängen: Änderungen an Betreibernutzern, Herabstufungen und Schließungen von DORA-Vorfällen sowie Herabstufungen der Kundenklassifizierung erzwingen ihn im Dienst (`DualControlGate`); diese Gründe stehen in der zweiten Tabelle der Matrix.

Das Starten einer Identitätsübernahme ist unter [Identitätsübernahme](../operator/customers/impersonation.md) beschrieben; die Sitzung wird bei `ENTRA_ENABLED=true` vollständig abgelehnt.

---

## Zwei Wege { #two-tracks }

Wie der zweite Faktor nachgewiesen wird, hängt davon ab, wer die Sitzungstoken ausstellt. Beide werden durch dieselbe `@RequiresStepUp`-Annotation und denselben Aspekt erzwungen; nur die Prüfung unterscheidet sich.

### Lokales TOTP — `ENTRA_ENABLED=false`, und im Betreiberportal immer { #local-totp-entraenabledfalse-and-the-operator-portal-always }

RFC 6238 TOTP (HMAC-SHA1, 30-Sekunden-Fenster, 6 Ziffern), verifiziert durch `StepUpTokenIssuer`. Registrieren Sie sich bei `POST /api/v1/auth/step-up/enroll`, bestätigen Sie bei `/enroll/confirm`, und tauschen Sie dann bei `POST /api/v1/auth/step-up` einen Code gegen ein kurzlebiges Token mit `acr=stepup` ein, das 10 Minuten gültig ist. Der Aufrufer sendet dieses Token anstelle seines Sitzungstokens bei der geschützten Anfrage. Die Ablehnung erfolgt mit **403**.

> **WebAuthn/FIDO2 ist nicht implementiert.** Das Feld `method` in der Step-up-Anfrage wird akzeptiert und ignoriert. Frühere Versionen dieses Dokuments beschrieben es als primären Faktor; es existierte nie im Code. Bei der Entra-Anmeldung ist phishingresistente MFA verfügbar — allerdings über Conditional Access, nicht über dieses Modul.

### Entra-Authentifizierungskontext — `ENTRA_ENABLED=true` { #entra-authentication-context-entraenabledtrue }

Das Zugriffstoken muss den erforderlichen Conditional-Access-Authentifizierungskontext in seinem `acrs`-Claim tragen. Registerwerk verifiziert selbst keinen Faktor; es stellt eine Anforderung und überlässt Conditional Access die Entscheidung, was diese erfüllt — wodurch ein Betreiber phishingresistente MFA für erzwungene Übertragungen verlangen kann, ohne den Code zu ändern.

Die Ablehnung erfolgt als **401-Claims-Challenge**, sodass sich die SPA nur für diese eine Aktion erneut authentifiziert, statt den Benutzer abzumelden:

```
WWW-Authenticate: Bearer realm="", authorization_uri="…",
                  error="insufficient_claims", claims="<base64>"
```

Die Kontext-ID ist Konfiguration, referenziert über `@RequiresStepUp(reason = …)`:

```yaml
registerwerk.auth.step-up.entra:
  auth-context-id: c1                 # ENTRA_STEPUP_AUTH_CONTEXT_ID
  reason-overrides:
    FORCE_BURN_EWG26: c2
    "Payment rail creation": c1       # quote reasons containing spaces
```

Sie wird beim Start gegen den Tenant validiert: Ein Kontext, der nicht existiert oder existiert, aber **nicht für Apps veröffentlicht** ist, lässt den Start im Produktionsmodus fehlschlagen. Ein unveröffentlichter Kontext kann niemals erfüllt werden und erzeugt eine Anmelde-Umleitungsschleife, ohne dass die Protokolle dafür eine Erklärung liefern.

#### Aktualität funktioniert hier anders { #freshness-works-differently-here }

Ein Entra-Zugriffstoken lebt 60–90 Minuten, und `acrs` bleibt für seine gesamte Lebensdauer bestehen, sodass die Anwendung von `maxAgeMinutes` auf `iat` bei nahezu jedem geschützten Aufruf eine vollständige Browser-Umleitung erzwingen würde. Stattdessen gilt:

- die **primäre** Aktualitätskontrolle ist die Conditional-Access-Richtlinie für den Authentifizierungskontext (setzen Sie *Anmeldehäufigkeit: Jedes Mal* für Aktionen auf Regulierungsebene);
- `maxAgeMinutes` wird als Rückfallprüfung gegen den `auth_time`-Claim geprüft.

`auth_time` ist ein optionaler Claim, der bei der API-App-Registrierung angefordert werden muss. Ohne ihn fällt die Prüfung auf `iat` zurück, was schwächer ist — das Backend protokolliert beim ersten Mal eine Warnung, wenn es ein Entra-Token ohne diesen Claim sieht.

---

## 4-Augen-Implementierung { #4-eyes-implementation }

Der zweite Genehmiger muss ein anderer Nutzer sein, der aktuell als `REGISTRY_ADMIN` **oder** `COMPLIANCE_OFFICER` aktiviert ist (`StepUpTokenValidator.ELIGIBLE_APPROVER_ROLES`; die Rollen des Genehmigers werden aus der Datenbank neu gelesen). Eine eigene Rolle `SECOND_APPROVER` gibt es nicht. Auslösende von KYC- und Prüftreffer-Entscheidungen können selbst `REGISTRY_ADMIN` oder `COMPLIANCE_OFFICER` sein, sodass für diese Vorgänge ein `COMPLIANCE_OFFICER`-Paar möglich ist; niemand kann den eigenen Antrag genehmigen.

**Das Vier-Augen-Prinzip ist in beiden Wegen identisch**: Ein Doppelkontroll-Token wird immer lokal nach TOTP-Verifizierung geprägt und immer gegen den lokalen HS256-Decoder validiert; es hängt also nicht davon ab, wie der primäre Faktor nachgewiesen wurde.

```mermaid
sequenceDiagram
    participant Initiator
    participant Approver
    participant Backend

    Approver->>Backend: POST /api/v1/auth/step-up { code, action, target[, targetBody] }
    Backend-->>Approver: approver token (acr=stepup, stepup_scope, stepup_target, jti; 5 min, single use)
    Approver->>Initiator: Hand over the approver token
    Initiator->>Backend: POST /api/v1/auth/step-up { code, action }
    Backend-->>Initiator: initiator step-up token
    Initiator->>Backend: Protected call — Authorization: initiator token,<br/>X-Dual-Control-Token: approver token
    Backend->>Backend: Validate both, then execute + audit with both identities
```

Statt ein Token von Hand zu übergeben, kann der Auslösende die Freigabe-Warteschlange der Anwendung nutzen (nächster Abschnitt). Beide Wege enden in derselben Prüfung am geschützten Endpunkt.

Von `StepUpEnforcementAspect` und `StepUpTokenValidator` erzwungene Schlüsselinvarianten:

- Initiator und Genehmiger **müssen unterschiedliche Benutzer sein** (`sub`-Vergleich)
- Das Token des Genehmigers muss `stepup_scope` **exakt gleich** dem `reason` der Annotation tragen — andernfalls wäre eine Genehmigung ein allgemeiner Berechtigungsnachweis, der für jede Vier-Augen-Aktion in ihrem Zeitfenster gültig wäre
- Der Genehmiger muss in der **Datenbank** weiterhin als aktivierter `REGISTRY_ADMIN` **oder** `COMPLIANCE_OFFICER` geführt werden, nicht nur laut Token-Claims, die den Status nur zum Zeitpunkt der Ausstellung widerspiegeln
- Die Genehmigung ist **an die Anfrage gebunden, für die sie erteilt wurde** (K3). Der Genehmiger prägt sie mit `action` *und* `target` (`"METHOD /pfad?query"` des genauen Aufrufs; zusätzlich `targetBody`, der JSON-Body der Anfrage, den jeder Grund bindet). Das Token trägt `stepup_target`, den base64url-SHA-256 der kanonischen Anfrage (`v1`, Methode in Großbuchstaben, Pfad ohne abschließenden Schrägstrich, sortierte Query und der Hash des kanonischen JSON-Bodys: sortierte Schlüssel, keine Leerzeichen, exakte Dezimalzahlen in einfacher Schreibweise). Das Backend leitet denselben Digest aus der laufenden Anfrage ab; weicht er ab, wird der Aufruf mit **403** abgelehnt. Token ohne Ziel werden nicht mehr akzeptiert
- **Genehmigungs-Token gelten nur im Header.** Eine Genehmigung trägt `use=dual_control` und die Audience `registerwerk-dual-control`. Sie wird in `X-Dual-Control-Token` akzeptiert und sonst nirgends: Als `Authorization: Bearer` (oder Sitzungs-Cookie) wird sie auf jedem Endpunkt, auch auf `@RequiresStepUp`-Endpunkten, mit **403** abgelehnt; eine Genehmigung, die jemand in der Hand hält, lässt sich also nie als fremde Sitzung wiederverwenden. Gewöhnliche Step-up-Token (ohne Scope, ohne Markierung) bleiben der eigene Nachweis des Aufrufers.
- **Der Body ist immer gebunden.** Jeder Grund bindet den kanonischen Request-Body (`targetBody`, entfällt, wenn die Anfrage keinen hat). Ausnahmen stehen in `registerwerk.auth.step-up.dual-control.body-opt-out-reasons`: Nutzdaten, die kein JSON sind (Term-Sheet-Upload, Keystore-Import, CASP-CSV-Import), und Nutzdaten, die geheimes Schlüsselmaterial oder ein Keystore-Passwort sind (Rohschlüssel-Import, Keystore-Export); Methode, Pfad und Query bleiben auch dort gebunden. Zahlen sind exakte Dezimalwerte, nie `double`; ein Body mit wiederholtem JSON-Schlüssel oder eine Anfrage mit wiederholtem Query-Parameter lässt sich nicht binden und wird abgelehnt. Das Genehmigungs-Token bleibt einmalig verwendbar, auch wenn `bind-target-reasons` eingeschränkt wird.
- **Bootstrap ist eine Einbahntür.** Die Ausnahme „ein Step-up genügt" gilt nur, bis zwei aktivierte, TOTP-registrierte `REGISTRY_ADMIN`s gleichzeitig existiert haben. Die Datenbank hält diesen Moment fest (`dual_control_bootstrap`, per Trigger gesetzt, nie zurückgesetzt); danach kehrt die Ausnahme nie wieder, auch wenn ein Administrator später deaktiviert wird oder seinen Authenticator verliert. Das Deaktivieren oder Löschen eines Betreiber-Personalkontos (ohne Unternehmensbezug) oder eines Kontos mit einer geschützten Rolle (`REGISTRY_ADMIN`, `COMPLIANCE_OFFICER`, `SUPPORT_AGENT`, `AUDIT`) braucht den zweiten Genehmiger (`OPERATOR_USER_DISABLE`, `OPERATOR_USER_DELETE`).
- Die Genehmigung ist **einmalig verwendbar**: Ihre `jti` wird zusammen mit dem Audit-Ereignis in einer Transaktion in `dual_control_token_use` geschrieben (eine zweite Verwendung, auf jedem Replikat, ist ein **403**). Scheitert eine Aktion, nachdem die Genehmigung verbraucht wurde, ist eine neue Genehmigung nötig
- Die Genehmigung wird nur in einem **kurzen Zeitfenster** nach der Prägung akzeptiert (`registerwerk.auth.step-up.dual-control.window-seconds`, Standard 300 s); das eigene Step-up-Token des Initiators behält seine 10 Minuten

---

## Freigabe-Warteschlange der Anwendung { #in-app-approval-queue }

Beide Portale stellen und entscheiden Freigaben über `/api/v1/approvals`, statt Token weiterzureichen:

1. Der Auslösende stellt die genaue Anfrage (Aktion = der `@RequiresStepUp`-Grund des Endpunkts, Methode, Pfad, Query und JSON-Body). Die Anfrage wird nur angenommen, wenn diese Aktion der Grund dieser Route ist; der Body wird kanonisch gespeichert, sodass der Genehmiger sieht, was ausgeführt wird.
2. Ein berechtigter Genehmiger, der nicht der Auslösende ist (`REGISTRY_ADMIN` oder `COMPLIANCE_OFFICER`), sieht sie im Eingang **Approvals** und genehmigt mit einem frischen TOTP-Code oder lehnt ab. Selbstgenehmigung ist unmöglich, auch auf Datenbankebene.
3. Der Auslösende holt ein einmal verwendbares Genehmiger-Token ab, das an diesen Digest und an den Auslösenden gebunden ist (ein von einem Nutzer abgeholtes Token nützt in anderen Händen nichts), und sendet dann die eigentliche Anfrage mit seinem eigenen Step-up-Token und `X-Dual-Control-Token`.

Anfragen, die niemand entscheidet oder die nicht abgeholt werden, laufen nach 15 Minuten ab (`registerwerk.auth.step-up.approval-queue.ttl`). Die Warteschlange lehnt Identitätsübernahme-Sitzungen ab. Audit-Ereignisse: `APPROVAL_REQUEST_CREATED`, `_APPROVED`, `_REJECTED`, `_CANCELLED`, `_CLAIMED`, `_EXPIRED` (der Body steht nie im Ereignis, wohl aber sein Digest).

---

## TOTP-Registrierung, Speicherung und Zurücksetzen { #totp-enrolment-storage-reset }

- **Kein Vertrauen beim ersten Mal.** Zum Start einer Registrierung (`POST /api/v1/auth/step-up/enroll`) ist das aktuelle Passwort des Kontos im Body erforderlich (`{ "currentPassword": "…" }`); eine gestohlene oder unbeaufsichtigte Sitzung kann daher keinen Authenticator des Angreifers binden. Falsche Passwörter zählen zur selben Sperre wie falsche Codes. Konten, deren zweiter Faktor von einem externen Identity-Provider verwaltet wird, können keinen lokalen Authenticator registrieren. Die Bestätigung (`/enroll/confirm`) verbraucht den Zeitschritt des Codes, sodass er nicht als Step-up-Code wiederverwendet werden kann.
- **Verschlüsselt gespeichert.** Das TOTP-Geheimnis wird per Envelope-Verschlüsselung (AES-256-GCM, pro Wert ein neuer Datenschlüssel, vom Plattform-KEK umhüllt, die Benutzer-ID als zusätzliche authentifizierte Daten) in `app_user.totp_secret` abgelegt; `totp_secret_kid` hält den KEK-Provider fest. Von früheren Versionen im Klartext gespeicherte Geheimnisse werden durch einen Startjob verschlüsselt; die Prüfung lehnt ein noch im Klartext vorliegendes Geheimnis ab (ein Administrator setzt die Registrierung zurück).
- **Zustand über Replikate geteilt.** Replay-Schutz (RFC 6238 §5.2: ein Code auf oder vor dem zuletzt akzeptierten Zeitschritt wird abgelehnt) und Brute-Force-Sperre (5 falsche oder wiederverwendete Codes sperren Step-up für 15 Minuten) liegen in der Tabelle `totp_state` und werden atomar aktualisiert; ein auf einem Replikat akzeptierter Code wird auf allen anderen abgelehnt. Jeder Versuch wird reserviert, bevor der Code verglichen wird; parallele Rateversuche teilen sich daher ein Budget von fünf.
- **Selbstbedienungs-Entfernung.** `POST /api/v1/auth/step-up/disenroll { "code": "…" }` verlangt einen gültigen aktuellen Code, löscht die Registrierung und beendet die Sitzungen des Benutzers. Vor jeder Step-up-Aktion muss der Benutzer sich neu registrieren.
- **Zurücksetzen durch den Betreiber (Gerät verloren).** `POST /api/v1/admin/users/{id}/totp-reset` verlangt Step-up **und** einen zweiten Genehmiger (Grund `TOTP_RESET`). Es löscht die Registrierung, beendet die Sitzungen des Benutzers und schreibt das Audit-Ereignis `TOTP_RESET` mit beiden Identitäten; die eigene Registrierung lässt sich so nicht zurücksetzen. Der Benutzer registriert sich bei der nächsten Anmeldung neu.
- **Überwachung.** Das Gauge `registerwerk_stepup_unenrolled_operators` zählt aktivierte lokale `REGISTRY_ADMIN`-/`COMPLIANCE_OFFICER`-Konten, die älter als sieben Tage sind und keinen Authenticator haben. Es ist eine Warnmetrik, kein Startfehler; alarmieren Sie bei Werten über null.

Audit-Ereignisse des Lebenszyklus: `TOTP_ENROLMENT_STARTED`, `TOTP_ENROLLED`, `TOTP_DISENROLLED`, `TOTP_RESET`; `DUAL_CONTROL_APPROVED` enthält jetzt zusätzlich die Token-ID der Genehmigung und den Ziel-Digest, und `DUAL_CONTROL_BOOTSTRAP_USED` kennzeichnet die Ausnahme für einen einzelnen Akteur, solange weniger als zwei TOTP-registrierte Administratoren existieren.

---

## AOP-Durchsetzung { #aop-enforcement }

Der `StepUpEnforcementAspect` fängt jede mit `@RequiresStepUp` annotierte Methode ab und:

1. liest das authentifizierte JWT aus dem Sicherheitskontext
2. verzweigt je nach aktivem Weg:
   - **lokal** — erfordert `acr=stepup` und `iat` innerhalb von `maxAgeMinutes` (Standard 10); ein Fehler führt zu **403**
   - **Entra** — erfordert, dass `acrs` den konfigurierten Authentifizierungskontext enthält und `auth_time` innerhalb von `maxAgeMinutes` liegt; ein Fehler führt zu einer **401-Claims-Challenge**
3. validiert, falls `requireSecondApprover = true`, den Header `X-Dual-Control-Token` und legt die ID des Genehmigers als Anforderungsattribut `stepup.dualControlApproverId` offen, das Controller mit `@RequestAttribute` lesen — sie dürfen das Token nicht selbst erneut dekodieren
4. Die Claims-Challenge wird von `ClaimsChallengeAdvice` ausgelöst, nicht von Spring Security: Die Exception wird aus einem AOP-`@Around` geworfen und daher von `@RestControllerAdvice` behandelt, und der `BearerTokenAuthenticationEntryPoint` von Spring Security hat ohnehin keinen Codepfad, der einen `claims=`-Parameter serialisieren kann

---

## Audit-Ereignisse { #audit-events }

Step-up- und Vier-Augen-Aktivität wird über diese Ereignistypen festgehalten (die Liste entspricht dem, was im Code existiert; ein eigenes Ereignis „Step-up ausgestellt" gibt es nicht):

| Ereignistyp | Inhalt |
|---|---|
| `TOTP_ENROLMENT_STARTED`, `TOTP_ENROLLED`, `TOTP_DISENROLLED`, `TOTP_RESET` | Betroffener Nutzer; bei einem Reset zusätzlich Akteur und Genehmiger |
| `DUAL_CONTROL_APPROVED` | Auslösender, Genehmiger, Grund, Token-ID der Genehmigung und Ziel-Digest; wird geschrieben, bevor der geschützte Vorgang fortfährt |
| `DUAL_CONTROL_BOOTSTRAP_USED` | Die Einzelakteur-Ausnahme wurde genutzt, solange weniger als zwei TOTP-registrierte Administratoren existierten |
| `APPROVAL_REQUEST_CREATED / _APPROVED / _REJECTED / _CANCELLED / _CLAIMED / _EXPIRED` | Übergänge der Freigabe-Warteschlange: Akteur und Rolle, Genehmiger bei Genehmigung und Abholung, Digest und Token-ID (nie der Body) |

Der geprüfte Vorgang selbst (zum Beispiel `FORCED_TRANSFER` oder `SPERRVERMERK_CREATE`) trägt sein eigenes Ereignis. Diese Ereignisse sind Teil der manipulationssicher nachweisbaren [Audit-Kette](../platform/audit-log.md).
