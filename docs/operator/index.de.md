---
title: Für Betreiber
description: Ein Registerwerk-Register betreiben — die Aufgabe, die Architektur und die kundenbezogenen Abläufe, die den Großteil der Arbeit ausmachen.
---

# Für Betreiber

**Sie betreiben das Register.** Kunden emittieren Wertpapiere hinein, halten sie, handeln damit. Ihre Aufgabe ist zu entscheiden, wer hereinkommt, zu prüfen, was dort getan wird, die Plattform am Leben zu halten und zu helfen, wenn etwas schiefgeht.

Sie müssen Wertpapiermärkte nicht so tief verstehen wie ein Emittent. Sie müssen aber genug verstehen, um zu wissen, was Sie genehmigen und warum es zählt.

---

## Wo anfangen

<div class="grid cards" markdown>

-   :material-flag:{ .lg .middle } **[Was ein Betreiber tut](getting-started.md)**

    ---

    Die Aufgabe im Ganzen, das Portal und die Entscheidungen, die allein Ihnen zustehen.

-   :material-sitemap:{ .lg .middle } **[Wie Registerwerk gebaut ist](architecture.md)**

    ---

    Die Architektur, gerahmt danach, was kaputtgeht und was das dann bedeutet.

-   :material-account-group:{ .lg .middle } **[Kunden betreuen](customers/index.md)**

    ---

    Onboarding, KYC, Genehmigungen, Support, Identitätsübernahme, Offboarding. Der größte Teil der tatsächlichen Arbeit.

-   :material-server:{ .lg .middle } **[Installation](installation/prerequisites.md)**

    ---

    Zum Laufen bringen, von den Voraussetzungen bis zum Gateway.

</div>

---

## Die vier Dinge, die nur Sie können

Kunden können sehr viel. Diese vier gehören Ihnen, und zwar deshalb, weil jedes davon Schaden anrichten kann, der schwer oder gar nicht umkehrbar ist.

| | | |
|---|---|---|
| **Eine Organisation zulassen** | Niemand nutzt das Register, bevor Sie Rechtsträger und KYC genehmigt haben. | [Onboarding](customers/onboarding-flow.md) · [KYC](customers/kyc-process.md) |
| **Eine Emission genehmigen** | Kein Wertpapier existiert, bevor Sie Ja sagen. | [Emissionen genehmigen](customers/approving-issuances.md) |
| **Das Register berichtigen** | Zwangsübertragungen, Zwangsvernichtungen, Sperrvermerke — die Befugnisse nach §24 und §26 eWpG. | [Sperrvermerk](../compliance/sperrvermerk.md) |
| **Als Kunde handeln** | Identitätsübernahme, für den Support. Mächtig und vollständig zugerechnet. | [Identitätsübernahme](customers/impersonation.md) |

Beim zweiten und vierten Punkt wünschen sich neue Betreiber am häufigsten Anleitung; beide haben eine eigene Seite.

---

## Gewohnheiten, die sich früh lohnen

!!! tip "Lesen Sie das Audit-Log, wenn nichts los ist"
    Öffnen Sie es nur im Störfall, wissen Sie nicht, wie normal aussieht — und bemerken das nicht, was dort nicht hingehört.

!!! tip "Behandeln Sie das Vier-Augen-Prinzip als Funktion, nicht als Hindernis"
    Mehrere Vorgänge verlangen eine zweite Person: ein abgewickeltes Geschäft rückabwickeln, die Abwicklung einer Kapitalmaßnahme genehmigen, das MFA eines Kunden zurücksetzen, einen Temporary Access Pass ausstellen. Genau dort richtet eine einzelne irrtümliche oder böswillige Handlung den größten Schaden an.

    Installationen, in denen eine Person sämtliche Zugangsdaten hält, haben Vier-Augen-Kontrollen nur dem Namen nach. Erst die Personalausstattung macht sie echt.

!!! tip "Sagen Sie laut „Ich weiß es nicht""
    Man wird Sie fragen, ob ein Instrument compliant ist, ob ein Token Rechtswirkung hat, ob ein Kunde etwas rechtmäßig tun darf. Die Plattform bildet Regeln ab; sie entscheidet nicht über sie.

    Eine Frage an die Rechtsberatung zu verweisen ist weit häufiger die richtige Antwort, als Betreiber erwarten.

---

## Was Sie nicht sind

Erwähnenswert, weil Kunden das Gegenteil annehmen werden.

- **Sie sind nicht ihr Anwalt.** Sie genehmigen nach Ihren eigenen Kriterien, nicht nach ihren.
- **Sie sind nicht ihre Verwahrstelle.** Einen verlorenen Wallet-Schlüssel können Sie nicht wiederherstellen. Sie können eine Zwangsübertragung nach §24 ausführen — eine förmliche Berichtigung, kein Passwort-Reset.
- **Sie sind kein Bewertungsdienst.** Das Register hält Nennbeträge fest, keine Marktpreise.
- **Sie sind kein Garantiegeber.** Fällt ein Emittent aus, halten Sie das fest; Sie entschädigen die Inhaber nicht.

---

## Wenn etwas nicht stimmt

| | |
|---|---|
| Plattform verhält sich falsch | [Fehlersuche](troubleshooting.md) |
| Etwas ist ausgefallen | [Überwachung](maintenance/monitoring.md) · [Notfallhandbuch](dr/runbook.md) |
| Kunde ausgesperrt | [Zwei-Faktor-Support](customers/two-factor-support.md) |
| Kunde ratlos | [Identitätsübernahme](customers/impersonation.md) — genau sehen, was er sieht |
| Bekannte Mängel | [Prüfungsbefunde](../assurance-review-ledger.md) |
