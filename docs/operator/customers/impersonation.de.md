---
title: Identitätsübernahme — sehen, was der Kunde sieht
description: Im Kundenportal für den Support handeln: wie es funktioniert, wem es zugerechnet wird, wo die Grenzen liegen und wie man es steuert.
---

# Identitätsübernahme — sehen, was der Kunde sieht

Ein Kunde sagt, der Trading Desk lasse ihn keinen Bestand einstellen. Sie sehen sich sein Konto im Betreiberportal an, und alles wirkt in Ordnung. Sie bitten um einen Screenshot und bekommen ein Foto eines Bildschirms.

**Die Identitätsübernahme beendet diese Schleife.** Sie öffnet das Kundenportal mit ausgewählter Kundenorganisation, sodass Sie genau das sehen, was der Kunde sieht.

Sie gibt Zugriff auf die Sicht eines Kunden auf seine eigenen Daten und ist deshalb abgesichert: Für den Start sind ein frischer Step-up-Nachweis und eine schriftliche Begründung nötig, der Standard ist eine **schreibgeschützte** Sitzung, und eine Sitzung mit Schreibrechten braucht einen zweiten Genehmiger und steht nur im Demo-Modus zur Verfügung.

---

## Was sie tatsächlich ist

Kein Passwort-Reset. Kein Anmelden als der Kunde. Sie erhalten nie dessen Zugangsdaten, und er wird nie abgemeldet.

Der Startaufruf (`POST /api/v1/impersonation`, Step-up-Grund `ADMIN_IMPERSONATION`) enthält den Kunden, eine **verpflichtende Begründung** (mindestens 15 Zeichen) und optional eine Ticket-Referenz. Er liefert **kein Token** zurück, sondern eine Übergabe-URL mit einem **Einmalcode**, gültig für 60 Sekunden. Das Kundenportal tauscht den Code gegen ein httpOnly-Sitzungscookie; wird der Code ein zweites Mal verwendet, endet die Sitzung. Das Token gelangt damit nie in die Hände oder den Browserverlauf des Betreibers.

Das Sitzungstoken hinter dem Cookie trägt:

| Claim | Wert |
|---|---|
| `sub` | **Ihre** Nutzer-ID — nicht seine |
| `entityId` | Die Kundenorganisation, in der Sie handeln |
| `roles` | `COMPANY_ADMIN`, `ISSUER`, `INVESTOR`, `TRADER` |
| `imp` | `true` |
| `imp_mode` | `READ_ONLY` (Standard) oder `ACT_ON_BEHALF` |
| `jti` | Die ID des Datensatzes in `impersonation_session` |
| `exp` | 30 Minuten (`registerwerk.auth.impersonation-ttl-seconds`, Standard 1800) |

!!! success "Das Subjekt bleiben Sie, und darin besteht der ganze Entwurf"
    Weil `sub` Ihre Nutzer-ID bleibt, wird **jede Handlung, die Sie vornehmen, Ihnen zugerechnet** — im [Audit-Log](../../platform/audit-log.md), nicht dem Kunden und nicht irgendeinem gemeinsamen „System"-Akteur.

    Ein Kunde kann niemals für etwas verantwortlich gemacht werden, das ein Betreiber während einer Identitätsübernahme getan hat, und ein Betreiber kann sich niemals hinter der Identität eines Kunden verstecken. Ohne diese Eigenschaft wäre die Identitätsübernahme in einem regulierten Umfeld unbrauchbar.

    Das Kennzeichen `imp: true` markiert die Sitzung als übernommen, sodass übernommene Handlungen im Log von gewöhnlichen unterscheidbar sind.

### Modi

| | `READ_ONLY` (Standard) | `ACT_ON_BEHALF` |
|---|---|---|
| Start-Endpunkt | `POST /api/v1/impersonation` | `POST /api/v1/impersonation/act-on-behalf` |
| Wer starten darf | `REGISTRY_ADMIN` oder `SUPPORT_AGENT` | nur `REGISTRY_ADMIN` |
| Step-up | Ja (`ADMIN_IMPERSONATION`) | Ja, zusätzlich ein **zweiter Genehmiger** (`ADMIN_IMPERSONATION_ACT_ON_BEHALF`) |
| Was die Sitzung kann | Nur lesen: `POST`, `PUT`, `PATCH` und `DELETE` werden mit `403 IMPERSONATION_READ_ONLY` abgewiesen | Schreiben, außer der Sperrliste unten |
| Produktionsmodus | Verfügbar | **Abgelehnt.** Im Produktionsmodus wird jede laufende Sitzung als schreibgeschützt erzwungen, auch eine übrig gebliebene Schreibsitzung |

Die Sperrliste für `ACT_ON_BEHALF` (`registerwerk.auth.impersonation-deny-patterns`) liefert `403 IMPERSONATION_ACTION_DENIED` für Bestätigungen im Namen des Kunden und Kontoverwaltung: Zahlungsbestätigung, Zahlungsstreit und Abwicklung von Trades, Default-Erklärungen im Repo-Desk, Verwaltung der Unternehmensnutzer, Identity-Provider-Einstellungen des Unternehmens, Webhooks, Organisationsidentität und das Löschen von KYC-Dokumenten.

!!! note "SUPPORT_AGENT"
    `SUPPORT_AGENT` ist eine Betreiber-Rolle für den Support: Sie kann schreibgeschützte Sitzungen starten und Kunden-Rechtsträger zur Auswahl auflisten, sonst nichts. Vergabe und Entzug erfordern Step-up und einen zweiten Genehmiger; die Rolle ist in den Zugriffsprüfungen enthalten.

Nur **aktive** Rechtsträger können übernommen werden. Die Sitzung wird in `impersonation_session` erfasst (Akteur, Rechtsträger, Modus, Begründung, Ticket, Genehmiger, Ablauf), und **die Unternehmensadministratoren des Kunden sehen jede Sitzung auf ihrem Rechtsträger** unter `GET /api/v1/company/impersonation-sessions`.

---

## Der Einsatz

1. Öffnen Sie im Betreiberportal den Datensatz des Kunden und wählen Sie **Impersonate**. Geben Sie die Begründung (und, falls vorhanden, eine Ticket-Referenz) ein. Der Dialog bietet schreibgeschützt an; der Schreibmodus erscheint nur im Demo-Modus.
2. Sie werden an das Kundenportal unter `/admin/handoff` übergeben. Das URL-Fragment enthält den Einmalcode `code`, `entityId` und `entityName`; das Portal tauscht den Code gegen sein Sitzungscookie und setzt Sie im Dashboard ab.
3. Eine **dauerhafte Leiste** sitzt oben auf jeder Seite: *Acting as **Nordwind Energie GmbH*** (in einer schreibgeschützten Sitzung: *Viewing … (read-only support session - changes are blocked)*), mit **Switch company** und **Exit impersonation**.
4. Sehen Sie nach und diagnostizieren Sie. Alles, was Sie tun, wird als Ihre Handlung protokolliert.
5. Wählen Sie am Ende **Exit impersonation**. Die Sitzung endet und wird protokolliert; ohne Ausstieg läuft sie nach 30 Minuten ab.

Sie können auch einsteigen, ohne zuvor einen Kunden zu wählen — die Leiste zeigt dann *Admin mode — no company selected* und bietet **Select company** mit durchsuchbarer Liste. Ein `SUPPORT_AGENT` landet nach der Anmeldung auf dieser Firmenauswahl.

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

- **Die Sitzung ist kurzlebig.** Sie läuft nach 30 Minuten ab; steigen Sie neu ein (mit neuer Begründung), statt zu verlängern.
- **Der Übergabecode gilt einmal und 60 Sekunden.** Holt das Portal ihn nicht rechtzeitig ab oder wird er erneut verwendet, endet die Sitzung; starten Sie neu.
- **Sie erhalten einen festen Rollensatz**, nicht die konkreten Rollen eines bestimmten Nutzers. Ein Problem, das von den engeren Rechten eines einzelnen Nutzers abhängt, können Sie so nicht nachstellen.
- **Step-up und Vier-Augen-Prinzip werden nicht umgangen.** Der Start braucht Ihren eigenen Step-up-Nachweis; eine Schreibsitzung zusätzlich einen zweiten Genehmiger. Innerhalb einer Sitzung bleiben geschützte Vorgänge des Kunden geschützt, und die Freigabe-Warteschlange lehnt Identitätsübernahme-Sitzungen ab.
- **Einen anderen Betreiber können Sie nicht übernehmen.** Sie zielt ausschließlich auf Kunden-Rechtsträger.

---

## Sie steuern

Die Identitätsübernahme steht jedem `REGISTRY_ADMIN` und jedem `SUPPORT_AGENT` zur Verfügung. Damit ist sie neben der Technik auch eine Frage der Kontrolle — und Prüfer werden danach fragen.

!!! tip "Praktiken, die sich lohnen"

    **Machen Sie die Begründung aussagekräftig.** Die Plattform lehnt einen Start ohne Begründung von mindestens 15 Zeichen ab und hält sie samt optionaler Ticket-Referenz in `impersonation_session` und im Audit-Ereignis fest. Tragen Sie die Ticketnummer ins Ticketfeld ein und schreiben Sie in die Begründung, was Sie sehen müssen.

    **Sehen Sie Übernahmeereignisse regelmäßig durch.** Sie sind abfragbar (Ereignisnamen unten). Ein monatlicher Blick darauf, wer wen übernommen hat, abgeglichen mit Tickets, macht aus einer weitreichenden Befugnis eine beaufsichtigte. Die Unternehmensadministratoren des Kunden können dieselbe Prüfung von ihrer Seite aus durchführen.

    **Setzen Sie für Supportmitarbeitende bevorzugt `SUPPORT_AGENT` ein.** Die Rolle kann schreibgeschützte Sitzungen starten und sonst nichts; der Support braucht dann kein `REGISTRY_ADMIN`-Konto.

    **Halten Sie den Kreis der `REGISTRY_ADMIN` klein.** Jeder Inhaber kann Sitzungen für jeden aktiven Kunden starten.

    **Sagen Sie Kunden, dass es das gibt.** Im Nachhinein zu erfahren, dass Betreibermitarbeitende ihr Portal betreten können, schadet dem Vertrauen weit mehr als die Fähigkeit selbst. Richtig gerahmt — *wir können sehen, was Sie sehen, jede Handlung wird auf unseren Namen aufgezeichnet, und Ihre Administratoren können jede Sitzung einsehen* — beruhigt es.

    **Lassen Sie nie eine Sitzung offen.** Steigen Sie nach getaner Arbeit aus. Ein unbeaufsichtigter Browser in einer Übernahmesitzung ist ein unbeaufsichtigter Browser im Konto eines Kunden (nach 30 Minuten läuft sie allerdings ab).

---

## Was ein Prüfer fragen wird

Halten Sie Antworten bereit:

- Wer hält `REGISTRY_ADMIN` oder `SUPPORT_AGENT`, und wie viele Personen sind das?
- Wie verknüpfen Sie ein Übernahmeereignis mit einem Supportanlass? (Begründung und Ticket stehen in `impersonation_session` und im Ereignis `ADMIN_IMPERSONATION_STARTED`.)
- Wie würden Sie eine Übernahme *ohne* zugehöriges Ticket entdecken?
- Können Sie zeigen, dass übernommene Handlungen dem Betreiber zugerechnet werden und nicht dem Kunden?
- Ist die Schreib-Übernahme in der Produktion abgeschaltet? (Ja: Der Produktionsmodus lehnt `ACT_ON_BEHALF` ab und stuft jede laufende Sitzung auf schreibgeschützt herab.)

Der Audit-Pfad enthält die Ereignisse `ADMIN_IMPERSONATION_STARTED`, `ADMIN_IMPERSONATION_HANDOFF_EXCHANGED` und `ADMIN_IMPERSONATION_ENDED`; Anfragen innerhalb einer Sitzung tragen die Markierung `imp`. Die Zurechnungsfrage ist eine Live-Vorführung und sollte geübt sein: einen Testrechtsträger übernehmen, eine Seite ansehen, die Audit-Einträge zeigen, die Ihren Nutzer mit gesetztem `imp` nennen, und die Sitzung in der Ansicht der Unternehmensadministratoren des Kunden zeigen.

---

## Wohin als Nächstes

- [Zwei-Faktor-Support](two-factor-support.md) — der andere große Support-Ablauf
- [Audit-Log](../../platform/audit-log.md)
- [Rollen und Berechtigungen](roles.md)
