---
title: Was ein Operator tut
description: Die volle Rolle des Betreibers – die Entscheidungen, die bei Ihnen liegen, das Portal und ein 15-minütiger lokaler Start.
---

# Was ein Operator tut

Sie betreiben das Register. Kunden verlassen sich darauf, dass es korrekt, verfügbar und von jemandem betreut ist, der versteht, was er genehmigt.

Diese Seite ist der Job. [Wie Registerwerk aufgebaut ist](architecture.md) ist das System; [Kunden betreuen](customers/index.md) ist das Detail jedes Prozesses.

---

## Die Rolle, ehrlich gesagt

Der größte Teil der Arbeit besteht in der **Beurteilung von Menschen und Instrumenten**, nicht in Infrastruktur. Sie verbringen weit mehr Zeit damit, zu entscheiden, ob ein Rechtsträger der ist, der er zu sein behauptet, und ob eine Emission zugelassen werden soll, als damit, Container neu zu starten.

Die Befugnisse, die allein Ihnen zustehen, haben alle eine Eigenschaft gemeinsam: **Jede kann Schaden anrichten, der schwer oder gar nicht rückgängig zu machen ist.**

| | Warum sie bei Ihnen liegt |
|---|---|
| **Einen Rechtsträger aufnehmen** | Alles Nachgelagerte setzt voraus, dass diese Prüfung stattgefunden hat. |
| **Eine Emission genehmigen** | Schafft etwas, das zu einer von Anlegern gehaltenen Rechtsverpflichtung wird. |
| **Das Register korrigieren** | Zwangsübertragungen und Vernichtungen (Burning) nach §§24/26 eWpG verlagern fremdes Eigentum. |
| **Als Kunde handeln** | [Identitätsübernahme (Impersonation)](customers/impersonation.md) versetzt Sie in dessen Portal. |

---

## Ihr Tag

### Routine

- **Die Genehmigungswarteschlange.** Rechtsträger, die auf die KYC-Prüfung warten, Emissionen, die auf Genehmigung warten.
- **Das Audit-Log.** Lesen Sie es, wenn nichts falsch läuft, damit Sie wissen, wie normal aussieht.
- **Gesundheitszustand.** Indexer-Verzögerung, Chain-RPC-Gesundheit, Screening-Verfügbarkeit, [Audit-Partitionsspielraum](maintenance/monitoring.md).
- **Support.** Meist eines von drei Dingen — siehe unten.

### Nach Zeitplan

- **`REGISTRY_ADMIN`-Mitgliedschaft überprüfen.** Jeder Inhaber dieser Rolle kann Emissionen genehmigen, das Register korrigieren und bei jedem Kunden eine Identitätsübernahme (Impersonation) durchführen.
- **Bevorstehende KYC-Abläufe prüfen.** Einen Kunden einen Monat im Voraus zu warnen verhindert einen Ausfall, den er als Ihr Verschulden erlebt.
- **Die Audit-Chain verifizieren** und den Nachweis aufbewahren. Eine Integritätskontrolle, die niemand ausübt, ist von einer nicht funktionierenden nicht zu unterscheiden.
- **Wiederherstellungen testen.** Ein Backup, das nie wiederhergestellt wurde, ist eine Hypothese.

### Die Drei-Fragen-Triage

Bevor Sie irgendetwas Exotisches untersuchen, ist ein Kundenproblem meist eines von:

1. **KYC abgelaufen** — Übertragungen stoppen, alles andere sieht normal aus.
2. **Wallet nicht registriert oder nicht zugelassen** — Übertragungen scheitern on-chain statt zu warten.
3. **Rolle fehlt** — Kunde erhält ein `403` und nennt es „die Seite ist kaputt“.

Ein `401` bedeutet, der Token ist fehlerhaft. Ein `403` bedeutet, der Token ist in Ordnung, die Rolle jedoch nicht. Allein diese Unterscheidung löst einen großen Teil der Tickets.

---

## Das Operator-Portal

Unter `:44200`. Es umgeht das Gateway vollständig und nutzt die integrierte Benutzername/Passwort-Anmeldung mit lokalem TOTP für den Step-up — in jeder Konfiguration, einschließlich Bereitstellungen, in denen Kunden Microsoft Entra ID nutzen.

| Bereich | |
|---|---|
| **Kunden** | Juristische Personen, ihr Status, ihr KYC. |
| **Onboarding** | Rechtsträger anlegen, Einladungstoken erzeugen. |
| **Assets** | Jede Emission über alle Kunden hinweg. |
| **Benutzer** | Konten und Rollen, einschließlich [2FA-Support](customers/two-factor-support.md). |
| **Compliance** | Sanktionsprüfungsfälle, KYC-Prüfung. |
| **Audit** | Das manipulationssicher nachweisbare Protokoll. |
| **Organizations / Permissions** | On-Chain-Identität und Ökosystemberechtigungen. |
| **dApp-Review** | Marketplace-Einreichungen. |
| **Zahlungswege (Payment Rails)** | Kuratierung des Cash-Leg-Katalogs. |
| **Wallets / Network Nodes** | Verwahrte Wallets, Chain- und RPC-Gesundheit. |

!!! warning "Die Navigation des Portals ist keine Sicherheitsgrenze"
    Operator-Portal-Routen werden im Browser nicht rollenbasiert gefiltert. Der Zugriff wird durch das **Backend** je Anfrage anhand Ihres Tokens erzwungen.

    Ein Benutzer mit nur `AUDIT` sieht also Menüeinträge für Dinge, die er nicht tun darf, und erhält beim Öffnen eine Ablehnung. Nichts wird preisgegeben — aber schließen Sie aus einem sichtbaren Menüeintrag nicht, dass jemand ihn auch nutzen darf.

---

## Fünfzehn Minuten zu einem lokalen Register

```bash
git clone <your-registerwerk-remote> && cd registerwerk
git submodule update --init --recursive
cp .env.example.test .env
# CHAINCACHE_IMAGE in .env must name an independently supplied image.
docker compose up -d --build
```

Mit `CHAINCACHE_ENABLED=true` startet derselbe Befehl beide Chaincache-Workloads sowie deren
privates PostgreSQL. Registerwerk benötigt nur das in `CHAINCACHE_IMAGE` angegebene
Image und baut `../chaincache` nicht selbst. Mit `false` bleibt der Kern-Stack unabhängig davon
startfähig.

!!! danger "Lassen Sie `JWT_ISSUER_URI` für einen lokalen Start leer"
    Wird es gesetzt, schaltet das Kundenportal in den OIDC-Modus, der einen echten Entra-Mandanten, App-Registrierungen und Conditional Access erfordert. Eine halb konfigurierte Issuer-URI erzeugt Anmeldefehler, die wie Bugs aussehen.

    Der lokale Modus ist die Standardeinstellung und der richtige Startpunkt. Schalten Sie Entra bewusst ein, gemäß [Entra-ID-Einrichtung](../platform/entra-setup.md).

Danach:

| | |
|---|---|
| Operator-Portal | `http://localhost:44200` |
| Kundenportal | `http://localhost:44201` |
| Backend-Gesundheit | `curl http://localhost:48080/actuator/health` |
| Über das Gateway | `curl http://localhost:48000/api/v1/public/chains` |
| Dokumentation | `docker compose --profile docs up` → `http://localhost:48003` |

Melden Sie sich mit `DEFAULT_ADMIN_EMAIL` / `DEFAULT_ADMIN_PASSWORD` aus Ihrer `.env` an.

Kong läuft DB-los aus `gateway/kong.yml`, daher gibt es keine Gateway-Datenbank-Anmeldedaten und keine `kong`- oder `konga`-Datenbank. Seine Admin-API ist an Loopback gebunden — erreichen Sie sie mit `docker compose exec kong kong health`, legen Sie sie niemals offen.

Für alles über einen lokalen Testlauf hinaus lesen Sie [Voraussetzungen](installation/prerequisites.md) und danach sorgfältig [Umgebung](configuration/environment.md).

---

## Bevor Sie echte Kunden bedienen

- [ ] `DEFAULT_ADMIN_PASSWORD` und `JWT_DEV_SECRET` von ihren Standardwerten geändert.
- [ ] `JWT_AUDIENCE` gesetzt, falls Entra aktiviert ist. **Nicht optional** — ohne diese Einstellung wird ein an eine beliebige andere Anwendung in Ihrem Mandanten ausgegebenes Token hier als gültige Sitzung akzeptiert.
- [ ] Backups eingerichtet **und mindestens einmal wiederhergestellt** — einschließlich des Objektspeichers, der nicht in der Datenbank liegt.
- [ ] [Monitoring](maintenance/monitoring.md) eingerichtet, mit Alarmierung für den Audit-Partitionsspielraum.
- [ ] Mehr als ein `REGISTRY_ADMIN`, gehalten von **unterschiedlichen Personen**, damit [Vier-Augen](../compliance/step-up-mfa.md)-Kontrollen real sind.
- [ ] Ein getestetes [Disaster-Recovery](dr/runbook.md)-Verfahren.
- [ ] Ihre KYC- und Emissionsgenehmigungskriterien schriftlich festgehalten, damit Entscheidungen konsistent und erklärbar sind.

---

## Wo weiter

- [Wie Registerwerk aufgebaut ist](architecture.md)
- [Kunden betreuen](customers/index.md)
- [Fehlerbehebung](troubleshooting.md)
