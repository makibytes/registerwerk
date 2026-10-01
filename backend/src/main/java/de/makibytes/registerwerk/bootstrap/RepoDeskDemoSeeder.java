package de.makibytes.registerwerk.bootstrap;

import de.makibytes.registerwerk.asset.api.*;
import de.makibytes.registerwerk.customer.api.*;
import de.makibytes.registerwerk.repo.api.*;
import de.makibytes.registerwerk.repo.api.RepoTypes.*;
import de.makibytes.registerwerk.shared.api.RepoDeskCapability;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Creates a small but genuinely multi-counterparty repo book for the local demo. */
@Component
@ConditionalOnProperty(name="registerwerk.seed-demo-data", havingValue="true")
public class RepoDeskDemoSeeder implements ApplicationRunner, Ordered, de.makibytes.registerwerk.shared.DemoOnly {
    private static final String MARKER="[DEMO-REPO]";
    private final RepoDeskCapability capability; private final RepoRfqRepository rfqs;
    private final RepoQuoteRepository quotes; private final LegalEntityRepository entities; private final AssetRepository assets;
    private final RepoDeskParticipantRepository participants;
    public RepoDeskDemoSeeder(RepoDeskCapability capability,RepoRfqRepository rfqs,RepoQuoteRepository quotes,
                              LegalEntityRepository entities,AssetRepository assets,RepoDeskParticipantRepository participants){this.capability=capability;this.rfqs=rfqs;this.quotes=quotes;this.entities=entities;this.assets=assets;this.participants=participants;}
    @Override public int getOrder(){return 30;}
    @Override @Transactional public void run(ApplicationArguments args){
        if(!capability.isReleased()) return;
        var nord=entity("DEMO-NI-001");var rhein=entity("DEMO-RK-001");var aurora=entity("DEMO-AF-001");
        var frankfurt=entity("DEMO-FD-001");var wuerttemberg=entity("DEMO-WI-001");
        // Participation is opt-in (K3). Runs before the marker check so an already-seeded database is repaired too.
        for(LegalEntity demo:List.of(nord,rhein,aurora,frankfurt,wuerttemberg)) optIn(demo);
        List<RepoRfq> seeded=rfqs.findAll().stream().filter(r->r.getNotes()!=null&&r.getNotes().startsWith(MARKER)).toList();
        if(!seeded.isEmpty()){ refreshStale(seeded); return; }
        var green=asset("DEMO-BOND-MC-001");var infra=asset("DEMO-NOTE-AF-001");
        RepoRfq first=rfq(nord,Side.BORROW_CASH,Visibility.TARGETED,green,"500","465000",3.15,200,Set.of(rhein.getId(),aurora.getId()),"Nordbank treasury funding against Green Bond inventory");
        quote(first,rhein,"463500",3.32,225,"Firm subject to same-day DvP affirmation");
        RepoRfq second=rfq(rhein,Side.LEND_CASH,Visibility.BROADCAST,infra,"750","720000",3.05,250,Set.of(),"Rheinische cash desk seeks high-quality tokenised collateral");
        quote(second,frankfurt,"715000",3.18,275,"Can settle at opening date");
        rfq(aurora,Side.BORROW_CASH,Visibility.TARGETED,green,"200","184000",3.45,300,Set.of(rhein.getId(),wuerttemberg.getId()),"Aurora working-capital RFQ");
    }
    /**
     * Demo-only: the seeded RFQs live 18h and their quotes 6h, so a long-lived demo volume would otherwise go stale
     * (empty RFQ book, nothing acceptable). Re-open marker RFQs that merely expired and re-arm their expired quotes.
     * RFQs that were matched or cancelled by a user are left alone. Idempotent.
     */
    private void refreshStale(List<RepoRfq> seeded){
        Instant now=Instant.now();Instant horizon=now.plus(Duration.ofHours(1));
        for(RepoRfq r:seeded){
            boolean stale=(r.getStatus()==RfqStatus.EXPIRED||r.getStatus()==RfqStatus.OPEN)&&r.getExpiresAt()!=null&&r.getExpiresAt().isBefore(horizon);
            if(!stale) continue;
            r.setStatus(RfqStatus.OPEN);r.setExpiresAt(now.plus(Duration.ofHours(18)));
            r.setStartDate(LocalDate.now(ZoneOffset.UTC).plusDays(2));r.setEndDate(LocalDate.now(ZoneOffset.UTC).plusDays(32));
            rfqs.save(r);
            for(RepoQuote q:quotes.findByRfqIdOrderByRepoRateAscCreatedAtAsc(r.getId())){
                if((q.getStatus()==QuoteStatus.ACTIVE||q.getStatus()==QuoteStatus.EXPIRED)&&q.getValidUntil().isBefore(horizon)){
                    q.setStatus(QuoteStatus.ACTIVE);q.setValidUntil(now.plus(Duration.ofHours(6)));quotes.save(q);}
            }
        }
    }
    private RepoRfq rfq(LegalEntity requester,Side side,Visibility visibility,Asset asset,String quantity,String cash,
                        double rate,int haircut,Set<UUID> targets,String note){RepoRfq r=new RepoRfq();r.setRequesterEntityId(requester.getId());
        r.setSide(side);r.setVisibility(visibility);r.setCollateralAssetId(asset.getId());r.setCollateralQuantity(new BigDecimal(quantity));
        r.setCashAmount(new BigDecimal(cash));r.setCashCurrency("EUR");r.setStartDate(LocalDate.now(ZoneOffset.UTC).plusDays(2));
        r.setEndDate(LocalDate.now(ZoneOffset.UTC).plusDays(32));r.setProposedRepoRate(BigDecimal.valueOf(rate));r.setProposedHaircutBps(haircut);
        r.setSettlementMethod(SettlementMethod.DVP);r.setExpiresAt(Instant.now().plus(Duration.ofHours(18)));
        r.setTargetEntityIds(new LinkedHashSet<>(targets));r.setNotes(MARKER+" "+note);return rfqs.save(r);}
    private void quote(RepoRfq rfq,LegalEntity dealer,String cash,double rate,int haircut,String message){RepoQuote q=new RepoQuote();
        q.setRfqId(rfq.getId());q.setQuotingEntityId(dealer.getId());q.setCashAmount(new BigDecimal(cash));q.setRepoRate(BigDecimal.valueOf(rate));
        q.setHaircutBps(haircut);q.setValidUntil(Instant.now().plus(Duration.ofHours(6)));q.setMessage(message);quotes.save(q);}
    /**
     * The demo companies are institutional counterparties (banks, funds, asset managers). The seed never overwrites an
     * explicit classification; it only fills in the gap when a demo company has not been classified yet, and KYC /
     * screening still go through the normal {@code PartyEligibilityGate} at runtime.
     */
    private void optIn(LegalEntity entity){
        if(entity.getClientCategory()==null){entity.setClientCategory(ClientCategory.PROFESSIONAL);
            entity.setClientCategoryClassifiedAt(Instant.now());entities.save(entity);}
        RepoDeskParticipant p=participants.findById(entity.getId()).orElseGet(RepoDeskParticipant::new);
        if(p.getEntityId()!=null&&p.isActive()&&p.isListed()) return;
        p.setEntityId(entity.getId());p.setOptedInAt(Instant.now());p.setOptedOutAt(null);p.setListed(true);participants.save(p);}
    private LegalEntity entity(String number){return entities.findByEntityNumber(number).orElseThrow();}
    private Asset asset(String number){return assets.findByAssetNumber(number).orElseThrow();}
}

