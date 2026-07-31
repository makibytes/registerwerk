---
title: Anleger
description: Für Menschen, die Wertpapiere halten — sehen, was Ihnen gehört, was es wert ist und was Ihnen zusteht.
---

# Anleger

**Sie besitzen Wertpapiere und wollen den Überblick behalten.** Sie handeln nicht aktiv, emittieren nichts und suchen keinen Hebel. Sie haben etwas gekauft und wollen wissen, wie es steht.

Das ist der kleinste Arbeitsbereich — mit Absicht.

---

## Bevor irgendetwas funktioniert

Drei Dinge müssen zutreffen, bevor ein Wertpapier Sie erreichen kann. Wenn etwas nicht funktioniert, liegt es fast immer an einem davon.

<div class="grid cards" markdown>

-   **1. Ihre Organisation ist aufgenommen**

    ---

    Ihr Unternehmen existiert im Register als Rechtsträger mit aktivem Status.

    [:octicons-arrow-right-24: Zugang erhalten](../onboarding.md)

-   **2. Ihr KYC ist genehmigt**

    ---

    Der Betreiber hat Ihre Organisation geprüft. Nicht bloß eingereicht — **genehmigt**, und nicht abgelaufen.

    [:octicons-arrow-right-24: Verifizierung](../kyc.md)

-   **3. Sie haben eine Wallet registriert**

    ---

    Eine Adresse, an die Wertpapiere gesendet werden können. Ohne sie gibt es kein Ziel für die Lieferung.

    [:octicons-arrow-right-24: Wallet verbinden](../investors/wallet-setup.md)

</div>

!!! warning "Die Reihenfolge zählt"
    Bei einem regulierten Instrument wie einem [ERC-3643](../../token-standards/erc3643.md)-Wertpapier muss Ihre Wallet in der Identity Registry dieses Instruments zugelassen sein, *bevor* Ihnen etwas übertragen werden kann. Eine Übertragung an eine nicht registrierte Wallet bleibt nicht hängen — sie scheitert on-chain.

    Wenn ein Emittent sagt, er habe Ihnen Wertpapiere geschickt, und nichts ist angekommen, prüfen Sie das zuerst.

---

## Ihr Alltag

### Dashboard

Was sich seit Ihrem letzten Blick geändert hat: Ihre Bestände, jüngste Vorgänge, alles, was Aufmerksamkeit braucht — ein ablaufendes KYC, ein offener Vorgang, ein gesperrter Bestand.

### Positions

Alles, was Sie halten, über alle Assets und Chains hinweg.

| Spalte | So zu lesen |
|---|---|
| **Asset** | Welches Wertpapier. |
| **Nominal amount** | Der Nennbetrag, den Sie halten. |
| **Wallet** | Welche Ihrer Adressen ihn hält. |
| **Entry type** | Sammel- oder Einzeleintragung — [was das heißt](../lifecycle/primary-issuance.md#was-ein-registereintrag-enthalt). |
| **Status** | Aktiv oder gesperrt. |

!!! note "Nennbetrag ist nicht Marktwert"
    100.000 € Nennbetrag heißt, dass Ihnen bei Fälligkeit 100.000 € zustehen. Es heißt nicht, dass die Position heute 100.000 € wert ist — eine Anleihe kann ihr ganzes Leben lang über oder unter dem Nennwert handeln.

    Registerwerk ist ein Register. Es hält fest, was Sie halten, nicht, was jemand dafür zahlen würde.

### Investments

Ein Bestand, in der Tiefe. Die Konditionen des Instruments, seine On-Chain-Adresse und Transaktionshistorie, Sie betreffende Kapitalmaßnahmen und Ihre Registerauszüge.

Hierhin gehen Sie, wenn Sie etwas *nachweisen* statt nur sehen müssen.

---

## Was Ihnen widerfahren wird

### Sie erhalten einen Registerauszug

Halten Sie im Wege einer **Einzeleintragung** und sind Sie Verbraucher, steht Ihnen nach §19(2) eWpG ein *Registerauszug* zu — nach der Ersteintragung, nach jeder Sie betreffenden Änderung und mindestens jährlich.

Das sind dauerhafte, reproduzierbare Aufzeichnungen, keine Benachrichtigungs-E-Mails. [Mehr zu Auszügen](../lifecycle/holding.md#ihr-registerauszug).

Institutionelle Inhaber in einer Sammeleintragung erhalten keine — deshalb sehen Sie womöglich gar keine.

### Sie erhalten Kupons

Bei einer Anleihe kommen Zinsen nach Plan. Ob *Sie* eine bestimmte Zahlung erhalten, hängt am **Nachweisstichtag**, nicht am Zahltag — wer am Stichtag hält, dem gehört die Zahlung, auch wenn er am Tag darauf verkauft.

[:octicons-arrow-right-24: Wie Kapitalmaßnahmen funktionieren](../lifecycle/redemption.md)

### Ihr KYC wird ablaufen

Die Verifizierung hat ein Ablaufdatum. Nähert es sich, warnt die Plattform; ist es überschritten, stoppen Übertragungen.

**Das nimmt Ihnen Ihre Wertpapiere nicht weg.** Sie bleiben Inhaber, Ihre Ansprüche auf Zahlungen bleiben bestehen. Sie können nur nichts bewegen, bis Ihre Organisation erneut geprüft ist.

### Ein Bestand kann gesperrt werden

Ein Gerichtsbeschluss, ein Sanktionstreffer, eine Verpfändung, eine ungeklärte Compliance-Frage. Sie sehen die Sperre und ihren Grund an der Position.

Es gehört Ihnen weiterhin. Sie können es nicht bewegen. [Mehr zu Sperren](../lifecycle/holding.md#wenn-ein-bestand-gesperrt-ist).

---

## Was Sie hier nicht können

Deutlich gesagt, damit Sie nicht danach suchen:

- **Sie können aus dem Anleger-Bereich heraus nicht verkaufen.** Verkaufen erfordert die Rolle `TRADER` und den [Arbeitsbereich Trader](trader.md).
- **Sie können Ihr Portfolio nicht bewerten.** Registerwerk hält keine Marktkurse für die Wertpapiere, die es führt.
- **Sie können nicht an eine beliebige Adresse übertragen.** Bei regulierten Instrumenten muss das Ziel ein zugelassener Inhaber sein.
- **Sie können eine verlorene Wallet nicht selbst wiederherstellen.** Siehe unten.

!!! danger "Wenn Sie Ihren Wallet-Schlüssel verlieren"
    Niemand kann ihn wiederherstellen. Weder der Betreiber noch der Emittent.

    Ihr *Anspruch* bleibt bestehen — das Register führt Sie weiterhin als Inhaber, und Kupons wie Rückzahlung stehen Ihnen weiterhin zu. Verloren ist die Möglichkeit, die Token zu bewegen.

    Die Wiederherstellung ist eine vom Betreiber ausgeführte **Zwangsübertragung** nach §24 eWpG: eine förmliche, belegte Korrektur, die Ihren Bestand auf eine Wallet überträgt, die Sie kontrollieren. Wenden Sie sich an den Betreiber. Sie verlangt Nachweise, sie verlangt das [Vier-Augen-Prinzip](../../compliance/step-up-mfa.md), und sie geht nicht schnell.

---

## Wohin als Nächstes

- [Der Lebenszyklus eines Wertpapiers](../lifecycle/index.md) — was um Sie herum tatsächlich geschieht
- [Verwahrung und Bestand](../lifecycle/holding.md) — wo Ihre Wertpapiere wirklich liegen
- [Fragen und Antworten](../faq.md)
