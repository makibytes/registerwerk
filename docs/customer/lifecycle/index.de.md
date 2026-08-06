---
title: Der Lebenszyklus eines Wertpapiers
description: Eine Anleihe, verfolgt von der ersten Idee bis zur Rückzahlung — jede Funktion von Registerwerk erklärt an der Stelle, an der sie tatsächlich gebraucht wird.
---

# Der Lebenszyklus eines Wertpapiers

Die meisten Dokumentationen erklären Funktionen. Dieser Abschnitt erzählt eine *Geschichte* und lässt die Funktionen dort auftauchen, wo sie hingehören.

Die Geschichte ist eine Anleihe. Wir begleiten sie von dem Moment, in dem jemand Geld leihen will, durch die Papiere, auf eine Blockchain, in die Hände von Anlegern, über einen Handelsplatz, in einen Beleihungsmarkt als Sicherheit — und schließlich aus der Welt, wenn die Schuld getilgt ist.

**Wer diesen Abschnitt ganz liest, versteht das Geschäft, um das es bei Registerwerk geht.** Etwa vierzig Minuten.

---

## Nordwind Energie

!!! example "Das durchgehende Beispiel"

    Die **Nordwind Energie GmbH** baut Windparks in Schleswig-Holstein. Für einen neuen Standort braucht sie **50 Millionen Euro** — und will nicht zur Bank.

    Also leiht sie sich das Geld direkt bei Anlegern, indem sie eine **Anleihe** begibt: das Versprechen, das Geld zu einem festen Termin zurückzuzahlen, mit Zinsen dazwischen.

    Die geplanten Konditionen:

    | | |
    |---|---|
    | Betrag | 50.000.000 € |
    | Stückelung | 1.000 € je Stück, also 50.000 Stück |
    | Zins | 4,5 % p. a., halbjährlich gezahlt |
    | Laufzeit | 5 Jahre |
    | Rückzahlung | voller Nennbetrag am Fälligkeitstag |

    Das ist das gesamte Finanzprodukt. Alles Weitere ist die Maschinerie, die dieses Versprechen wirksam, handelbar und durchsetzbar macht — und die einer Aufsicht zeigt, dass alles ordentlich zugegangen ist.

??? note "Für Leser ohne Finanzhintergrund: was eine Anleihe wirklich ist"

    Eine Anleihe ist ein Kredit, der in gleiche Stücke geschnitten wird, damit viele Geldgeber je eines nehmen können.

    Nordwind will 50 Millionen. Statt einen einzigen Geldgeber für die ganze Summe zu finden, teilt sie den Kredit in 50.000 Stücke zu 1.000 €. Ein Anleger kauft so viele Stücke, wie er möchte. Jedes Stück gibt Anspruch auf den anteiligen Zins und am Ende auf 1.000 €.

    Drei Wörter, die Ihnen ständig begegnen werden:

    - **Nennbetrag** (auch *Nennwert*, *pari*): der auf dem Stück stehende Betrag — hier 1.000 €. Das wird am Ende zurückgezahlt, unabhängig davon, was jemand zwischendurch dafür bezahlt hat.
    - **Kupon**: der Zinssatz, hier 4,5 % p. a. Der Name stammt aus der Zeit, als Anleihen aus Papier waren und man für jede Zahlung physisch einen Zinsschein von der Urkunde abtrennte.
    - **Fälligkeit**: der Tag, an dem der Kredit endet und der Nennbetrag zurückgezahlt wird.

    Der entscheidende und zunächst unintuitive Punkt: **Kurs und Nennbetrag einer Anleihe sind zwei verschiedene Zahlen, und der Kurs bewegt sich.** Steigen nach der Emission die Zinsen, wird eine Anleihe mit 4,5 % unattraktiver, und man bekommt sie nur noch mit Abschlag los — vielleicht für 960 € je 1.000-€-Stück. Der Nennbetrag hat sich nicht geändert. Geändert hat sich, was jemand für das Recht auf diese Zahlung zu geben bereit ist.

---

## Die sechs Stationen

<div class="grid cards" markdown>

-   **1. [Konzeption und Genehmigung](design.md)**

    ---

    Nordwind beschreibt die Anleihe im Portal, wählt, wie sie auf einer Blockchain existieren soll, und reicht sie ein. Der Betreiber prüft und genehmigt. Noch ist nichts on-chain.

-   **2. [Primäremission](primary-issuance.md)**

    ---

    Der Contract wird ausgebracht, Anleger werden zugelassen, und die 50.000 Stück entstehen in ihren Händen. Das Geld fließt in die eine, die Wertpapiere in die andere Richtung.

-   **3. [Verwahrung und Bestand](holding.md)**

    ---

    Anleger besitzen etwas. Wo liegt es tatsächlich, wer gilt als Inhaber, und was passiert, wenn Register und Blockchain auseinanderfallen?

-   **4. [Sekundärmarkt](secondary-market.md)**

    ---

    Ein Anleger will vor Fälligkeit aussteigen. Jemand anderes will einsteigen. Wie beide zueinander finden und wie der Tausch abgesichert wird.

-   **5. [Pensionsgeschäfte und Beleihung](repo-lending.md)**

    ---

    Ein Anleger braucht Geld, will die Anleihe aber behalten. Er verpfändet sie und leiht sich dagegen — der älteste Trick der Finanzmärkte, on-chain nachgebaut.

-   **6. [Kapitalmaßnahmen und Rückzahlung](redemption.md)**

    ---

    Fünf Jahre lang halbjährlich Zinsen. Dann endet der Kredit, das Geld geht zurück, und das Wertpapier wird vernichtet.

</div>

---

## Die zwei Irrtümer, die man sich sparen kann

Zwei Missverständnisse verursachen bei Einsteigern die meiste Verwirrung. Sie jetzt zu benennen erspart viel Zurückblättern.

**„Der Token *ist* das Wertpapier."** Ist er nicht. Der Token ist die Art, wie das Wertpapier auf einer Blockchain übertragen und nachgewiesen wird. Das Wertpapier ist der rechtliche Anspruch gegen Nordwind. Das Register ist die Aufzeichnung darüber, wer ihn hält. Gingen morgen sämtliche Blockchains der Welt aus, schuldete Nordwind den Anlegern immer noch 50 Millionen Euro — es wäre nur erheblich schwerer nachzuweisen, wem was zusteht. Der Token ist der Mechanismus, nicht die Sache.

**„Auf einer Blockchain kann jeder alles an jeden schicken."** Für eine Kryptowährung stimmt das. Hier ausdrücklich nicht. Ein reguliertes Wertpapier darf nur halten, wer es halten darf, und diese Beschränkung muss den Kontakt mit einer öffentlichen Blockchain überleben, auf der jeder jede Funktion aufrufen kann. Dieses Problem zu lösen macht den größten Teil dessen aus, was Wertpapier-Token schwieriger macht als gewöhnliche Token — und ist das Thema von [Konzeption und Genehmigung](design.md).

---

[Weiter mit Station 1: Konzeption und Genehmigung :octicons-arrow-right-24:](design.md){ .md-button .md-button--primary }
