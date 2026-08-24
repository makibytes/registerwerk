package de.makibytes.registerwerk.bootstrap;

import de.makibytes.registerwerk.asset.api.*;
import de.makibytes.registerwerk.customer.api.*;
import de.makibytes.registerwerk.repo.api.*;
import de.makibytes.registerwerk.repo.api.RepoTypes.*;
import de.makibytes.registerwerk.repo.api.RepoDeskCapability;
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
public class RepoDeskDemoSeeder implements ApplicationRunner, Ordered {
    private static final String MARKER="[DEMO-REPO]";
    private final RepoDeskCapability capability; private final RepoRfqRepository rfqs;
    private final RepoQuoteRepository quotes; private final LegalEntityRepository entities; private final AssetRepository assets;
    public RepoDeskDemoSeeder(RepoDeskCapability capability,RepoRfqRepository rfqs,RepoQuoteRepository quotes,
                              LegalEntityRepository entities,AssetRepository assets){this.capability=capability;this.rfqs=rfqs;this.quotes=quotes;this.entities=entities;this.assets=assets;}
    @Override public int getOrder(){return 30;}
    @Override @Transactional public void run(ApplicationArguments args){
        if(!capability.isReleased() || rfqs.findAll().stream().anyMatch(r->r.getNotes()!=null&&r.getNotes().startsWith(MARKER))) return;
        var nord=entity("DEMO-NI-001");var rhein=entity("DEMO-RK-001");var aurora=entity("DEMO-AF-001");
        var frankfurt=entity("DEMO-FD-001");var wuerttemberg=entity("DEMO-WI-001");
        var green=asset("DEMO-BOND-MC-001");var infra=asset("DEMO-NOTE-AF-001");
        RepoRfq first=rfq(nord,Side.BORROW_CASH,Visibility.TARGETED,green,"500","465000",3.15,200,Set.of(rhein.getId(),aurora.getId()),"Nordbank treasury funding against Green Bond inventory");
        quote(first,rhein,"463500",3.32,225,"Firm subject to same-day DvP affirmation");
        RepoRfq second=rfq(rhein,Side.LEND_CASH,Visibility.BROADCAST,infra,"750","720000",3.05,250,Set.of(),"Rheinische cash desk seeks high-quality tokenised collateral");
        quote(second,frankfurt,"715000",3.18,275,"Can settle at opening date");
        rfq(aurora,Side.BORROW_CASH,Visibility.TARGETED,green,"200","184000",3.45,300,Set.of(rhein.getId(),wuerttemberg.getId()),"Aurora working-capital RFQ");
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
    private LegalEntity entity(String number){return entities.findByEntityNumber(number).orElseThrow();}
    private Asset asset(String number){return assets.findByAssetNumber(number).orElseThrow();}
}

