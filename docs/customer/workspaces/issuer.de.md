---
title: Emittent
description: Für Organisationen, die sich über die Ausgabe von Wertpapieren Kapital beschaffen — anlegen, ausbringen, verwalten und zurückzahlen.
---

# Emittent

**Sie leihen sich Geld oder verkaufen einen Anteil, und Sie tun es, indem Sie ein Wertpapier emittieren.** Sie beschreiben das Instrument, lassen es genehmigen, bringen es auf eine Blockchain, lassen Anleger zu, erzeugen die Einheiten — und verwalten die Sache dann jahrelang.

Von den drei Arbeitsbereichen trägt dieser die meiste Verantwortung. Was Sie hier anlegen, ist eine rechtliche Verpflichtung Ihrer Organisation.

---

## Was es hier gibt

| | |
|---|---|
| **Issuances** | Ihre Wertpapiere anlegen und verwalten. Das Hauptereignis. |
| **My dApps** | Anwendungen im Marketplace veröffentlichen — siehe [dApp-Herausgeber](dapp-publisher.md). |
| **Company Admin** | Ihre Nutzer und Ihre Organisation verwalten — siehe [Unternehmensadministrator](company-admin.md). |
| **Marketplace** | Anwendungen des Ökosystems. |

---

## Vor Ihrer ersten Emission

- **Ihre Organisation ist aufgenommen und ihr KYC ist genehmigt.** Ein Emittent mit abgelaufenem KYC kann nicht emittieren.
- **Sie kennen Ihre Jurisdiktion.** Das ist kein Etikett — es wählt das gesamte Regelwerk, das für die Lebensdauer des Instruments gilt. [Rechtsrahmen](../../legal/index.md).
- **Sie haben eine ISIN, falls Sie eine brauchen.** Registerwerk erzwingt Eindeutigkeit, vergibt aber keine; die bekommen Sie von Ihrer nationalen Nummerierungsstelle. Ohne geht es auch, aber die Anschlussfähigkeit nach außen wird schwieriger.
- **Sie haben entschieden, wer es halten darf.** Öffentliches Angebot? Nur professionelle Anleger? Eine einzige Jurisdiktion? Das bestimmt Ihren Token-Standard, und eine spätere Änderung bedeutet ein neues Instrument.

---

## Eine Emission anlegen

*Issuances → New Issuance.* Drei Schritte.

=== "1. Eckdaten"

    Name, ISIN, Jurisdiktion und die Ökonomie. Bei einer Anleihe: Nennbetrag, Währung, Emissions- und Fälligkeitstermin, Kuponsatz, Zinsberechnungsmethode, Zahlungsfrequenz, Kündbarkeit und Ausgabepreis als Bruchteil des Nennbetrags.

    **Der Ausgabepreis** zählt bei Nullkuponanleihen: Sie zahlen keine Zinsen und entschädigen den Anleger dadurch, dass sie unter pari verkauft werden — Kauf zu 800 €, Rückzahlung von 1.000 € bei Fälligkeit. Voreingestellt ist `1.0`.

    **Die Zinsberechnungsmethode** (ACT/360, ACT/365, 30/360 …) entscheidet, wie ein Teiljahr bei der Zinsberechnung zum Bruchteil wird. Sie ist unglamourös, und sie verändert das Geld.

=== "2. Chain & Standard"

    Welche Blockchain, und welcher Token-Standard.

    Bei einem regulierten Wertpapier lautet die Antwort meist [ERC-3643](../../token-standards/erc3643.md), weil dieser Standard *wer das halten darf* im Token selbst durchsetzt. [ERC-20](../../token-standards/erc20.md) ist einfacher und überall verstanden, kennt aber keine Zulässigkeit — wer eine Einheit erhält, besitzt sie.

    Andere Formen: ERC-1155 für viele Serien in einem Contract, ERC-3525 für semi-fungible Instrumente, ERC-4626/7540 für Fonds und Vaults, DAML auf Canton, wo Vertraulichkeit gegenüber Gegenparteien nötig ist, SPL-2022 auf Solana.

    [:octicons-arrow-right-24: Den Token-Standard wählen](../issuers/token-standards.md)

=== "3. Prüfen & einreichen"

    Prüfen und einreichen. Der Status geht `DRAFT` → `PENDING_APPROVAL`, und **die Bearbeitung endet**.

---

## Genehmigung

Der Betreiber prüft. Dann:

| | |
|---|---|
| **Genehmigt** | `APPROVED`. Konditionen fixiert. Sie dürfen ausbringen. |
| **Abgelehnt** | Zurück auf `DRAFT` mit protokollierter Begründung. Bearbeiten und erneut einreichen. |

Es gibt keinen Zustand `REJECTED` — eine abgelehnte Emission kehrt in den Entwurf zurück, wo sie bearbeitbar ist. Die Begründung steht im [Audit-Log](../../platform/audit-log.md).

---

## Ausbringen

*Issuance → Deploy.* Registerwerk sendet die Transaktion und hält die Contract-Adresse fest. Bei ERC-3643 wird die gesamte Suite ausgebracht — Token, Identity Registry, Trusted Issuers Registry, Compliance — miteinander verdrahtet.

Der Contract existiert nun und hält **null Einheiten**.

[:octicons-arrow-right-24: Auf eine Blockchain ausbringen](../issuers/deploying-to-chain.md)

---

## Anleger zulassen

*Issuance → Investors.* Jeder Anleger muss ein KYC-genehmigter Rechtsträger mit registrierter Wallet sein, aufgenommen in die Identity Registry.

!!! warning "Das ist eine Voraussetzung, keine Formalie"
    Unter ERC-3643 **kann eine nicht zugelassene Wallet keine Token empfangen** — die Übertragung scheitert on-chain. Minting vor der Zulassung erzeugt fehlgeschlagene Transaktionen und sonst nichts.

Wählen Sie je Inhaber die Eintragungsart:

- **Sammeleintragung** — eine Verwahrstelle hält für viele dahinterstehende Anleger.
- **Einzeleintragung** — der Anleger wird unmittelbar benannt, über eine pseudonyme Referenz. §17(2) eWpG verlangt zusätzliche Inhalte: Rechte Dritter, Verfügungsbeschränkungen, Hinweise zur Rechtsfähigkeit. §19(2) verpflichtet Sie, Verbraucherinhabern Registerauszüge zu übermitteln.

Ein Asset kann beide Formen zugleich führen.

[:octicons-arrow-right-24: Ihre Anleger verwalten](../issuers/managing-investors.md)

---

## Minting und Emittieren

*Issuance → Mint.* Einheiten entstehen und werden Inhabern zugeordnet. Dann `APPROVED` → `ISSUED`, und das Instrument ist im Umlauf.

!!! danger "Minting erschafft Wert aus dem Nichts"
    Ein Fehler hier ist keine falsche Zahl in einem Bericht — es sind echte Wertpapiere in den falschen Händen.

    Mint-Regeln können begrenzen, wie viel eine Adresse jemals erhalten darf, die Aktion verlangt [Step-up-Authentifizierung](../../compliance/step-up-mfa.md), und jeder Mint wird mit namentlich benanntem Handelnden protokolliert.

---

## Damit leben: fünf Jahre Verwaltung

Diesen Teil unterschätzen die meisten. Die Emission dauert eine Woche. Die Verwaltung den Rest des Jahrzehnts.

### Kapitalmaßnahmen

Zweimal jährlich Kupons und irgendwann die Rückzahlung. Registerwerk legt Kupon-Maßnahmen automatisch aus dem Zahlungsplan an und führt sie durch ihre Termine.

Ihre Aufgabe ist es, die Abwicklung zu genehmigen — und das verlangt das [Vier-Augen-Prinzip](../../compliance/step-up-mfa.md), denn die falsche Inhaberliste zu bezahlen ist der klassische katastrophale Fehler der Wertpapierverwaltung und lässt sich sehr schwer rückgängig machen.

Die drei Termine, die entscheiden, wer Geld bekommt: **Nachweisstichtag** (wer in diesem Moment hält, ist berechtigt), **Ex-Tag** (ab hier wird ohne die Zahlung gehandelt), **Zahltag** (das Geld fließt).

[:octicons-arrow-right-24: Kapitalmaßnahmen im Detail](../lifecycle/redemption.md)

### Die Inhaberliste im Blick behalten

Ihre Anleger handeln untereinander, und Sie können sie nicht daran hindern. Was Sie bekommen, ist Sichtbarkeit: Das Register aktualisiert sich, und Ihre Inhaberliste ändert sich.

Achten Sie auf **Inhaberobergrenzen**, falls Ihr Instrument welche hat — eine Compliance-Regel, die Übertragungen abweist, sobald eine Grenze erreicht ist. Anleger erleben das als unerklärtes Scheitern; die eigenen Grenzen zu kennen erspart Support-Aufkommen.

### Registerauszüge

Für Verbraucherinhaber mit Einzeleintragung werden §19(2)-Auszüge erzeugt und als Registerunterlagen aufbewahrt. Noch Jahre später reproduzierbar — denn ein Auszug, den Sie nicht erneut erzeugen können, ist kein Nachweis.

### Aussetzung

`ISSUED` → `SUSPENDED` friert den Handel ein, ohne das Instrument zu beenden — für eine Kapitalmaßnahme, einen Streitfall oder einen vermuteten Fehler. Umkehrbar.

### Rückzahlung

Bei Fälligkeit: Bestandsaufnahme, Ansprüche, Genehmigung im Vier-Augen-Prinzip, Zahlung, Token vernichtet, `REDEEMED`. Endgültig — daraus führt kein Weg zurück.

Inhaberzeilen werden **nur logisch gelöscht, nie entfernt**: Ein verschwundener §16-Registereintrag kann weder Aufbewahrungs- noch Nachweispflichten erfüllen.

---

## Was Sie überraschen wird

!!! info "Sie können ein rechtmäßiges Geschäft zwischen zulässigen Inhabern nicht unterbinden"
    Einmal emittiert, handelt das Instrument nach seinen eigenen Compliance-Regeln. Diese Regeln setzen Sie bei der Emission; über einzelne Geschäfte entscheiden Sie nicht.

!!! info "Sie können eine genehmigte Emission nicht bearbeiten"
    Die Konditionen werden mit der Genehmigung fixiert. Eine Änderung bedeutet eine neue Emission oder eine Korrektur durch den Betreiber mit Prüfpfad.

!!! info "Das KYC Ihrer Anleger ist nicht Ihre Beurteilung"
    Der Betreiber verifiziert Rechtsträger. Sie können keinen Anleger zulassen, den der Betreiber nicht genehmigt hat, wie gut Sie ihn auch kennen.

!!! info "Eine Zwangsübertragung braucht den Betreiber"
    Korrekturen nach §24 eWpG — ein verlorener Schlüssel, ein Gerichtsbeschluss, ein fehlerhafter Eintrag — sind Betreiberhandlungen im Vier-Augen-Prinzip, nichts, was Sie selbst ausführen.

---

## Wohin als Nächstes

- [Der Lebenszyklus eines Wertpapiers](../lifecycle/index.md) — der gesamte Bogen, von Anfang bis Ende
- [Den Token-Standard wählen](../issuers/token-standards.md)
- [Unternehmensadministrator](company-admin.md) — die Nutzer Ihrer Organisation verwalten
