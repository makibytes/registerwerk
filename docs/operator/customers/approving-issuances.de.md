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
