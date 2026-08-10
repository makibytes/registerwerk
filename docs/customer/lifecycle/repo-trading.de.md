---
title: 5a. Repo-Handel
description: Bilaterale Wertpapierpensionsgeschäfte über gezielte oder offene RFQs verhandeln und abwickeln.
---

# Station 5a — Repo-Handel

Ein **Pensionsgeschäft (Repo)** besteht aus zwei gemeinsam vereinbarten Geschäften: Wertpapiere werden am Starttag gegen Geld verkauft und am Endtag zu einem festgelegten Betrag zurückgekauft. Die Differenz ist der Repo-Ertrag.

Der Repo Desk bildet diesen bilateralen Ablauf ab. Er ist bewusst von der [wertpapierbesicherten Kreditvergabe](repo-lending.md) getrennt, bei der Sicherheiten in einen On-Chain-Pool eingebracht werden.

| | Repo Desk | Wertpapierbesicherter Kredit |
|---|---|---|
| Gegenpartei | Benannte Unternehmen | Pool |
| Struktur | Verkauf und Rückkauf | Besicherter Kredit |
| Preis | Feste Quote und Rückkaufbetrag | Nutzungsabhängiger Zinssatz |
| Risikosteuerung | Haircut, Margin Call, Substitution | LTV, Orakel, Liquidation |

## Ablauf

1. Unter **Trader → Repo Desk → New RFQ** Kreditaufnahme/-vergabe, Sicherheit, Geldbetrag, Termine, indikativen Satz und Haircut eingeben.
2. Eine **gezielte RFQ** ist nur für ausgewählte Unternehmen sichtbar; eine **Broadcast-RFQ** für alle zugelassenen Trader.
3. Dealer sehen nie konkurrierende Quotes. Der Auftraggeber vergleicht Geldbetrag, Jahressatz, Haircut und Gültigkeit und nimmt eine Quote an.
4. Der Rückkaufbetrag wird nach ACT/360 festgelegt. `3,25` bedeutet 3,25 % p.a.
5. Bei Eröffnung und Schließung bestätigt jeweils der Empfänger den erhaltenen Geld- bzw. Wertpapier-Leg mit Referenz.
6. Margin Calls und Sicherheitensubstitutionen werden im gemeinsamen, unveränderlichen Lebenszyklus protokolliert.

!!! warning "Recht und Abwicklung bleiben außerhalb der Software verbindlich"
    Der Ablauf ersetzt weder Rahmenvertrag, Sicherheitenkatalog, Bewertungsstelle, Verwahrung, Streitprozess noch Netting-Gutachten. FoP ist eine bewusste operative Ausnahme; DvP bleibt vorzuziehen.

