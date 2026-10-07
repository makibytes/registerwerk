package de.makibytes.registerwerk.bootstrap;

import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.Jurisdiction;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.indexer.api.DemoNomineePoolEntity;
import de.makibytes.registerwerk.kyc.api.KycJurisdictionApproval;
import de.makibytes.registerwerk.kyc.api.KycJurisdictionApprovalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Demo-only: creates (idempotently) the legal entity that nominee-pool holder rows are held in and
 * hands its id to {@link DemoNomineePoolEntity}, so lending markets registered by
 * {@link LocalLendingDemoSeeder} are entered as nominee pools of their collateral asset without
 * {@code SYNC_NOMINEE_POOL_ENTITY_ID} being set. Production keeps requiring the property (and keeps
 * its ERROR when it is blank): this bean exists only with {@code registerwerk.seed-demo-data=true}
 * and, as a {@link de.makibytes.registerwerk.shared.DemoOnly}, is refused by the production
 * readiness check.
 *
 * <p>Ordered at 5, i.e. after {@link DemoDataSeeder} (0) and before the lending seeder (20): the
 * market-registered event is handled asynchronously after the registering commit, so the pool
 * entity has to be committed and published to the service before the first market is registered.
 * The entity is ACTIVE and KYC-approved (expiry and DE_EWPG jurisdiction approval like the other
 * demo entities); {@link DemoCoherenceSeeder} adds its {@code kyc_approval_record}.
 */
@Component
@ConditionalOnProperty(name = "registerwerk.seed-demo-data", havingValue = "true")
public class DemoNomineePoolSeeder implements ApplicationRunner, Ordered, de.makibytes.registerwerk.shared.DemoOnly {

    private static final Logger log = LoggerFactory.getLogger(DemoNomineePoolSeeder.class);

    static final String ENTITY_NUMBER = "DEMO-NP-001";
    static final String ENTITY_NAME = "Registerwerk Nominee Pool (Demo)";
    private static final String COMPLIANCE_OFFICER_EMAIL = "compliance.officer@registerwerk-demo.internal";

    private final LegalEntityRepository entities;
    private final KycJurisdictionApprovalRepository jurisdictionApprovals;
    private final AppUserRepository users;
    private final DemoNomineePoolEntity demoNomineePoolEntity;

    public DemoNomineePoolSeeder(LegalEntityRepository entities,
                                 KycJurisdictionApprovalRepository jurisdictionApprovals,
                                 AppUserRepository users,
                                 DemoNomineePoolEntity demoNomineePoolEntity) {
        this.entities = entities;
        this.jurisdictionApprovals = jurisdictionApprovals;
        this.users = users;
        this.demoNomineePoolEntity = demoNomineePoolEntity;
    }

    @Override
    public int getOrder() {
        return 5;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        LegalEntity pool = entities.findByEntityNumber(ENTITY_NUMBER).orElseGet(this::create);
        demoNomineePoolEntity.set(pool.getId());
        log.info("Demo nominee-pool legal entity {} ({}) is the default holder of nominee-pool rows",
                ENTITY_NUMBER, pool.getId());
    }

    private LegalEntity create() {
        LegalEntity e = new LegalEntity();
        e.setEntityNumber(ENTITY_NUMBER);
        e.setCurrentName(ENTITY_NAME);
        // Pool rows are register entries held on behalf of third parties, like an investor's.
        e.setType(EntityType.INVESTOR);
        e.setStatus(EntityStatus.ACTIVE);
        e.setKycStatus(KycStatus.APPROVED);
        e.setKycExpiryDate(LocalDate.now().plusYears(2));
        e.setRegistrationCountry("DE");
        LegalEntity saved = entities.save(e);

        UUID approver = users.findByEmailIgnoreCase(COMPLIANCE_OFFICER_EMAIL).map(AppUser::getId).orElse(null);
        KycJurisdictionApproval approval = new KycJurisdictionApproval();
        approval.setEntityId(saved.getId());
        approval.setJurisdiction(Jurisdiction.DE_EWPG);
        approval.setStatus(KycJurisdictionApproval.Status.APPROVED);
        approval.setApprovedBy(approver);
        approval.setApprovedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        approval.setExpiresAt(LocalDate.now().plusYears(2));
        jurisdictionApprovals.save(approval);
        return saved;
    }
}
