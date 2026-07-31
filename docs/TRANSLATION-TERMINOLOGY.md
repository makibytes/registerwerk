# Translation terminology

Authoritative term list for the Registerwerk documentation. **Translators must use these
renderings** — consistency matters more than elegance, and in several cases the everyday
word is not the legal one.

Not published: excluded from the MkDocs build (`exclude_docs`). It is a working document
for whoever maintains the translations.

---

## Governing law per language

Each language has a *different national statute* for the same idea. The translations are
anchored to those statutes, not to a literal rendering of the German original.

| Language | Statute | Instrument is called | Register is called | Register keeper |
|---|---|---|---|---|
| **de** | eWpG (Gesetz über elektronische Wertpapiere, 2021) | elektronisches Wertpapier · Kryptowertpapier | Wertpapierregister · Kryptowertpapierregister | registerführende Stelle |
| **fr** | Code monétaire et financier, art. L.211-3 / R.211-9-7; ord. 2017-1674, loi 2023-171, décret 2023-421 | titre financier inscrit dans un DEEP | dispositif d'enregistrement électronique partagé (DEEP) | teneur de registre |
| **it** | D.L. 25/2023 → L. 52/2023 ("Decreto Fintech"); TUF D.Lgs 58/1998 | strumento finanziario digitale | registro per la circolazione digitale | responsabile del registro |
| **es** | Ley 6/2023 del Mercado de Valores y de los Servicios de Inversión (LMVSI) | valor negociable representado mediante sistemas basados en tecnología de registro distribuido (TRD) | registro basado en TRD | entidad responsable de la inscripción y registro (ERIR) |

!!! danger "Do not translate the German statute references"
    `§16 eWpG` stays `§16 eWpG` in every language. It names a specific provision of German
    law. Rendering it as "art. 16 de la loi allemande" makes it uncitable.

    The same applies to `Sammeleintragung`, `Einzeleintragung`, `Sperrvermerk` and
    `Registerauszug`: give the German term, then a gloss in the target language on first
    use. These are terms of art in a German statute, not generic concepts.

---

## Core instrument terms

| English | de | fr | it | es |
|---|---|---|---|---|
| security (financial instrument) | Wertpapier | titre financier | strumento finanziario | valor negociable |
| electronic security | elektronisches Wertpapier | titre financier électronique | strumento finanziario digitale | valor representado mediante TRD |
| bond | Anleihe / Schuldverschreibung | obligation | obbligazione | bono / obligación |
| issuer | Emittent | émetteur | emittente | emisor |
| investor | Anleger / Investor | investisseur | investitore | inversor |
| holder | Inhaber | titulaire / porteur | titolare | titular / tenedor |
| register entry | Registereintragung | inscription | iscrizione / registrazione | inscripción / anotación |
| face value / nominal | Nennbetrag (legal) · Nennwert | valeur nominale | valore nominale | valor nominal |
| unit / denomination | Stückelung | coupure | taglio | denominación |
| coupon | Kupon / Zinskupon | coupon | cedola | cupón |
| maturity | Fälligkeit · Endfälligkeit | échéance | scadenza | vencimiento |
| redemption | Rückzahlung · Tilgung | remboursement | rimborso | amortización / reembolso |
| issue price | Ausgabepreis / Emissionskurs | prix d'émission | prezzo di emissione | precio de emisión |
| zero-coupon bond | Nullkuponanleihe | obligation à coupon zéro | obbligazione zero coupon | bono cupón cero |
| day count convention | Zinsberechnungsmethode | convention de décompte des jours | convenzione di calcolo giorni | base de cálculo de intereses |
| accrued interest | Stückzinsen | coupon couru | rateo di interessi | cupón corrido |
| ISIN | ISIN | ISIN (code) | ISIN (codice) | ISIN (código) |
| prospectus | Wertpapierprospekt | prospectus | prospetto | folleto |

!!! note "Nennbetrag versus Nennwert"
    §16 eWpG uses **Nennbetrag**. Use it wherever the register record is meant. `Nennwert`
    is acceptable in general prose about pricing, but the register field is the *Nennbetrag*.

---

## Market and lifecycle terms

| English | de | fr | it | es |
|---|---|---|---|---|
| primary market | Primärmarkt | marché primaire | mercato primario | mercado primario |
| secondary market | Sekundärmarkt | marché secondaire | mercato secondario | mercado secundario |
| issuance | Emission | émission | emissione | emisión |
| subscription | Zeichnung | souscription | sottoscrizione | suscripción |
| listing (an offer to sell) | Verkaufsangebot | offre de vente | proposta di vendita | oferta de venta |
| trade / execution | Ausführung / Geschäft | exécution / transaction | esecuzione / operazione | ejecución / operación |
| settlement | Abwicklung | règlement-livraison | regolamento | liquidación |
| delivery versus payment (DvP) | Lieferung gegen Zahlung (LgZ) | livraison contre paiement (LCP) | consegna contro pagamento | entrega contra pago |
| corporate action | Kapitalmaßnahme | opération sur titres (OST) | operazione societaria | operación societaria / evento corporativo |
| record date | Nachweisstichtag | date d'enregistrement | data di registrazione | fecha de registro |
| ex date | Ex-Tag | date de détachement | data di stacco | fecha ex-cupón |
| payment date | Zahltag / Zahlungstermin | date de paiement | data di pagamento | fecha de pago |
| announcement date | Ankündigungstag | date d'annonce | data di annuncio | fecha de anuncio |
| entitlement | Anspruch / Berechtigung | droit | diritto / spettanza | derecho |
| dividend | Dividende | dividende | dividendo | dividendo |
| split | Aktiensplit | division du nominal | frazionamento | desdoblamiento (split) |
| default (failure to pay) | Zahlungsausfall | défaut de paiement | inadempimento | impago / incumplimiento |
| suspension | Aussetzung | suspension | sospensione | suspensión |
| tax certificate | Steuerbescheinigung | attestation fiscale | certificazione fiscale | certificado fiscal |

!!! warning "Kapitalmaßnahme, not 'Unternehmensaktion'"
    "Corporate action" has an established German term. A literal translation reads as a
    machine rendering to anybody in the industry.

    Same trap in French: it is **opération sur titres**, never "action d'entreprise".

---

## Repo, lending and collateral

The most error-prone group. Each language has a settled market term that is *not* a
translation of the English.

| English | de | fr | it | es |
|---|---|---|---|---|
| repo (repurchase agreement) | Pensionsgeschäft (Repo) | pension livrée (repo) | pronti contro termine (PCT) | operación con pacto de recompra (repo) |
| securities lending | Wertpapierleihe | prêt de titres | prestito titoli | préstamo de valores |
| collateral | Sicherheit(en) | garantie / collatéral | garanzia / collaterale | garantía / colateral |
| to pledge | verpfänden | nantir / donner en garantie | costituire in garanzia | pignorar / dar en garantía |
| pledge (the right) | Pfandrecht | nantissement | pegno | prenda |
| loan | Darlehen | prêt | prestito | préstamo |
| borrower | Darlehensnehmer | emprunteur | mutuatario / debitore | prestatario |
| lender / supplier | Darlehensgeber | prêteur | finanziatore | prestamista |
| loan-to-value (LTV) | Beleihungsquote (LTV) | quotité de financement (LTV) | rapporto prestito/valore (LTV) | relación préstamo-valor (LTV) |
| liquidation (of collateral) | Verwertung | réalisation de la garantie | escussione della garanzia | ejecución de la garantía |
| liquidation bonus | Verwertungsabschlag | prime de liquidation | premio di liquidazione | bonificación de liquidación |
| health factor | Sicherheitsfaktor (Health Factor) | facteur de santé (health factor) | fattore di salute (health factor) | factor de salud (health factor) |
| margin call | Nachschussaufforderung | appel de marge | richiesta di margine | ajuste de márgenes |
| utilisation | Auslastung | taux d'utilisation | tasso di utilizzo | tasa de utilización |
| interest rate | Zinssatz | taux d'intérêt | tasso di interesse | tipo de interés |
| basis point (bps) | Basispunkt (bp) | point de base (pb) | punto base (pb) | punto básico (pb) |

!!! danger "Verwertung, not Liquidation"
    German **Liquidation** means winding up a company. Selling pledged collateral is
    **Verwertung**. Getting this wrong changes the meaning entirely.

    Italian **escussione** likewise: *liquidazione* is company wind-up.

    Spanish: **ejecución de la garantía**, not *liquidación* (which means settlement).

!!! note "es: liquidación is a false friend twice over"
    In Spanish, *liquidación* = **settlement** of a trade. Never use it for collateral
    enforcement (*ejecución*) or company wind-up (*disolución/liquidación societaria*).

---

## Compliance and regulation

| English | de | fr | it | es |
|---|---|---|---|---|
| KYC (know your customer) | Kundenidentifizierung / KYC | connaissance du client (KYC) | adeguata verifica della clientela (KYC) | conocimiento del cliente (KYC) |
| customer due diligence | Sorgfaltspflichten | vigilance / diligence | adeguata verifica | diligencia debida |
| AML | Geldwäscheprävention | lutte contre le blanchiment (LCB-FT) | antiriciclaggio | prevención del blanqueo de capitales |
| beneficial owner | wirtschaftlich Berechtigter | bénéficiaire effectif | titolare effettivo | titular real |
| sanctions screening | Sanktionsprüfung | filtrage des sanctions | screening sanzioni | filtrado de sanciones |
| Travel Rule | Travel Rule (Geldtransfer-VO) | Travel Rule | Travel Rule | Travel Rule |
| audit trail / log | Prüfpfad / Audit-Log | piste d'audit | pista di controllo | pista de auditoría |
| tamper-evident | manipulationssicher nachweisbar | inviolable / infalsifiable | a prova di manomissione | a prueba de manipulación |
| four-eyes principle | Vier-Augen-Prinzip | principe des quatre yeux / double validation | principio dei quattro occhi | principio de doble control |
| step-up authentication | Step-up-Authentifizierung | authentification renforcée | autenticazione rafforzata | autenticación reforzada |
| two-factor authentication | Zwei-Faktor-Authentifizierung (2FA) | authentification à deux facteurs (2FA) | autenticazione a due fattori (2FA) | autenticación de doble factor (2FA) |
| supervisory authority | Aufsichtsbehörde | autorité de supervision | autorità di vigilanza | autoridad supervisora |
| retention (of records) | Aufbewahrung | conservation | conservazione | conservación |
| right to erasure | Recht auf Löschung | droit à l'effacement | diritto alla cancellazione | derecho de supresión |

!!! note "Strong authentication in French and Spanish"
    French **authentification forte** is the PSD2 term (SCA). Use **authentification
    renforcée** for step-up so the two are not confused.

    Spanish: PSD2 SCA is *autenticación reforzada de clientes*. For step-up, keep
    **autenticación reforzada** but do not abbreviate to SCA.

---

## Platform-specific terms

Registerwerk's own vocabulary. Translate the concept; keep the UI label matching the
interface, which is **English-only** in every deployment.

| English | de | fr | it | es |
|---|---|---|---|---|
| operator (the registry) | Registerbetreiber | opérateur du registre | operatore del registro | operador del registro |
| customer | Kunde | client | cliente | cliente |
| legal entity | juristische Person / Rechtsträger | entité juridique | soggetto giuridico | entidad jurídica |
| workspace | Arbeitsbereich | espace de travail | area di lavoro | espacio de trabajo |
| endpoint (wallet address) | Endpunkt | point de réception | endpoint | punto final |
| wallet | Wallet | portefeuille (wallet) | wallet | monedero (wallet) |
| private key | privater Schlüssel | clé privée | chiave privata | clave privada |
| minting | Emission / Erzeugung (Minting) | émission (minting) | conio (minting) | emisión (minting) |
| burning | Vernichtung (Burning) | destruction (burning) | distruzione (burning) | destrucción (burning) |
| forced transfer | Zwangsübertragung | transfert forcé | trasferimento coattivo | transferencia forzosa |
| holder block | Sperrvermerk | blocage du titulaire | blocco del titolare | bloqueo del titular |
| impersonation | Identitätsübernahme (Impersonation) | usurpation d'identité assistée / mode support | modalità supporto (impersonation) | suplantación asistida (modo soporte) |
| fail closed | Fail-Closed (Abweisung im Fehlerfall) | rejet par défaut (fail closed) | rifiuto in caso di errore (fail closed) | denegación por defecto (fail closed) |
| indexer | Indexer | indexeur | indicizzatore | indexador |
| smart contract | Smart Contract | contrat intelligent (smart contract) | smart contract | contrato inteligente (smart contract) |
| token standard | Token-Standard | norme de jeton (token standard) | standard di token | estándar de token |
| onboarding | Onboarding | intégration (onboarding) | onboarding | alta / incorporación (onboarding) |
| offboarding | Offboarding / Beendigung | résiliation (offboarding) | cessazione (offboarding) | baja (offboarding) |

!!! warning "Impersonation needs care in every language"
    The literal word means *identity theft* in most of these languages, which is precisely
    the wrong impression for a supervised support feature.

    Prefer the support framing — *mode support*, *modalità supporto*, *modo soporte* — with
    the English term in brackets on first use, and rely on the page body to explain that
    every action stays attributed to the operator.

---

## UI labels: do not translate

The portals ship **English UI only**. Where documentation names a button, menu or status,
keep the English string and gloss it:

> Öffnen Sie **Trading Desk → Create listing** (Verkaufsangebot anlegen).

This applies to: navigation items, button labels, workspace names (*Investor*, *Trader*,
*Issuer*), status values (`DRAFT`, `PENDING_APPROVAL`, `APPROVED`, `ISSUED`, `SUSPENDED`,
`REDEEMED`, `PENDING`, `SETTLED`, …), role names (`REGISTRY_ADMIN`, `COMPANY_ADMIN`, …),
configuration keys, table and column names, and code.

Translating a status value a reader must recognise on screen actively hinders them.

---

## Register of translated pages

`nav_translations` in `mkdocs.yml` covers navigation labels for every page, so the sidebar
is fully localised in all five languages regardless of body-text coverage.

Pages without a `.<lang>.md` file fall back to English automatically — the reader gets a
complete, working site, not a 404.
