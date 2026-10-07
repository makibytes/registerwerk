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

## Kontrollen des Desks

- **Quoten sind versioniert.** Eine ersetzte Quote wird `SUPERSEDED` und kann nicht mehr angenommen werden. Die Annahme enthält den vom Server gelieferten `termsHash`; bei Abweichung (409) sind die aktuellen Konditionen zu prüfen. Die angenommenen Konditionen werden am Trade gespeichert und ändern sich nicht. Beträge und Zinsen werden auf die Nebeneinheit der Währung gerundet (ACT/360, für GBP u. a. ACT/365).
- **Jeder Leg hat einen Zahler** (erklärt „gesendet“ mit Referenz) **und einen Empfänger** (bestätigt oder bestreitet). Ein Margin Call braucht Bewertungsreferenz und Betrag, darf den daraus folgenden Fehlbetrag nicht übersteigen und gibt mindestens 24 Stunden Frist; **nur die Bestätigung des Kreditgebers beendet ihn**.
- **Default ist zweistufig:** Mitteilung (Default Notice) durch den Gläubiger, nach der Karenzzeit (Standard 24 Stunden) Erklärung – nur solange die Pflicht unerfüllt ist und die Gegenseite keine Erfüllung erklärt hat. Hat der Kreditnehmer gezahlt und der Kreditgeber gibt die Sicherheit nicht zurück, kann der *Kreditnehmer* den Default erklären.
- **Streitfall:** Jede Partei kann den Trade einfrieren; der Operator dokumentiert das Ergebnis mit Rechtsgrundlage und zweitem Freigeber, entscheidet aber nicht in der Sache.
- **Substitution** ist ein eigener Antrag; die Sicherheit wechselt erst, wenn beide Legs bestätigt sind, und nie bei geschlossenen, ausgefallenen oder strittigen Trades.
- **Zugang:** Opt-in des Unternehmens, professioneller Kunde bzw. geeignete Gegenpartei, KYC-/Screening-Prüfung. Der Kreditnehmer muss die Stücke im Register halten; bereits verpfändete oder im Handel gelistete Stücke sind gesperrt (interne Belastung, kein Sperrvermerk im Register). Die Laufzeit muss vor Fälligkeit bzw. Kündigung der Sicherheit enden; eine Einlösung ist bei offenem Repo blockiert. Kapitalmaßnahmen werden am Trade vermerkt; Ausgleichszahlungen regeln die Parteien.
- **Schutzmaßnahmen des Gläubigers:** Margin Call, Default Notice und Default-Feststellung sichern ein bestehendes Engagement und werden nur bei einem **harten Stopp** verweigert: Unternehmen nicht aktiv oder ungeklärter Sanktions-Screening-Treffer. Abgelaufene KYC oder ein Sperrvermerk auf irgendeiner Wallet entwaffnet den Kreditgeber nicht: die Maßnahme läuft, der Trade wird markiert (`PARTY_FLAGGED`) und der Registerbetreiber erhält eine Aufgabe. Neue Engagements, Substitutionen und deren Genehmigung behalten die volle Prüfung.
- **SFTR:** Beide Parteien brauchen eine LEI; jeder Trade hat eine UTI und liefert die vorhandenen SFTR-Felder (`/sftr-fields`). Registerwerk meldet nicht; die Parteien bleiben verantwortlich. Die Abwicklung ist bilateral und selbstbestätigt, nicht atomar.

!!! warning "Recht und Abwicklung bleiben außerhalb der Software verbindlich"
    Der Ablauf ersetzt weder Rahmenvertrag, Sicherheitenkatalog, Bewertungsstelle, Verwahrung, Streitprozess noch Netting-Gutachten. FoP ist eine bewusste operative Ausnahme; DvP bleibt vorzuziehen.
