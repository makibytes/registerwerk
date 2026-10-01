package de.makibytes.registerwerk.repo.internal;

import de.makibytes.registerwerk.asset.api.*;
import de.makibytes.registerwerk.customer.api.*;
import de.makibytes.registerwerk.repo.api.*;
import de.makibytes.registerwerk.repo.api.RepoTypes.*;
import jakarta.persistence.EntityNotFoundException;
import de.makibytes.registerwerk.repo.events.RepoAuditEvent;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class RepoDeskService {
    private static final Duration MIN_QUOTE_VALIDITY = Duration.ofMinutes(1);
    private static final Duration MAX_RFQ_VALIDITY = Duration.ofDays(7);

    private final RepoDeskProperties properties;
    private final RepoRfqRepository rfqs;
    private final RepoQuoteRepository quotes;
    private final RepoTradeRepository trades;
    private final RepoLifecycleEventRepository lifecycleEvents;
    private final LegalEntityRepository entities;
    private final AssetRepository assets;
    private final RepoControls controls;
    private final RepoDeskParticipantRepository participants;
    private final RepoCorporateActionListener corporateActions;
    private final AssetBondTermsRepository bondTerms;
    private final AssetHolderRepository holders;
    private final ApplicationEventPublisher publisher;

    public RepoDeskService(RepoDeskProperties properties, RepoRfqRepository rfqs,
                           RepoQuoteRepository quotes, RepoTradeRepository trades,
                           RepoLifecycleEventRepository lifecycleEvents, LegalEntityRepository entities,
                           AssetRepository assets, RepoControls controls, RepoDeskParticipantRepository participants,
                           RepoCorporateActionListener corporateActions, AssetBondTermsRepository bondTerms,
                           AssetHolderRepository holders, ApplicationEventPublisher publisher) {
        this.controls = controls;
        this.participants = participants;
        this.corporateActions = corporateActions;
        this.bondTerms = bondTerms;
        this.holders = holders;
        this.publisher = publisher;
        this.properties = properties;
        this.rfqs = rfqs;
        this.quotes = quotes;
        this.trades = trades;
        this.lifecycleEvents = lifecycleEvents;
        this.entities = entities;
        this.assets = assets;
    }

    @Transactional
    public List<RfqView> listVisible(UUID entityId) {
        properties.requireReleased();
        controls.requireParticipant(entityId, "the repo desk");
        return rfqs.findVisibleTo(entityId).stream().map(rfq -> view(rfq, entityId)).toList();
    }

    @Transactional
    public RfqView get(UUID rfqId, UUID entityId) {
        properties.requireReleased();
        controls.requireParticipant(entityId, "the repo desk");
        RepoRfq rfq = requireRfq(rfqId);
        requireVisible(rfq, entityId);
        return view(rfq, entityId);
    }

    @Transactional
    public RfqView create(UUID entityId, UUID userId, CreateRfq command) {
        properties.requireReleased();
        controls.requireParticipant(entityId, "a repo RFQ");
        LegalEntity requester = requireActiveEntity(entityId);
        Asset asset = assets.findById(command.collateralAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Collateral asset not found"));
        if (asset.getStatus() != AssetStatus.ISSUED) {
            throw new IllegalStateException("Only issued securities can be used in a repo RFQ");
        }
        String currency = command.cashCurrency().toUpperCase(Locale.ROOT);
        CurrencyRules.currency(currency);
        CurrencyRules.requireMinorUnitScale(currency, command.cashAmount(), "Cash amount");
        validateDates(command.startDate(), command.endDate());
        controls.requireTermWithinCollateralLife(asset.getId(), command.endDate());
        Instant now = Instant.now();
        if (command.expiresAt().isAfter(now.plus(MAX_RFQ_VALIDITY))) {
            throw new IllegalArgumentException("RFQ validity cannot exceed seven days");
        }
        if (command.expiresAt().isAfter(command.startDate().atStartOfDay(ZoneOffset.UTC).toInstant())) {
            throw new IllegalArgumentException("RFQ must expire before the repo start date");
        }
        if (command.side() == Side.BORROW_CASH) {
            controls.requireHolding(entityId, asset.getId(), command.collateralQuantity());
        }

        Set<UUID> targets = command.targetEntityIds() == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(command.targetEntityIds());
        targets.remove(entityId);
        if (command.visibility() == Visibility.TARGETED && targets.isEmpty()) {
            throw new IllegalArgumentException("A targeted RFQ needs at least one counterparty");
        }
        if (command.visibility() == Visibility.BROADCAST && !targets.isEmpty()) {
            throw new IllegalArgumentException("A broadcast RFQ cannot contain target counterparties");
        }
        targets.forEach(target -> controls.requireParticipantCounterparty(target, "a repo RFQ"));

        RepoRfq rfq = new RepoRfq();
        rfq.setRequesterEntityId(requester.getId());
        rfq.setRequesterUserId(userId);
        rfq.setSide(command.side());
        rfq.setVisibility(command.visibility());
        rfq.setCollateralAssetId(asset.getId());
        rfq.setCollateralQuantity(command.collateralQuantity());
        rfq.setCashAmount(command.cashAmount());
        rfq.setCashCurrency(currency);
        rfq.setStartDate(command.startDate());
        rfq.setEndDate(command.endDate());
        rfq.setProposedRepoRate(command.proposedRepoRate());
        rfq.setProposedHaircutBps(command.proposedHaircutBps());
        rfq.setSettlementMethod(command.settlementMethod());
        rfq.setExpiresAt(command.expiresAt());
        rfq.setNotes(trimToNull(command.notes()));
        rfq.setTargetEntityIds(targets);
        RepoRfq saved = rfqs.save(rfq);
        audit("RepoRfq", saved.getId(), "RFQ_CREATED", userId, Map.of("side", command.side().name(),
                "assetId", asset.getId().toString(), "currency", currency));
        return view(saved, entityId);
    }

    @Transactional
    public RfqView cancel(UUID rfqId, UUID entityId) {
        properties.requireReleased();
        RepoRfq rfq = rfqs.findByIdForUpdate(rfqId)
                .orElseThrow(() -> new EntityNotFoundException("Repo RFQ not found"));
        requireOwner(rfq, entityId);
        refreshExpiry(rfq);
        if (rfq.getStatus() != RfqStatus.OPEN) {
            throw new IllegalStateException("Only an open RFQ can be cancelled");
        }
        rfq.setStatus(RfqStatus.CANCELLED);
        quotes.findByRfqIdOrderByRepoRateAscCreatedAtAsc(rfqId).stream()
                .filter(quote -> quote.getStatus() == QuoteStatus.ACTIVE)
                .forEach(quote -> quote.setStatus(QuoteStatus.REJECTED));
        audit("RepoRfq", rfqId, "RFQ_CANCELLED", rfq.getRequesterUserId(), Map.of());
        return view(rfq, entityId);
    }

    /** Every submission is a new immutable row (version n+1); the counterparty's previous ACTIVE quote is SUPERSEDED. */
    @Transactional
    public RfqView submitQuote(UUID rfqId, UUID entityId, UUID userId, SubmitQuote command) {
        properties.requireReleased();
        controls.requireParticipant(entityId, "a repo quote");
        requireActiveEntity(entityId);
        RepoRfq rfq = rfqs.findByIdForUpdate(rfqId)
                .orElseThrow(() -> new EntityNotFoundException("Repo RFQ not found"));
        refreshExpiry(rfq);
        requireMayQuote(rfq, entityId);
        if (rfq.getStatus() != RfqStatus.OPEN) throw new IllegalStateException("RFQ is no longer open");
        if (command.validUntil().isBefore(Instant.now().plus(MIN_QUOTE_VALIDITY))) {
            throw new IllegalArgumentException("Quote must remain valid for at least one minute");
        }
        if (command.validUntil().isAfter(rfq.getExpiresAt())) {
            throw new IllegalArgumentException("Quote cannot remain valid after the RFQ expires");
        }
        CurrencyRules.requireMinorUnitScale(rfq.getCashCurrency(), command.cashAmount(), "Quote cash amount");
        if (rfq.getSide() == Side.LEND_CASH) {
            // the quoting dealer would be the cash borrower and must be able to deliver the collateral
            controls.requireHolding(entityId, rfq.getCollateralAssetId(), rfq.getCollateralQuantity());
        }

        quotes.findByRfqIdAndQuotingEntityIdAndStatus(rfqId, entityId, QuoteStatus.ACTIVE).ifPresent(previous -> {
            previous.setStatus(QuoteStatus.SUPERSEDED);
            quotes.saveAndFlush(previous); // free the partial unique index before the new row is inserted
        });
        RepoQuote quote = new RepoQuote();
        quote.setRfqId(rfqId);
        quote.setQuotingEntityId(entityId);
        quote.setQuotingUserId(userId);
        quote.setQuoteVersion(quotes.maxVersion(rfqId, entityId) + 1);
        quote.setCashAmount(command.cashAmount());
        quote.setRepoRate(command.repoRate());
        quote.setHaircutBps(command.haircutBps());
        quote.setValidUntil(command.validUntil());
        quote.setMessage(trimToNull(command.message()));
        quote.setStatus(QuoteStatus.ACTIVE);
        RepoQuote saved = quotes.save(quote);
        audit("RepoRfq", rfqId, "QUOTE_SUBMITTED", userId, Map.of("quoteId", String.valueOf(saved.getId()),
                "version", saved.getQuoteVersion(), "quotingEntityId", entityId.toString()));
        return view(rfq, entityId);
    }

    @Transactional
    public RfqView withdrawQuote(UUID rfqId, UUID entityId) {
        properties.requireReleased();
        // same RFQ lock as acceptQuote/submitQuote: a withdraw racing an accept must not leave the accepted quote WITHDRAWN
        RepoRfq rfq = rfqs.findByIdForUpdate(rfqId)
                .orElseThrow(() -> new EntityNotFoundException("Repo RFQ not found"));
        requireVisible(rfq, entityId);
        RepoQuote quote = quotes.findByRfqIdAndQuotingEntityIdAndStatus(rfqId, entityId, QuoteStatus.ACTIVE)
                .orElseThrow(() -> new EntityNotFoundException("You have no active quote on this RFQ"));
        quote.setStatus(QuoteStatus.WITHDRAWN);
        audit("RepoRfq", rfqId, "QUOTE_WITHDRAWN", quote.getQuotingUserId(), Map.of("quoteId", quote.getId().toString()));
        return view(rfq, entityId);
    }

    /**
     * Accepts exactly the terms the requester saw: {@code termsHash} must equal the server-side digest of
     * the quote row, which is immutable (a re-quote is a new version and supersedes this one).
     */
    @Transactional
    public RfqView acceptQuote(UUID rfqId, UUID quoteId, UUID entityId, String termsHash) {
        properties.requireReleased();
        RepoRfq rfq = rfqs.findByIdForUpdate(rfqId)
                .orElseThrow(() -> new EntityNotFoundException("Repo RFQ not found"));
        requireOwner(rfq, entityId);
        controls.requireParticipant(entityId, "accepting a repo quote");
        refreshExpiry(rfq);
        if (rfq.getStatus() != RfqStatus.OPEN) throw new IllegalStateException("RFQ is no longer open");
        RepoQuote accepted = quotes.findById(quoteId)
                .filter(quote -> rfqId.equals(quote.getRfqId()))
                .orElseThrow(() -> new EntityNotFoundException("Quote not found for this RFQ"));
        refreshQuoteExpiry(accepted);
        if (accepted.getStatus() == QuoteStatus.SUPERSEDED) {
            RepoQuote current = quotes.findByRfqIdAndQuotingEntityIdAndStatus(rfqId, accepted.getQuotingEntityId(), QuoteStatus.ACTIVE).orElse(null);
            throw new IllegalStateException("Quote version " + accepted.getQuoteVersion() + " was superseded"
                    + (current == null ? "" : " by version " + current.getQuoteVersion() + " (termsHash "
                    + RepoTerms.hash(rfq, current) + ")") + "; review the current terms");
        }
        if (accepted.getStatus() != QuoteStatus.ACTIVE) {
            throw new IllegalStateException("Quote is no longer active");
        }
        String expected = RepoTerms.hash(rfq, accepted);
        if (termsHash == null || !expected.equalsIgnoreCase(termsHash.trim())) {
            throw new IllegalStateException("The quote terms changed (current termsHash " + expected
                    + ", version " + accepted.getQuoteVersion() + "); review the current terms");
        }
        UUID quoterId = accepted.getQuotingEntityId();
        controls.requireParticipantCounterparty(quoterId, "a repo trade");
        boolean requesterBorrows = rfq.getSide() == Side.BORROW_CASH;
        UUID borrowerId = requesterBorrows ? entityId : quoterId;
        LegalEntity requesterEntity = requireEntity(entityId);
        LegalEntity quoterEntity = requireEntity(quoterId);
        controls.requireLei(requesterEntity);
        controls.requireLei(quoterEntity);
        controls.requireHolding(borrowerId, rfq.getCollateralAssetId(), rfq.getCollateralQuantity());
        controls.requireTermWithinCollateralLife(rfq.getCollateralAssetId(), rfq.getEndDate());
        if (rfq.getStartDate().isBefore(LocalDate.now(ZoneOffset.UTC))) {
            throw new IllegalStateException("The repo start date has passed");
        }

        rfq.setStatus(RfqStatus.MATCHED);
        for (RepoQuote quote : quotes.findByRfqIdOrderByRepoRateAscCreatedAtAsc(rfqId)) {
            if (quote.getId().equals(quoteId)) quote.setStatus(QuoteStatus.ACCEPTED);
            else if (quote.getStatus() == QuoteStatus.ACTIVE) quote.setStatus(QuoteStatus.REJECTED);
        }
        RepoTrade trade = createTrade(rfq, accepted, entityId, requesterEntity, expected);
        lifecycleEvents.save(event(trade.getId(), LifecycleEventType.TRADE_CONFIRMED, entityId,
                rfq.getRequesterUserId(), null, null, null, null, "Quote accepted; trade terms fixed"));
        corporateActions.recordExisting(trade);
        audit("RepoTrade", trade.getId(), "QUOTE_ACCEPTED", rfq.getRequesterUserId(), Map.of(
                "rfqId", rfqId.toString(), "quoteId", quoteId.toString(), "termsHash", expected,
                "uti", trade.getUti()));
        return view(rfq, entityId);
    }

    @Transactional(readOnly = true)
    public ParticipationView participation(UUID entityId) {
        properties.requireReleased();
        requireEntity(entityId);
        return participationView(entityId);
    }

    /** Opt in (or update the directory listing). Requires an eligible professional / ECP company. */
    @Transactional
    public ParticipationView optIn(UUID entityId, UUID userId, boolean listed) {
        properties.requireReleased();
        LegalEntity entity = requireActiveEntity(entityId);
        controls.requireEligible(entity, "the repo desk");
        RepoDeskParticipant participant = participants.findById(entityId).orElseGet(RepoDeskParticipant::new);
        participant.setEntityId(entityId);
        if (participant.getOptedOutAt() != null || participant.getOptedInBy() == null) {
            participant.setOptedInAt(Instant.now());
            participant.setOptedInBy(userId);
        }
        participant.setOptedOutAt(null);
        participant.setListed(listed);
        participants.save(participant);
        audit("LegalEntity", entityId, "PARTICIPATION_OPT_IN", userId, Map.of("listed", listed));
        return participationView(entityId);
    }

    @Transactional
    public ParticipationView optOut(UUID entityId, UUID userId) {
        properties.requireReleased();
        requireEntity(entityId);
        participants.findById(entityId).ifPresent(participant -> {
            participant.setOptedOutAt(Instant.now());
            participant.setListed(false);
            participants.save(participant);
        });
        audit("LegalEntity", entityId, "PARTICIPATION_OPT_OUT", userId, Map.of());
        return participationView(entityId);
    }

    /** Directory: only opted-in, listed, currently eligible professional companies other than the caller; name and LEI only. */
    @Transactional(readOnly = true)
    public List<CounterpartyView> counterparties(UUID entityId) {
        properties.requireReleased();
        controls.requireParticipant(entityId, "the repo desk");
        return participants.findByListedTrueAndOptedOutAtIsNull().stream()
                .map(RepoDeskParticipant::getEntityId)
                .filter(id -> !id.equals(entityId))
                .map(id -> entities.findById(id).orElse(null))
                .filter(Objects::nonNull)
                .filter(entity -> entity.getStatus() == EntityStatus.ACTIVE)
                .filter(entity -> controls.eligibilityReasons(entity.getId()).isEmpty())
                .sorted(Comparator.comparing(LegalEntity::getCurrentName))
                .map(entity -> new CounterpartyView(entity.getId(), entity.getCurrentName(), entity.getLeiCode()))
                .toList();
    }

    /** Collateral the caller actually holds on the register (never other tenants' private placements). */
    @Transactional(readOnly = true)
    public List<CollateralView> collateral(UUID entityId) {
        properties.requireReleased();
        controls.requireParticipant(entityId, "the repo desk");
        List<CollateralView> result = new ArrayList<>();
        for (UUID assetId : holders.findDistinctActiveAssetIdsByInvestorId(entityId)) {
            Asset asset = assets.findById(assetId).orElse(null);
            if (asset == null || asset.getStatus() != AssetStatus.ISSUED) continue;
            BigDecimal held = holders.sumActiveNominalByInvestorIdAndAssetId(entityId, assetId);
            if (held.signum() <= 0) continue;
            result.add(new CollateralView(asset.getId(), asset.getName(), asset.getIsin(), asset.getAssetNumber(),
                    held, controls.availableHolding(entityId, assetId),
                    bondTerms.findById(assetId).map(t -> t.getMaturityDate()).orElse(null)));
        }
        result.sort(Comparator.comparing(CollateralView::name));
        return result;
    }

    private ParticipationView participationView(UUID entityId) {
        RepoDeskParticipant p = participants.findById(entityId).orElse(null);
        List<String> reasons = controls.eligibilityReasons(entityId);
        return new ParticipationView(p != null && p.isActive(), p != null && p.isActive() && p.isListed(),
                p == null ? null : p.getOptedInAt(), reasons);
    }

    private RfqView view(RepoRfq rfq, UUID viewerEntityId) {
        refreshExpiry(rfq);
        Asset asset = assets.findById(rfq.getCollateralAssetId())
                .orElseThrow(() -> new EntityNotFoundException("RFQ collateral asset not found"));
        LegalEntity requester = entities.findById(rfq.getRequesterEntityId())
                .orElseThrow(() -> new EntityNotFoundException("RFQ requester not found"));
        boolean mine = rfq.getRequesterEntityId().equals(viewerEntityId);
        List<QuoteView> visibleQuotes = quotes.findByRfqIdOrderByRepoRateAscCreatedAtAsc(rfq.getId()).stream()
                .peek(this::refreshQuoteExpiry)
                .filter(quote -> mine || quote.getQuotingEntityId().equals(viewerEntityId))
                .map(quote -> quoteView(rfq, quote)).toList();
        UUID tradeId = trades.findByRfqId(rfq.getId())
                .filter(t -> mine || t.getCashBorrowerEntityId().equals(viewerEntityId) || t.getCashLenderEntityId().equals(viewerEntityId))
                .map(RepoTrade::getId).orElse(null);
        // other invitees must not learn who else was invited
        Set<UUID> targets = mine ? Set.copyOf(rfq.getTargetEntityIds()) : Set.of();
        return new RfqView(rfq, targets, asset.getName(), asset.getIsin(), requester.getCurrentName(),
                mine, mayQuote(rfq, viewerEntityId), tradeId, visibleQuotes);
    }

    private RepoTrade createTrade(RepoRfq rfq, RepoQuote accepted, UUID actorEntityId, LegalEntity requester, String termsHash) {
        if (trades.findByRfqId(rfq.getId()).isPresent()) {
            throw new IllegalStateException("A trade already exists for this RFQ");
        }
        boolean requesterBorrows = rfq.getSide() == Side.BORROW_CASH;
        RepoTrade trade = new RepoTrade();
        trade.setRfqId(rfq.getId());
        trade.setAcceptedQuoteId(accepted.getId());
        trade.setAcceptedQuoteVersion(accepted.getQuoteVersion());
        trade.setTermsHash(termsHash);
        trade.setCashBorrowerEntityId(requesterBorrows ? actorEntityId : accepted.getQuotingEntityId());
        trade.setCashLenderEntityId(requesterBorrows ? accepted.getQuotingEntityId() : actorEntityId);
        trade.setCollateralAssetId(rfq.getCollateralAssetId());
        trade.setCollateralQuantity(rfq.getCollateralQuantity());
        trade.setCashAmount(accepted.getCashAmount());
        trade.setCashCurrency(rfq.getCashCurrency());
        trade.setRepoRate(accepted.getRepoRate());
        trade.setHaircutBps(accepted.getHaircutBps());
        trade.setStartDate(rfq.getStartDate());
        trade.setEndDate(rfq.getEndDate());
        trade.setSettlementMethod(rfq.getSettlementMethod());
        int basis = CurrencyRules.dayCountBasis(rfq.getCashCurrency());
        trade.setDayCountBasis(basis);
        trade.setRepurchaseAmount(CurrencyRules.repurchaseAmount(rfq.getCashCurrency(), accepted.getCashAmount(),
                accepted.getRepoRate(), rfq.getStartDate(), rfq.getEndDate(), basis));
        RepoTrade saved = trades.save(trade);
        // ISO 23897 shape: LEI of the generating (requesting) party + 32 hex characters from the trade id
        saved.setUti(requester.getLeiCode().trim().toUpperCase(Locale.ROOT)
                + saved.getId().toString().replace("-", "").toUpperCase(Locale.ROOT));
        return saved;
    }

    private void audit(String subjectType, UUID subjectId, String action, UUID actorId, Map<String, Object> details) {
        publisher.publishEvent(RepoAuditEvent.of(subjectType, subjectId, action, actorId, details));
    }

    private RepoLifecycleEvent event(UUID tradeId, LifecycleEventType type, UUID entityId, UUID userId,
                                     BigDecimal amount, UUID assetId, BigDecimal quantity,
                                     String reference, String note) {
        RepoLifecycleEvent event = new RepoLifecycleEvent();
        event.setRepoTradeId(tradeId); event.setEventType(type); event.setActorEntityId(entityId);
        event.setActorUserId(userId); event.setAmount(amount); event.setAssetId(assetId);
        event.setQuantity(quantity); event.setReference(trimToNull(reference)); event.setNote(trimToNull(note));
        return event;
    }

    private QuoteView quoteView(RepoRfq rfq, RepoQuote quote) {
        String name = entities.findById(quote.getQuotingEntityId())
                .map(LegalEntity::getCurrentName).orElse("Unknown counterparty");
        return new QuoteView(quote, name, RepoTerms.hash(rfq, quote));
    }

    private void refreshExpiry(RepoRfq rfq) {
        if (rfq.getStatus() == RfqStatus.OPEN && !rfq.getExpiresAt().isAfter(Instant.now())) {
            rfq.setStatus(RfqStatus.EXPIRED);
        }
    }

    private void refreshQuoteExpiry(RepoQuote quote) {
        if (quote.getStatus() == QuoteStatus.ACTIVE && !quote.getValidUntil().isAfter(Instant.now())) {
            quote.setStatus(QuoteStatus.EXPIRED);
        }
    }

    private void requireVisible(RepoRfq rfq, UUID entityId) {
        if (rfq.getRequesterEntityId().equals(entityId)) return;
        if (rfq.getVisibility() == Visibility.BROADCAST) return;
        if (rfq.getTargetEntityIds().contains(entityId)) return;
        throw new AccessDeniedException("RFQ is not visible to this company");
    }

    private void requireMayQuote(RepoRfq rfq, UUID entityId) {
        if (!mayQuote(rfq, entityId)) throw new AccessDeniedException("Company cannot quote this RFQ");
    }

    private boolean mayQuote(RepoRfq rfq, UUID entityId) {
        return !rfq.getRequesterEntityId().equals(entityId)
                && (rfq.getVisibility() == Visibility.BROADCAST || rfq.getTargetEntityIds().contains(entityId))
                && rfq.getStatus() == RfqStatus.OPEN && rfq.getExpiresAt().isAfter(Instant.now());
    }

    private void requireOwner(RepoRfq rfq, UUID entityId) {
        if (!rfq.getRequesterEntityId().equals(entityId)) {
            throw new AccessDeniedException("Only the requesting company can change this RFQ");
        }
    }

    private RepoRfq requireRfq(UUID id) {
        return rfqs.findById(id).orElseThrow(() -> new EntityNotFoundException("Repo RFQ not found"));
    }

    private LegalEntity requireEntity(UUID id) {
        if (id == null) throw new AccessDeniedException("An active company context is required");
        return entities.findById(id).orElseThrow(() -> new AccessDeniedException("Company context not found"));
    }

    private LegalEntity requireActiveEntity(UUID id) {
        LegalEntity entity = requireEntity(id);
        if (entity.getStatus() != EntityStatus.ACTIVE) {
            throw new AccessDeniedException("Company must be active to use the Repo Desk");
        }
        return entity;
    }

    private void validateDates(LocalDate startDate, LocalDate endDate) {
        if (startDate.isBefore(LocalDate.now(ZoneOffset.UTC))) {
            throw new IllegalArgumentException("Repo start date cannot be in the past");
        }
        if (!endDate.isAfter(startDate)) {
            throw new IllegalArgumentException("Repo end date must be after its start date");
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    public record CreateRfq(Side side, Visibility visibility, UUID collateralAssetId,
                            BigDecimal collateralQuantity, BigDecimal cashAmount, String cashCurrency,
                            LocalDate startDate, LocalDate endDate, BigDecimal proposedRepoRate,
                            Integer proposedHaircutBps, SettlementMethod settlementMethod,
                            Instant expiresAt, Set<UUID> targetEntityIds, String notes) {}
    public record SubmitQuote(BigDecimal cashAmount, BigDecimal repoRate, int haircutBps,
                              Instant validUntil, String message) {}
    public record RfqView(RepoRfq rfq, Set<UUID> targetEntityIds,
                          String collateralAssetName, String collateralIsin,
                          String requesterName, boolean mine, boolean canQuote, UUID tradeId,
                          List<QuoteView> quotes) {}
    public record QuoteView(RepoQuote quote, String quotingEntityName, String termsHash) {}
    public record ParticipationView(boolean participating, boolean listed, Instant optedInAt, List<String> eligibilityIssues) {}
    public record CounterpartyView(UUID id, String name, String lei) {}
    public record CollateralView(UUID id, String name, String isin, String assetNumber,
                                 BigDecimal heldQuantity, BigDecimal availableQuantity, LocalDate maturityDate) {}
}
