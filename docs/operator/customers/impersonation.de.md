---
title: Identitätsübernahme — sehen, was der Kunde sieht
description: Im Kundenportal für den Support handeln: wie es funktioniert, wem es zugerechnet wird, wo die Grenzen liegen und wie man es steuert.
---

# Identitätsübernahme — sehen, was der Kunde sieht

Ein Kunde sagt, der Trading Desk lasse ihn keinen Bestand einstellen. Sie sehen sich sein Konto im Betreiberportal an, und alles wirkt in Ordnung. Sie bitten um einen Screenshot und bekommen ein Foto eines Bildschirms.

**Die Identitätsübernahme beendet diese Schleife.** Sie öffnet das Kundenportal mit ausgewählter Kundenorganisation, sodass Sie genau das sehen, was der Kunde sieht.

Sie ist zugleich das Mächtigste, was Sie ohne die Zustimmung einer zweiten Person tun können — und verdient es, bewusst eingesetzt zu werden.

---

## Was sie tatsächlich ist

Kein Passwort-Reset. Kein Anmelden als der Kunde. Sie erhalten nie dessen Zugangsdaten, und er wird nie abgemeldet.

Das Backend stellt ein **kurzlebiges Token** aus, das Folgendes trägt:

| Claim | Wert |
|---|---|
| `sub` | **Ihre** Nutzer-ID — nicht seine |
| `entityId` | Die Kundenorganisation, in der Sie handeln |
| `roles` | `COMPANY_ADMIN`, `ISSUER`, `INVESTOR`, `TRADER` |
| `imp` | `true` |
| `exp` | Kurz — die übliche Token-Lebensdauer |

!!! success "Das Subjekt bleiben Sie, und darin besteht der ganze Entwurf"
    Weil `sub` Ihre Nutzer-ID bleibt, wird **jede Handlung, die Sie vornehmen, Ihnen zugerechnet** — im [Audit-Log](../../platform/audit-log.md), nicht dem Kunden und nicht irgendeinem gemeinsamen „System"-Akteur.

    Ein Kunde kann niemals für etwas verantwortlich gemacht werden, das ein Betreiber während einer Identitätsübernahme getan hat, und ein Betreiber kann sich niemals hinter der Identität eines Kunden verstecken. Ohne diese Eigenschaft wäre die Identitätsübernahme in einem regulierten Umfeld unbrauchbar.

    Das Kennzeichen `imp: true` markiert die Sitzung als übernommen, sodass übernommene Handlungen im Log von gewöhnlichen unterscheidbar sind.

---

## Der Einsatz

1. Öffnen Sie im Betreiberportal den Datensatz des Kunden und wählen Sie **Impersonate**.
2. Sie werden an das Kundenportal unter `/admin/handoff` übergeben, das das Token aus dem URL-Fragment verarbeitet und Sie im Dashboard absetzt.
3. Eine **dauerhafte Leiste** sitzt oben auf jeder Seite: *Acting as **Nordwind Energie GmbH***, mit **Switch company** und **Exit impersonation**.
4. Arbeiten Sie. Alles, was Sie tun, wird als Ihre Handlung protokolliert.
5. Wählen Sie am Ende **Exit impersonation**.

Sie können auch einsteigen, ohne zuvor einen Kunden zu wählen — die Leiste zeigt dann *Admin mode — no company selected* und bietet **Select company** mit durchsuchbarer Liste.

!!! tip "Die Leiste ist aus gutem Grund immer sichtbar"
    Jeder `REGISTRY_ADMIN` sieht die Übernahmeleiste im Kundenportal jederzeit, ob eine Gesellschaft gewählt ist oder nicht. Sie erinnert beständig daran, dass Sie kein gewöhnlicher Nutzer dieser Oberfläche sind, und macht versehentliches Arbeiten im falschen Kontext deutlich schwerer.

---

## Wann man sie nutzt

**Gute Gründe**

- Ein vom Kunden gemeldetes Problem nachstellen, das Sie im Betreiberportal nicht sehen.
- Prüfen, wie die Sicht eines Kunden nach einer Konfigurationsänderung aussieht.
- Einen Kunden am Telefon durch einen Ablauf führen.
- Bestätigen, dass ein Berechtigungs- oder Zulässigkeitsproblem das ist, wofür Sie es halten.

**Schlechte Gründe**

!!! danger "Nutzen Sie die Übernahme nicht, um die Arbeit des Kunden für ihn zu erledigen"
    Eine Order aufzugeben, ein Verkaufsangebot anzulegen oder eine Emission im Namen eines Kunden einzureichen erzeugt eine Aufzeichnung, die zeigt, dass *ein Betreiber* eine geschäftliche Entscheidung innerhalb eines Kundenkontos getroffen hat.

    Selbst bei perfekter Zurechnung — vielleicht *gerade* bei perfekter Zurechnung — ist das eine Aufzeichnung, die sich gegenüber einer Aufsicht oder in einem Streitfall schwer erklären lässt. Der Wille des Kunden kommt darin nirgends vor.

    Hinsehen, diagnostizieren, erklären. Handeln lassen Sie den Kunden.

!!! danger "Nutzen Sie sie nicht, um Daten zu lesen, zu denen Sie sonst nicht berechtigt wären"
    Die Übernahme gewährt Ihnen die Sicht des Kunden auf seine eigenen Informationen. Ob *Sie* berechtigt sind, darin ohne Supportanlass zu stöbern, ist eine Frage des [Datenschutzes](../../compliance/data-protection.md), keine technische. Das Audit-Log zeigt, dass Sie hingesehen haben.

---

## Ihre Grenzen

### Im Entra-Modus funktioniert sie nicht

Ist `ENTRA_ENABLED=true`, melden sich Kunden über Microsoft Entra ID an, das Sitzungen unmittelbar an jeden Nutzer ausgibt. Registerwerk kann keine Sitzung im Namen eines Kunden ausstellen, und das Backend **weigert sich**, es zu versuchen.

Das Kundenportal zeigt eine ausdrückliche Meldung statt einer unerklärten Weiterleitung:

> **Impersonation is unavailable.** This portal signs in through Microsoft Entra ID, which issues the session directly to each user. Registerwerk cannot act on a customer's behalf in this mode. Ask the customer to sign in themselves, or use the operator portal's read-only views.

Das ist eine echte Beschränkung, keine Lücke, die man umgeht. In Entra-Installationen besteht Ihr Support-Werkzeugkasten aus den Ansichten des Betreiberportals plus Bildschirmfreigabe.

!!! warning "Planen Sie Ihre Supportprozesse vor der Umstellung darauf"
    Betreiber, die ihren Support-Ablauf auf der Identitätsübernahme aufgebaut haben und dann den Entra-Modus aktivieren, entdecken den Verlust im ungünstigsten Moment. Entscheiden Sie *vor* der Umstellung, wie Sie Kunden ohne sie unterstützen — nicht danach.

### Weitere Grenzen

- **Das Token ist kurzlebig.** Lange Sitzungen laufen ab; steigen Sie neu ein, statt zu verlängern.
- **Sie erhalten einen festen Rollensatz**, nicht die konkreten Rollen eines bestimmten Nutzers. Ein Problem, das von den engeren Rechten eines einzelnen Nutzers abhängt, können Sie so nicht nachstellen.
- **Step-up und Vier-Augen-Prinzip gelten weiterhin.** Die Übernahme umgeht sie nicht.
- **Einen anderen Betreiber können Sie nicht übernehmen.** Sie zielt ausschließlich auf Kunden-Rechtsträger.

---

## Sie steuern

Die Identitätsübernahme ist eine ständige Fähigkeit jedes `REGISTRY_ADMIN`. Damit ist sie eine Frage der Kontrolle, nicht der Technik — und Prüfer werden danach fragen.

!!! tip "Praktiken, die sich lohnen"

    **Verlangen Sie einen Grund, dokumentiert außerhalb der Plattform.** Eine Ticket-Referenz, vor der Sitzung. Das Audit-Log hält fest, dass Sie übernommen haben; es kann nicht festhalten, *warum*.

    **Sehen Sie Übernahmeereignisse regelmäßig durch.** Sie sind abfragbar. Ein monatlicher Blick darauf, wer wen übernommen hat, abgeglichen mit Tickets, macht aus einer unbegrenzten Befugnis eine beaufsichtigte.

    **Halten Sie den Kreis der `REGISTRY_ADMIN` klein.** Jeder Inhaber kann jeden Kunden übernehmen. Das ist das stärkste Argument für einen knappen Adminkreis.

    **Sagen Sie Kunden, dass es das gibt.** Im Nachhinein zu erfahren, dass Betreibermitarbeitende ihr Portal betreten können, schadet dem Vertrauen weit mehr als die Fähigkeit selbst. Richtig gerahmt — *wir können sehen, was Sie sehen, und jede Handlung wird auf unseren Namen aufgezeichnet* — beruhigt es.

    **Lassen Sie nie eine Sitzung offen.** Steigen Sie nach getaner Arbeit aus. Ein unbeaufsichtigter Browser in einer Übernahmesitzung ist ein unbeaufsichtigter Browser im Konto eines Kunden.

---

## Was ein Prüfer fragen wird

Halten Sie Antworten bereit:

- Wer hält `REGISTRY_ADMIN`, und wie viele Personen sind das?
- Wie verknüpfen Sie ein Übernahmeereignis mit einem Supportanlass?
- Wie würden Sie eine Übernahme *ohne* zugehöriges Ticket entdecken?
- Können Sie zeigen, dass übernommene Handlungen dem Betreiber zugerechnet werden und nicht dem Kunden?

Die letzte Frage ist eine Live-Vorführung und sollte geübt sein: einen Testrechtsträger übernehmen, eine harmlose Handlung ausführen, den Audit-Eintrag zeigen, der Ihren Nutzer mit gesetztem `imp` nennt.

---

## Wohin als Nächstes

- [Zwei-Faktor-Support](two-factor-support.md) — der andere große Support-Ablauf
- [Audit-Log](../../platform/audit-log.md)
- [Rollen und Berechtigungen](roles.md)
