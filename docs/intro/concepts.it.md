---
title: Concetti fondamentali
description: Glossario dei termini giuridici, finanziari e tecnici usati in tutto Registerwerk.
---

# Concetti fondamentali

Questo glossario definisce i termini usati nella documentazione, nel codice e nelle interfacce di Registerwerk. I termini sono raggruppati per ambito; i rimandi puntano alle pagine di dettaglio dove esistono.

---

## Strumenti finanziari ed emissione

**Token rappresentativo di strumento finanziario (security token)**
: Un token blockchain che rappresenta uno strumento finanziario — un'obbligazione, un'azione, una quota di fondo o un altro attivo regolamentato. Registerwerk gestisce questi token secondo il diritto dei mercati finanziari delle [giurisdizioni supportate](../legal/index.md).

**Strumento finanziario digitale (elektronisches Wertpapier)**
: Uno strumento che esiste esclusivamente come iscrizione in un registro elettronico centralizzato o decentralizzato, senza documento cartaceo. In Germania definito dal [§2 eWpG](../legal/ewpg.md); equivalenti esistono nel diritto lussemburghese, francese e liechtensteinese.

**Emittente**
: Il soggetto giuridico che crea e offre un token rappresentativo di strumento finanziario. In Registerwerk un emittente è un soggetto giuridico [cliente](#soggetti-clienti) con il ruolo `ISSUER` che ha superato l'approvazione [KYC/antiriciclaggio](../compliance/kyc-aml.md).

**Investitore / titolare**
: Un soggetto giuridico o una persona fisica che detiene una posizione su un token. Tracciato nel sistema come record `AssetHolder` collegato tramite una `HolderIdentity` a una `LegalEntity` o a una `NaturalPerson`.

**ISIN** (International Securities Identification Number)
: Un codice alfanumerico di 12 caratteri che identifica univocamente uno strumento a livello mondiale. Registerwerk memorizza l'ISIN sull'entità `Asset` e lo incorpora nei metadati del token.

**Numero di asset**
: L'identificativo sequenziale interno di Registerwerk per uno strumento, distinto dall'ISIN. Usato nei flussi interni e nei riferimenti di revisione.

**Emissione / distribuzione**
: L'atto di creare un contratto di token su una blockchain. In Registerwerk la distribuzione è tracciata da un record `AssetDeployment` che collega l'`Asset` off-chain al suo indirizzo di contratto on-chain.

---

## Concetti blockchain

**Blockchain / chain**
: Una rete a libro mastro distribuito. Registerwerk supporta Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism (EVM), Solana, StarkNet, Stellar e Canton. Vedi [Blockchain supportate](../blockchains/index.md).

**Standard di token**
: Una specifica che definisce l'interfaccia di un token (come può essere trasferito, interrogato e amministrato). Esempi: ERC-20, ERC-3643, SPL-2022. Vedi [Standard di token](../token-standards/index.md).

**Smart contract**
: Codice eseguibile distribuito su una blockchain. Registerwerk distribuisce i contratti con [Web3j](https://web3j.io/) (EVM) e Solanaj (Solana). Gli indirizzi dei contratti sono memorizzati in `AssetDeployment`.

**Transazione (on-chain)**
: Un'operazione firmata crittograficamente e inviata a una blockchain. Ogni cambiamento di stato è registrato come `BlockchainTransaction` e collegato al corrispondente evento di revisione.

**Scostamento di chain**
: Una discrepanza tra il saldo di token on-chain e il campo `AssetHolder.nominalAmount` del database di Registerwerk. Il `ChainDriftDetectionJob` verifica lo scostamento ogni 15 minuti per ciascun asset emesso.

**Registro autoritativo**
: Registerwerk conserva una registrazione operativa dei titolari in PostgreSQL e proietta o riconcilia parte dello stato on-chain. Quale registrazione abbia autorità giuridica dipende dallo strumento, dal modello di registro, dall'operatore e dalla giurisdizione, e richiede una decisione di perimetro approvata. Né il database né la blockchain sono universalmente autoritativi.

**Wallet**
: Una coppia di chiavi crittografiche usata per firmare transazioni on-chain. Registerwerk gestisce i wallet dell'operatore (materiale delle chiavi cifrato a riposo) tramite il modulo `wallet`.

---

## Regolamentazione e conformità

**KYC** (Know Your Customer)
: Il processo di verifica dell'identità di un cliente — compresi la sua attività, i proprietari e i titolari effettivi — prima di instaurare un rapporto d'affari. Vedi [KYC e antiriciclaggio](../compliance/kyc-aml.md).

**KYB** (Know Your Business)
: L'equivalente societario del KYC, incentrato sulla verifica della legittimità e dell'assetto proprietario di un soggetto giuridico.

**Antiriciclaggio (AML)**
: Il corpo di norme che impone alle imprese di rilevare e prevenire il riciclaggio di denaro. In Germania: la GwG; a livello di Unione: AMLD6 e il prossimo AMLR.

**PEP** (persona politicamente esposta)
: Una persona che ricopre o ha ricoperto una funzione pubblica di rilievo. Le PEP richiedono una verifica rafforzata ai sensi del [§10(2) GwG](../compliance/kyc-aml.md).

**Titolare effettivo (UBO)**
: La o le persone fisiche che in ultima istanza possiedono o controllano un soggetto giuridico, di norma oltre una soglia del 25 %. Tracciato in Registerwerk come `BeneficialOwner` collegato a una `NaturalPerson`.

**Screening sanzioni**
: Il confronto di una persona o di un soggetto con le liste sanzioni internazionali (OFAC SDN, PESC dell'UE, ONU 1267, HMT del Regno Unito, SECO svizzera). Vedi [Screening sanzioni](../compliance/sanctions-screening.md).

**Travel Rule (TFR)**
: Il regolamento (UE) 2023/1113 che impone che le informazioni su ordinante e beneficiario accompagnino i trasferimenti di cripto-attività superiori a 1.000 € tra VASP. Attuato con lo [standard di dati IVMS-101](../compliance/travel-rule.md).

**VASP** (prestatore di servizi su attività virtuali)
: Un'impresa vigilata che fornisce servizi relativi ad attività virtuali (exchange, depositari). Registerwerk stesso agisce come VASP/CASP quando emette token per conto di terzi.

**CASP** (prestatore di servizi per le cripto-attività)
: Il termine usato da MiCAR per VASP nel diritto dell'Unione.

**Sperrvermerk**
: Termine giuridico tedesco per un'annotazione di blocco su un'iscrizione nel registro titoli, che limita il trasferimento o grava l'attivo. Imposto dal [§16 eWpG](../legal/ewpg.md). Vedi [Sperrvermerk](../compliance/sperrvermerk.md).

**DORA** (Digital Operational Resilience Act)
: Il regolamento (UE) 2022/2554 che impone ai soggetti finanziari di gestire i rischi informatici, segnalare gli incidenti gravi e tenere un registro dei fornitori informatici terzi. Vedi [DORA](../compliance/dora.md).

**LEI** (identificativo del soggetto giuridico)
: Un codice di 20 caratteri conforme alla ISO 17442 che identifica univocamente un soggetto giuridico a livello mondiale. Memorizzato su `LegalEntity` in Registerwerk; consigliato per tutti gli emittenti.

---

## Soggetti clienti

**Operatore**
: L'organizzazione che gestisce un'installazione di Registerwerk. Gli operatori accedono al frontend operatore (:44200) e possono amministrare tutti i clienti, gli asset e i dati di conformità.

**Cliente**
: Un emittente o un investitore attivato da un operatore. I clienti accedono al frontend cliente (:44201) attraverso il gateway API Kong.

**Soggetto giuridico (`LegalEntity`)**
: Il modello dati centrale per la società di un cliente. Contiene giurisdizione, numero di iscrizione, LEI, stato KYC e i collegamenti ai titolari effettivi e ai documenti KYC.

**Persona fisica (`NaturalPerson`)**
: Un individuo — amministratore, titolare effettivo o investitore. L'entità attuale colloca i dati personali come nome, data di nascita, cittadinanza e codice fiscale in normali colonne di database; la cifratura dei campi a livello applicativo non è implementata.

**Titolare effettivo (`BeneficialOwner`)**
: Fa da ponte tra una `LegalEntity` e una `NaturalPerson`, con percentuale di partecipazione e tipo di controllo.

---

## Termini propri della piattaforma

**Modulo**
: Un contesto delimitato Spring Modulith. Registerwerk ha 34 moduli, ciascuno con un package `api/` (tipi pubblici) e un package `internal/` (implementazione privata). Vedi [Architettura modulare](../platform/modules.md).

**Autenticazione rafforzata (step-up)**
: Una seconda sfida di autenticazione richiesta prima di eseguire operazioni ad alto rischio (trasferimento coattivo, distruzione coattiva, deroga al KYC). Applicata dall'annotazione `@RequiresStepUp`. Vedi [MFA rafforzata](../compliance/step-up-mfa.md).

**Principio dei quattro occhi (Vier-Augen-Prinzip)**
: Un requisito di doppio controllo per cui un secondo approvatore autorizzato deve confermare un'azione prima che abbia effetto. Realizzato dal modulo `stepup`.

**Catena di revisione**
: La sequenza a prova di manomissione degli eventi di revisione, ciascuno contenente un hash della voce precedente. Fornisce una prova crittografica della completezza e dell'integrità della pista di controllo. Vedi [Pista di controllo](../platform/audit-log.md).
