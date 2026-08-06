---
title: Glossario
description: Ogni termine usato in questa documentazione, definito in modo semplice.
---

# Glossario

Definizioni semplici. Dove un termine ha un significato tecnico preciso diverso dall'uso comune, la differenza viene indicata.

---

## Finanza

**Cedola**
: L'interesse che paga un'obbligazione. Il nome sopravvive dalle obbligazioni cartacee, dove si staccava una cedola dal certificato per riscuotere ogni pagamento.

**Consegna contro pagamento**
: Regolare in modo che lo strumento si muova se e solo se si muove il pagamento. Elimina il rischio che una parte esegua e l'altra no.

**Data di registrazione**
: L'istante in cui il registro viene fotografato per decidere a chi spetta un pagamento. Se detieni a quella data il pagamento è tuo, anche se vendi domani.

**Data di stacco**
: Da questa data uno strumento tratta *senza* un pagamento imminente. Compri dopo e il pagamento è del venditore.

**Depositario**
: Un intermediario che detiene strumenti finanziari per conto altrui. In un'iscrizione collettiva il depositario è il titolare iscritto.

**Emittente**
: L'organizzazione che crea uno strumento finanziario e deve ciò che promette.

**Garanzia** (anche *collaterale*)
: Un bene di valore costituito a garanzia di un prestito. Se il debitore non rimborsa, il finanziatore può venderlo.

**Liquidità**
: La facilità con cui qualcosa si trasforma in denaro senza spostarne il prezzo. Uno strumento che nessuno vuole comprare è illiquido.

**LLTV**
: La soglia di rapporto prestito/valore oltre la quale un prestito può essere escusso.

**Mercato primario**
: L'emittente che vende agli investitori. Il denaro arriva all'emittente. Accade una volta sola.

**Mercato secondario**
: Gli investitori che si vendono tra loro. L'emittente non è parte e non riceve nulla.

**Obbligazione**
: Un prestito diviso in parti uguali, così che molti finanziatori possano prenderne una ciascuno. Il debitore paga interessi e a scadenza rimborsa il valore nominale.

**Obbligazione zero coupon**
: Un'obbligazione che non paga interessi e che in cambio viene venduta sotto il valore nominale. Compri a 800 €, ricevi 1.000 € a scadenza.

**Operazione societaria**
: Tutto ciò che un emittente fa e che tocca i titolari in quanto titolari — pagare una cedola, frazionare le unità, rimborsare il capitale.

**Pronti contro termine** (*repo*)
: Una vendita con riacquisto concordato a un prezzo più alto. Economicamente un prestito garantito; la differenza di prezzo è l'interesse. Strutturata come vendita perché la proprietà piena resiste all'insolvenza meglio di una garanzia.

**Punto base (pb)**
: Un centesimo di punto percentuale. 100 pb = 1 %. Si usa perché «il tasso è salito dell'1 %» è ambiguo — dal 4 % al 5 %, o dal 4 % al 4,04 %? I punti base tolgono l'ambiguità.

**Rapporto prestito/valore (LTV)**
: Quanto hai preso a prestito in percentuale del valore della tua garanzia. Prendi 50.000 € contro 100.000 € di garanzia e il tuo LTV è il 50 %.

**Regolamento**
: Il completamento di un'operazione — strumenti e denaro che cambiano davvero di mano. Da distinguere dal concluderla.

**Rimborso**
: Restituire il capitale di uno strumento e ritirarlo.

**Scadenza**
: La data in cui un'obbligazione finisce e il suo valore nominale viene rimborsato.

**Valore nominale** (anche *nominale*, *pari*)
: L'importo scritto sullo strumento — ciò che viene rimborsato a scadenza. **Non** il prezzo. Un'obbligazione da 1.000 € può trattare a 960 €.

**Valore nominale detenuto**
: Il valore nominale che un titolare detiene. Ciò che il registro annota. Non il valore di mercato.

---

## Blockchain

**Blockchain**
: Un libro mastro condiviso, tenuto da molte parti, in cui le voci non possono essere alterate di nascosto una volta registrate.

**Chiave privata**
: Il segreto che autorizza le azioni da un indirizzo wallet. Non può essere reimpostata, recuperata né riemessa. Perderla significa perdere la possibilità di muovere i token.

**Conio** (*minting*)
: Creare token che non esistevano. L'opposto della distruzione.

**Distruzione** (*burning*)
: Distruggere token. L'offerta diminuisce. Irreversibile.

**ERC-20**
: Lo standard comune di token fungibile. Semplice e supportato ovunque, **senza** alcuna nozione di chi possa detenerlo.

**ERC-3643** (anche *T-REX*)
: Uno standard di token per strumenti finanziari regolamentati. Verifica l'ammissibilità prima di ogni trasferimento e fa fallire on-chain quelli non conformi.

**Gas**
: La commissione pagata per far elaborare una transazione.

**Hash di transazione**
: L'identificativo di una transazione. La tua ricevuta; cercalo su un block explorer.

**Indirizzo del contratto**
: Il luogo in cui uno smart contract risiede su una chain. Pubblico; chiunque può ispezionarlo.

**Mainnet / testnet**
: La rete reale, dove il valore è reale. E la rete di prova, dove non lo è.

**ONCHAINID**
: Un contratto di identità on-chain che contiene i claim verificati di una parte sotto ERC-3643.

**Revert**
: Una transazione che fallisce e si annulla per intero. Un controllo di conformità che fallisce provoca un revert — non accade nulla a metà.

**Smart contract**
: Un programma su una blockchain. Si esegue esattamente come è scritto, quando viene chiamato, senza che nessuno decida di consentirlo.

**Stablecoin**
: Un token pensato per mantenere un valore stabile rispetto a una valuta.

**Token**
: Un'unità registrata in uno smart contract. Qui, la rappresentazione on-chain di uno strumento finanziario — il meccanismo, non lo strumento stesso.

**Wallet**
: Software che custodisce una chiave privata. Non contiene token — è il contratto ad annotare un saldo a fronte del tuo indirizzo.

---

## Registerwerk

**Area di lavoro** (*workspace*)
: Una vista del portale cliente che raggruppa gli strumenti per un mestiere — Investor, Trader o Issuer. Navigazione, **non** permesso.

**Asset**
: Uno strumento finanziario nel registro. Formalmente: la registrazione che il registro fa di uno strumento.

**Autenticazione rafforzata** (*step-up*)
: Richiedere una prova d'identità fresca per un'azione sensibile, oltre a una sessione già aperta.

**Canale di pagamento**
: Un mezzo supportato per muovere la gamba contante — stablecoin, API di pagamento istantaneo, regolamento consegna contro pagamento, o bonifico bancario.

**Endpoint**
: Un indirizzo wallet che hai registrato presso il registro, con un'etichetta.

**Estratto di registro** (*Registerauszug*)
: Un prospetto del contenuto del registro relativo a un titolare. Ai sensi del §19(2) eWpG, dovuto ai titolari consumatori con iscrizione individuale. Un documento di registro conservato, non una notifica.

**Indicizzatore**
: Software che osserva le blockchain e scrive nel registro ciò che vede.

**Manifesto**
: Il JSON firmato che descrive una dApp del marketplace. Il suo hash viene ancorato on-chain all'approvazione.

**Modalità supporto** (*impersonation*)
: Un operatore che agisce dentro il portale di un cliente per assistenza. Ogni azione è attribuita all'**operatore**, mai al cliente.

**Operatore**
: L'organizzazione che gestisce il registro. Approva soggetti ed emissioni e detiene i poteri di rettifica del registro.

**Pista di controllo**
: La registrazione a prova di manomissione di ogni operazione che modifica lo stato. Concatenata tramite hash, così che un'alterazione sia rilevabile.

**Principio dei quattro occhi**
: Richiedere due persone diverse. Applicato alle operazioni più taglienti.

**Registro**
: Il database dell'operatore che annota chi detiene che cosa. **La registrazione giuridicamente rilevante**, distinta dal token.

**Rifiuto in caso di errore** (*fail closed*)
: Quando un controllo non può essere eseguito, rifiutare anziché consentire. Lo screening sanzioni funziona così — un'interruzione significa trasferimenti rifiutati, non lasciati passare senza controllo.

**Soggetto giuridico**
: Un'organizzazione nel registro. Gli utenti vi appartengono; verifica e permessi si attaccano ad esso.

**Sperrvermerk**
: Una restrizione annotata su un'iscrizione ai sensi del §16 eWpG. Finché sussiste, la posizione non può essere trasferita. Resta comunque tua.

**Tipo di iscrizione**
: Se un'iscrizione è *collettiva* (un depositario detiene per molti) oppure *individuale* (l'investitore è nominato direttamente).

**Titolare**
: Un'iscrizione a registro che annota che qualcuno detiene un certo importo di uno strumento.

**Trasferimento coattivo**
: Una rettifica eseguita dall'operatore che sposta una posizione tra wallet, ai sensi del §24 eWpG. Il rimedio per una chiave persa o un provvedimento giudiziario. Richiede il principio dei quattro occhi.

---

## Regolamentazione

**Antiriciclaggio** (*AML*)
: Le regole che impediscono al sistema finanziario di occultare i proventi di reato.

**DORA**
: Regolamento europeo sul rischio informatico e la resilienza operativa dei soggetti finanziari.

**eWpG**
: La legge tedesca sui titoli elettronici, in vigore da giugno 2021. Consente a uno strumento finanziario di esistere come iscrizione a registro anziché come certificato cartaceo.

**GDPR / DSGVO**
: La normativa europea sulla protezione dei dati.

**KYC**
: *Know Your Customer*, l'adeguata verifica della clientela. Verificare con chi si ha a che fare.

**MiCAR**
: Regolamento europeo che disciplina emittenti di cripto-attività e prestatori di servizi.

**MiFIR**
: Regolamento europeo alla base della segnalazione delle operazioni.

**Prospetto**
: Il documento informativo per un'offerta al pubblico di strumenti finanziari. Esistono esenzioni — comunemente per le offerte riservate a investitori professionali.

**Travel Rule**
: L'obbligo che le informazioni su ordinante e beneficiario viaggino con un trasferimento. L'equivalente cripto di ciò che una banca trasmette con un bonifico.

**§16 eWpG**
: Il contenuto del registro e la sua efficacia giuridica.

**§17(2) eWpG**
: Il contenuto aggiuntivo richiesto per le iscrizioni individuali.

**§19(2) eWpG**
: L'obbligo di fornire estratti di registro ai titolari consumatori.

**§24 eWpG**
: La rettifica del registro — la base dei trasferimenti coattivi.
