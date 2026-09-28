---
title: Eine Emission genehmigen
description: Die Entscheidung, die ein Wertpapier ins Leben ruft – was zu prüfen ist, was eine Genehmigung bedeutet und was nicht, und was als Nächstes passiert.
---

# Eine Emission genehmigen

Ein Emittent hat ein Wertpapier beschrieben und eingereicht. Bis Sie genehmigen, handelt es sich um eine Beschreibung. Nachdem Sie genehmigt haben, kann es zu einer rechtlichen Verpflichtung dieses Emittenten werden, die von Anlegern gehalten wird.

Dies ist die folgenreichste Routineentscheidung, die ein Betreiber trifft.

---

## Was Sie tatsächlich entscheiden

!!! warning "Seien Sie genau, was Genehmigung bedeutet"
    Genehmigung bedeutet: **Diese Emission erfüllt die Zulassungskriterien des Registers.**

    Sie bedeutet nicht, dass das Instrument rechtmäßig ist, dass das Angebot den Prospektregeln entspricht, dass der Emittent es rechtmäßig ausgeben darf, oder dass der Token rechtliche Wirkung hat. Das hängt von der Zulassung des Emittenten, seiner Beratung und seinen Umständen ab.

    Behandelt ein Emittent Ihre Genehmigung als Compliance-Stellungnahme, korrigieren Sie das schriftlich. Dieses Missverständnis wird später teuer.

---

## Bevor Sie hinschauen

Bestätigen Sie zuerst die langweiligen Dinge – sie disqualifizieren schneller als alles in den Bedingungen:

- [ ] Die ausstellende Entität ist **aktiv**, und ihre **KYC ist genehmigt und nicht abgelaufen**.
- [ ] Die Entität ist als Emittent registriert.
- [ ] Es liegt keine offene [Sanktions](../../compliance/sanctions-screening.md)-Angelegenheit gegen sie vor.

---

## Was zu prüfen ist

### Identität

| | |
|---|---|
| **Name** | Sinnvoll, und nicht irreführend ähnlich zu einem bestehenden Instrument. |
| **ISIN** | Eindeutig – die Plattform erzwingt das. Registerwerk vergibt keine ISINs; der Emittent erhält eine von seiner nationalen Nummerierungsstelle. Eine Emission ohne ISIN ist zulässig, schränkt aber die Interoperabilität ein. |
| **Jurisdiktion** | Wählt das gesamte Regelwerk, das für die Lebensdauer des Instruments gilt. Eine spätere Änderung ist keine bloße Feldbearbeitung. |

### Bedingungen

Bei einer Anleihe: Nennbetrag, Währung, Ausgabe- und Fälligkeitstermine, Kuponsatz, Zinsberechnungsmethode, Zahlungshäufigkeit, Kündbarkeit, Ausgabepreis.

!!! tip "Drei Dinge, die einen zweiten Blick wert sind"
    **Fälligkeit vor dem Ausgabedatum.** Selten, und katastrophal, wenn es bis in die Produktion schafft – der Kuponplan wird daraus generiert.

    **Ausgabepreis bei einer Nullkuponanleihe.** Er ist standardmäßig `1.0` – pari. Eine Nullkuponanleihe zu pari zahlt keine Zinsen und zahlt den Nennbetrag zurück: ein Instrument, das nichts zurückgibt. Handelt es sich wirklich um eine Nullkuponanleihe, sollte der Ausgabepreis ein Abschlag sein. Diese Standardeinstellung hat schon für echte Verwirrung gesorgt.

    **Zinsberechnungsmethode.** Unspektakulär, und sie ändert, wie viel Geld bewegt wird. Bestätigen Sie, dass sie mit dem Term Sheet übereinstimmt, statt es anzunehmen.

### Konventionen des Kuponplans

Mit dem Speichern der Anleihebedingungen wird der Kuponplan erzeugt, aus dem die Jobs für Kapitalmaßnahmen arbeiten. Die Konventionen folgen der ICMA-Praxis; jede wird in den Bedingungen gesetzt, mit folgenden Standardwerten:

| Einstellung | Standard | Wirkung |
|---|---|---|
| Zinsberechnungsmethode | ACT/ACT (ICMA) | Reguläre Perioden laufen genau 1/Zahlungshäufigkeit auf; eine kurze erste Periode ihre tatsächlichen Tage im Verhältnis zur fiktiven regulären Periode. ACT/360, ACT/365 (fix), 30/360 und 30E/360 sind verfügbar. |
| Plan | Rückwärts ab Fälligkeit, kurze erste Periode | Reguläre Termine werden ab dem Fälligkeitstag zurückgerechnet; eine unregelmäßige Periode liegt am Anfang. Ist die Fälligkeit ein Monatsende, ist jeder Kupontermin ein Monatsende. |
| Geschäftstagekonvention | Modified Following | Fällt ein Zahltag auf keinen Geschäftstag, verschiebt er sich auf den nächsten Geschäftstag, außer das führt in den Folgemonat – dann auf den vorherigen. Die Zinsberechnung nutzt immer die unverschobenen Termine. |
| Feiertagskalender | TARGET2 | Wochenenden, 1. Januar, Karfreitag, Ostermontag, 1. Mai, 25. und 26. Dezember. |
| Stichtag (Record Date) | 1 Geschäftstag vor Zahlung | Wer am Ende dieses Tages im Register steht, erhält den Kupon. |
| Ankündigung | 5 Geschäftstage vor dem Stichtag | Wann der Kupon angekündigt wird. |
| Nachfristen | 30 Tage Zinsen, 7 Tage Kapital | Wie lange ein unbezahlter Betrag nach dem Zahltag nur überfällig ist. |

Der Kupon je Stück ist Nennbetrag × Kuponsatz × Zinstagequotient und bleibt ungerundet; gerundet wird erst der Anspruch jedes Inhabers. Ein variabler Kupon hat bis zur Zinsfestsetzung keinen Betrag und wird vorher nicht angekündigt. Erzeugt werden nur künftige Zahltage, spät erfasste Bedingungen erzeugen also keine rückdatierten Kupons. Der Plan erscheint im Tab **Corporate Actions** des Assets.

### Wie Kupons und die Rückzahlung ausgelöst werden

- **Ankündigung.** Der Kupon (und die Schlussrückzahlung) wird automatisch am *Ankündigungstag* ausgelöst, nicht am Zahltag – so bleibt Zeit für Bestätigung und Freigabe vor der Zahlung. Für die Rückzahlung gilt dasselbe: Zahltag = Fälligkeit, angepasst nach der Geschäftstagekonvention.
- **Stichtag.** Ansprüche werden zum **Ende des Stichtags** (Europe/Berlin) festgelegt, gemessen am damaligen Registerstand. Übertragungen danach ändern sie nicht. Bei Assets auf einer Chain wartet der Snapshot, bis das Register über den Stichtag hinaus abgeglichen ist, und wird verweigert („unmapped at record date“), wenn eine Wallet mit Bestand zu diesem Zeitpunkt keinen Registereintrag hat. Ein Stichtag bei Dividende, Split oder Kündigung muss beim Vorschlag und bei der Freigabe noch in der Zukunft liegen (frühestens der nächste Geschäftstag).
- **Rundung.** Der Anspruch jedes Inhabers wird auf die kleinste Einheit der Währung gerundet (Half-Even); die Bestätigung zeigt die gezahlte Summe und die Rundungsdifferenz.
- **Überfällig, verpasst, Ausfall.** Ein unbeglichener Betrag nach dem Zahltag ist zunächst **OVERDUE** (nur für Operatoren sichtbar; Kunden sehen „Zahlung ausstehend“). Erst nach der Nachfrist (30 Tage Zinsen, 7 Tage Kapital) wird ein Kupon **MISSED** und eine Anleihe **DEFAULTED**. Eine Abwicklung hebt jeden dieser Zustände auf: eine abgewickelte Rückzahlung setzt die Anleihe auf **REDEEMED**, eine abgewickelte Kündigung auf **CALLED**, ein abgewickelter Kupon ist **PAID**.
- **Vier-Augen-Prinzip.** Der Emittent bestätigt, ein Operator gibt frei. Ein Operator kann nie als Emittent bestätigen: Der Operator-Weg ist *Override attestation* (Step-up und Begründung, gesondert protokolliert) – auch beim Impersonieren. Ein Vorschlag muss von einer anderen Person freigegeben werden als der, die ihn gemacht hat.
- **Zurückgehaltene Ansprüche.** Ansprüche von Nominee-Pools (Look-through) werden nicht ausgezahlt und halten eine abgewickelte Maßnahme offen, markiert als „held entitlements outstanding“, bis sie geklärt sind.
- **Reihenfolge der Jobs.** 05:30 Kupons, 05:45 Rückzahlungen, 06:00 Tagesübergänge (Europe/Berlin) – eine morgens ausgelöste Maßnahme wird im selben Lauf verarbeitet.

### Chain und Standard

Passt der Token-Standard zu dem, was beansprucht wird?

!!! danger "Ein ERC-20 für ein eingeschränktes Wertpapier ist die Abweichung, auf die zu achten ist"
    Darf das Instrument nur von verifizierten oder professionellen Anlegern gehalten werden, kann [ERC-20](../../token-standards/erc20.md) das nicht erzwingen. Wer eine Einheit erhält, besitzt sie – jeder.

    Eingeschränkte Instrumente sollten [ERC-3643](../../token-standards/erc3643.md) verwenden, wo die Berechtigung im Token-Vertrag geprüft wird und nicht konforme Übertragungen on-chain scheitern (Revert).

    Das ist die wichtigste technische Prüfung in der Review, weil sie danach unsichtbar ist. Bei der Genehmigung geht nichts kaputt. Es geht kaputt, sobald zum ersten Mal eine Einheit eine Wallet erreicht, die sie nie hätte halten dürfen – und zu diesem Zeitpunkt sind bereits 50.000 Einheiten im Umlauf.

Bestätigen Sie außerdem, dass Mainnet gegenüber Testnet das ist, was der Emittent beabsichtigt hat. Die Genehmigung einer Mainnet-Emission, die jemand als Probelauf gedacht hat, ist ein unangenehmes Gespräch.

---

## Entscheiden

=== "Genehmigen"

    Der Status wird zu `APPROVED`. **Die Bedingungen werden gesperrt.** Der Emittent kann jetzt bereitstellen.

    Die Bedingungen können nur bis zur Emission vollständig (mit Step-up) gesetzt werden, und ISIN, Währung, Emissionsvolumen, Stückelung und Termine lassen sich nach der Genehmigung nicht mehr über das Bearbeitungsformular ändern. Spätere Änderungen sind **Änderungen der Bedingungen**: *Asset bearbeiten → Amend terms* verlangt die Rechtsgrundlage, Step-up und einen zweiten Operator, schreibt jeden Vorher-/Nachher-Wert ins Audit-Log und erzeugt die künftigen, noch nicht angekündigten Kupons neu. Gezahlte und bereits angekündigte Kupons werden nie überschrieben. Nennbetrag, Kupon und Fälligkeit einer bereitgestellten Canton-Anleihe können hier gar nicht geändert werden – sie sind im Ledger-Instrument festgelegt.

    Notieren Sie, warum Sie genehmigt haben. Das Audit-Log erfasst, dass Sie es getan haben, nicht, was Sie überzeugt hat.

=== "Ablehnen"

    Der Status kehrt zu **`DRAFT`** zurück – wieder bearbeitbar – mit Ihrer aufgezeichneten Begründung.

    Es gibt keinen `REJECTED`-Status. Eine abgelehnte Emission ist ein Entwurf. Das überrascht Betreiber, die einen Sackgassen-Status erwarten.

    **Schreiben Sie eine Begründung, auf die der Emittent reagieren kann.** „Nicht konform" führt zu einer erneuten Einreichung derselben Sache. „Das Instrument ist auf professionelle Anleger beschränkt, verwendet aber ERC-20, das dies nicht erzwingen kann – erneut als ERC-3643 einreichen" führt zu einer korrekten.

---

## Nach der Genehmigung

Damit sind Sie noch nicht fertig. Der Emittent wird:

1. **Bereitstellen** – den Vertrag deployen.
2. **Investoren zulassen** – jeder braucht eine genehmigte KYC-Entität und eine registrierte Wallet.
3. **Mint** – die Einheiten erzeugen.
4. **Emittieren** – womit es live geht.

Sie werden erneut involviert, wenn Investoren onboarding brauchen, und danach dauerhaft bei Kapitalmaßnahmen.

!!! info "Die Abwicklung einer Kapitalmaßnahme braucht einen zweiten Betreiber"
    Die Genehmigung einer Kapitalmaßnahme zur Abwicklung erfordert [vier Augen](../../compliance/step-up-mfa.md).

    Die falsche Inhaberliste auszuzahlen ist der klassische katastrophale Fehler in der Wertpapierverwaltung, und er lässt sich nur sehr schwer rückgängig machen. Stellen Sie sicher, dass in Ihrem Dienstplan tatsächlich zwei Personen verfügbar sind, wenn Kupontermine anfallen – eine Vier-Augen-Kontrolle, die an einem Freitagnachmittag niemand erfüllen kann, ist eine Kontrolle, die umgangen wird.


### Zeichnungsorders und Registereinträge

Investoren zeichnen über das Portal. Sie (oder der Emittent) bearbeiten die Warteschlange auf dem Tab **Subscription orders** des Vermögenswerts:

1. **Zuteilen** – voll oder gekürzt. Emissionsvolumen und Höchstbestand des Investors (einschließlich seiner übrigen offenen Zuteilungen) werden unter einer Sperre geprüft, parallele Zuteilungen können also nicht überschießen.
2. Warten, bis der Investor **annimmt**. Die Zuteilung trägt dann eine Zahlungsfrist (Standard 10 TARGET-Geschäftstage). Wird nicht rechtzeitig gezahlt, setzt ein geplanter Job sie auf **verfallen** und gibt die Kapazität frei.
3. **Zahlung bestätigen**, sobald das Geld auf dem Konto ist ([Step-up](../../compliance/step-up-mfa.md)). Bei Anleihen ist der Zahlbetrag zugeteilte Stücke × Nennwert × Ausgabekurs: Unterzahlung wird abgelehnt, Überzahlung als *Erstattung fällig* erfasst – die Erstattung selbst ist eine manuelle Zahlung. Bei Vermögenswerten ohne Anleihebedingungen erfassen Sie den erhaltenen Betrag.
4. **Abwickeln.** KYC, Sanktionsprüfung, Sperrvermerk, Registereinfrierung, Finalität, Zielmarkt und Bestandsgrenze werden erneut geprüft. Bei einem deployten ERC-20- oder ERC-3643-Vermögenswert werden die Stücke gemintet und der Holder-Sync schreibt sie ins Register, sobald die Übertragung indiziert ist; bei anderen deployten Standards bleibt die Order auf *bezahlt*, bis Sie die Stücke von Hand ausgeben. Ohne Deployment wird das Register direkt belastet.
5. **Freigeben** gibt eine Zuteilung mit Begründung zurück; bei einer bezahlten Order wird die Zahlung als Erstattung fällig markiert.

Registereinträge nehmen Sie vor, nicht der Emittent. Auf dem Tab **Holders** des Vermögenswerts verlangen *Add register entry* und *Change §17(2) attributes* jeweils eine Weisung (wer, und eine Referenz); ein leeres Feld bedeutet keine Änderung, das Entfernen eines Rechts braucht eine eigene Checkbox und einen zweiten Freigeber. Emittenten fragen über *Änderungsanfragen* an, die Sie (Vier-Augen) ausführen oder ablehnen. Bei einem deployten Vermögenswert ist ein manueller Eintrag nur eine Wallet-Zuordnung mit Nominal 0.

---

## Aussetzung und Rückzahlung

**Aussetzen** (`ISSUED` → `SUSPENDED`) friert den Handel ein, ohne das Instrument zu beenden – für eine Kapitalmaßnahme, einen Streitfall oder einen vermuteten Fehler. Reversibel.

**Einlösen** ist endgültig. Aus `REDEEMED` gibt es keinen Weg heraus.

Beide werden mit einem namentlich genannten Akteur protokolliert.

---

## Wo weiter

- [KYC-Prüfung](kyc-process.md) – das Tor davor
- [Design und Genehmigung](../../customer/lifecycle/design.md) – die Sicht des Emittenten auf denselben Schritt
- [Einen Token-Standard wählen](../../customer/issuers/token-standards.md)
