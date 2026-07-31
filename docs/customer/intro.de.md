---
title: Was Registerwerk ist
description: Eine schlichte Erklärung, was die Plattform tut, was sie nicht tut und was Sie von ihr erwarten dürfen.
---

# Was Registerwerk ist

**Es ist ein Register.** Eine Aufzeichnung darüber, wem welche Wertpapiere gehören, geführt von einem Betreiber, wobei diese Wertpapiere zugleich als Token auf einer Blockchain abgebildet sind.

Das ist die ganze Idee. Alles Weitere folgt daraus.

---

## Das Problem, das es löst

Ein Wertpapier war einmal eine Urkunde. Es zu besitzen hieß, sie körperlich zu halten oder von einer Verwahrstelle halten zu lassen. Verkaufen hieß, sie zu übergeben.

Das funktionierte — und es war teuer: Tresore, Kuriere, Abstimmungen und Tage zwischen dem Abschluss eines Geschäfts und seinem Vollzug.

Elektronische Wertpapiere schaffen die Urkunde ab. Eigentum wird zur Registereintragung. In Deutschland macht das **eWpG**, seit Juni 2021 in Kraft, dies rechtlich möglich: Ein Wertpapier darf als Eintragung in einem Register bestehen statt als Urkunde.

Registerwerk setzt ein solches Register um und ergänzt eine zweite Ebene — dieselben Bestände als Token auf einer Blockchain, damit Übertragungen ausgeführt und unabhängig überprüft werden können, ohne dass eine Seite den Aufzeichnungen der anderen vertrauen müsste.

---

## Die beiden Aufzeichnungen

Das ist der eine strukturelle Gedanke, den zu verstehen sich lohnt, denn die meisten Überraschungen folgen daraus.

<div class="grid" markdown>

!!! abstract "Das Register"
    Eine Datenbank beim Betreiber. Nennt den Inhaber, den Betrag, Beschränkungen.

    **Die rechtlich maßgebliche Aufzeichnung.**

!!! abstract "Der Token"
    Ein Saldo in einem Smart Contract auf einer Blockchain. Öffentlich und unabhängig überprüfbar.

    **Die Aufzeichnung, die ausführt.**

</div>

Software beobachtet die Chain und hält das Register im Gleichlauf. Meist stimmen beide überein. Tun sie es nicht, ist das Register maßgeblich, und die Differenz klärt ein Mensch.

[:octicons-arrow-right-24: Verwahrung und Bestand](lifecycle/holding.md) geht darauf richtig ein.

---

## Was Sie tun können

| | |
|---|---|
| **Emittieren** | Ein Wertpapier anlegen, genehmigen lassen, ausbringen, Anleger zulassen und es sein Leben lang verwalten. |
| **Halten** | Wertpapiere besitzen, Bestände einsehen, Auszüge und Zahlungen erhalten. |
| **Handeln** | Vor Fälligkeit verkaufen oder von anderen Inhabern kaufen. |
| **Beleihen** | Bestände als Sicherheit verpfänden und ein Darlehen darauf aufnehmen, wo freigeschaltet. |
| **Veröffentlichen** | Anwendungen auf dem Berechtigungsrahmen des Ökosystems bauen und listen. |
| **Prüfen** | Registerweit lesen, ohne irgendetwas ändern zu können. |

[:octicons-arrow-right-24: Ihren Arbeitsbereich finden](workspaces/index.md)

---

## Wo Wertpapiere leben können

Das Register unterstützt mehrere Blockchains, je Emission gewählt. Jede hat Mainnet und Testnet.

| Familie | |
|---|---|
| **EVM** | Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism |
| **Vertrauliches EVM** | Fhenix, Inco — Beträge on-chain verschlüsselt |
| **Solana** | SPL und SPL-2022 |
| **Canton** | Ein privates Ledger, in dem Gegenparteien nur ihre eigenen Transaktionen sehen |
| **Weitere** | StarkNet, Stellar |

Welche es wird, zählt mehr, als es scheint: Es bestimmt, wer Ihre Transaktionen sehen kann, was eine Übertragung kostet, wie schnell sie abgewickelt wird und welche Token-Standards verfügbar sind. [Unterstützte Blockchains](../blockchains/index.md) vergleicht sie.

---

## Was es nicht tut

Hierüber Klarheit zu schaffen nützt mehr als eine Funktionsliste.

!!! warning "Registerwerk ist eine Referenzimplementierung"
    Funktionierende Software, die abbildet, wie sich ein elektronisches Wertpapierregister bauen lässt — damit der Entwurf geprüft, kritisiert und wiederverwendet werden kann.

    **Ihr Einsatz macht niemanden eWpG-konform oder konform mit sonstigem Recht.** Sie verleiht keine aufsichtsrechtliche Erlaubnis und gibt einem Token keine Rechtswirkung als Wertpapier. Das hängt von der Erlaubnis des Betreibers, vom Instrument, vom Angebot, von den Beteiligten und von der Installation ab.

    Ihnen begegnet womöglich älteres Material mit der Behauptung, hier emittierte Token seien „rechtlich gleichwertig zu klassischen Inhaberschuldverschreibungen und Aktien". **Diese Behauptung ist falsch** und wurde entfernt. Ob ein Instrument Rechtswirkung hat, bestimmen das Gesetz und die tatsächliche Art der Emission — niemals die Software, die es aufgezeichnet hat.

Genauer gesagt ist es nicht:

- **Ein Bewertungsdienst.** Das Register hält Nennbeträge fest, keine Marktpreise.
- **Ein Verwahrer Ihrer Schlüssel.** Sie halten den privaten Schlüssel Ihrer Wallet. Niemand kann ihn wiederherstellen.
- **Ein Handelsplatz.** Es bindet Handelsplätze an; es betreibt keinen Markt.
- **Ein Zahlungssystem.** Es unterstützt mehrere Zahlungswege; Geld bewegt sich dort, nicht hier.
- **Ein Garantiegeber.** Fällt ein Emittent aus, zeichnet die Plattform das auf. Sie entschädigt die Inhaber nicht.

---

## Der regulatorische Hintergrund, kurz

Das **eWpG** (*Gesetz über elektronische Wertpapiere*) erlaubt elektronische Wertpapiere ohne körperliche Urkunde und verlangt ihre Eintragung in ein Wertpapierregister. Die Vorschriften, die Ihnen am häufigsten begegnen:

| | |
|---|---|
| **§16** | Was das Register enthält und was eine Eintragung bedeutet. |
| **§17(2)** | Zusätzliche Inhalte bei Einzeleintragungen. |
| **§19(2)** | Registerauszüge, die Verbraucherinhabern zustehen. |
| **§24** | Die Berichtigung des Registers. |

Registerwerk bildet außerdem Luxemburg (CSSF), Frankreich (AMF) und Liechtenstein (TVTG) ab und berührt Geldwäscheprävention, die Travel Rule, MiFIR-Meldungen, DAC8/CARF, DORA, MiCAR und die DSGVO.

[:octicons-arrow-right-24: Rechtsrahmen](../legal/index.md)

!!! note "Jede Emission in Produktion wird zuerst vom Betreiber genehmigt"
    Der Betreiber prüft Emissionen an seinen eigenen Zulassungskriterien, bevor irgendetwas ausgebracht wird. Das ist eine betriebliche Kontrolle, kein Rechtsgutachten über Ihr Instrument.

---

## Wohin als Nächstes

<div class="grid cards" markdown>

-   **Das Geschäft verstehen**

    ---

    [Der Lebenszyklus eines Wertpapiers](lifecycle/index.md) — eine Anleihe, von der Idee bis zur Rückzahlung. Vierzig Minuten, keine Vorkenntnisse nötig.

-   **Einrichten**

    ---

    [Zugang erhalten](onboarding.md) → [Verifizierung](kyc.md) → [Wallet verbinden](investors/wallet-setup.md)

-   **Ihre Arbeit tun**

    ---

    [Anleger](workspaces/investor.md) · [Trader](workspaces/trader.md) · [Emittent](workspaces/issuer.md) · [Prüfer](workspaces/auditor.md)

-   **Etwas nachschlagen**

    ---

    [Glossar](glossary.md) · [Fragen und Antworten](faq.md)

</div>
