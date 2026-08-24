package de.makibytes.registerwerk.repo.internal;

import de.makibytes.registerwerk.asset.api.*;
import de.makibytes.registerwerk.customer.api.*;
import de.makibytes.registerwerk.repo.api.*;
import de.makibytes.registerwerk.repo.api.RepoTypes.*;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    public RepoDeskService(RepoDeskProperties properties, RepoRfqRepository rfqs,
                           RepoQuoteRepository quotes, RepoTradeRepository trades,
                           RepoLifecycleEventRepository lifecycleEvents, LegalEntityRepository entities,
                           AssetRepository assets) {
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
        requireEntity(entityId);
        return rfqs.findVisibleTo(entityId).stream().map(rfq -> view(rfq, entityId)).toList();
    }

    @Transactional
    public RfqView get(UUID rfqId, UUID entityId) {
        properties.requireReleased();
        requireEntity(entityId);
        RepoRfq rfq = requireRfq(rfqId);
        requireVisible(rfq, entityId);
        return view(rfq, entityId);
    }

    @Transactional
    public RfqView create(UUID entityId, UUID userId, CreateRfq command) {
        properties.requireReleased();
        LegalEntity requester = requireActiveEntity(entityId);
        Asset asset = assets.findById(command.collateralAssetId())
                .orElseThrow(() -> new EntityNotFoundException("Collateral asset not found"));
        if (asset.getStatus() != AssetStatus.ISSUED) {
            throw new IllegalStateException("Only issued securities can be used in a repo RFQ");
        }
        validateDates(command.startDate(), command.endDate());
        Instant now = Instant.now();
        if (command.expiresAt().isAfter(now.plus(MAX_RFQ_VALIDITY))) {
            throw new IllegalArgumentException("RFQ validity cannot exceed seven days");
        }
        if (command.expiresAt().isAfter(command.startDate().atStartOfDay(ZoneOffset.UTC).toInstant())) {
            throw new IllegalArgumentException("RFQ must expire before the repo start date");
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
        targets.forEach(this::requireActiveEntity);

        RepoRfq rfq = new RepoRfq();
        rfq.setRequesterEntityId(requester.getId());
        rfq.setRequesterUserId(userId);
        rfq.setSide(command.side());
        rfq.setVisibility(command.visibility());
        rfq.setCollateralAssetId(asset.getId());
        rfq.setCollateralQuantity(command.collateralQuantity());
        rfq.setCashAmount(command.cashAmount());
        rfq.setCashCurrency(command.cashCurrency().toUpperCase(Locale.ROOT));
        rfq.setStartDate(command.startDate());
        rfq.setEndDate(command.endDate());
        rfq.setProposedRepoRate(command.proposedRepoRate());
        rfq.setProposedHaircutBps(command.proposedHaircutBps());
        rfq.setSettlementMethod(command.settlementMethod());
        rfq.setExpiresAt(command.expiresAt());
        rfq.setNotes(trimToNull(command.notes()));
        rfq.setTargetEntityIds(targets);
        return view(rfqs.save(rfq), entityId);
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
        return view(rfq, entityId);
    }

    @Transactional
    public RfqView submitQuote(UUID rfqId, UUID entityId, UUID userId, SubmitQuote command) {
        properties.requireReleased();
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

        RepoQuote quote = quotes.findByRfqIdAndQuotingEntityId(rfqId, entityId).orElseGet(RepoQuote::new);
        if (quote.getStatus() != null && quote.getStatus() != QuoteStatus.ACTIVE
                && quote.getStatus() != QuoteStatus.WITHDRAWN && quote.getId() != null) {
            throw new IllegalStateException("This counterparty's quote can no longer be replaced");
        }
        quote.setRfqId(rfqId);
        quote.setQuotingEntityId(entityId);
        quote.setQuotingUserId(userId);
        quote.setCashAmount(command.cashAmount());
        quote.setRepoRate(command.repoRate());
        quote.setHaircutBps(command.haircutBps());
        quote.setValidUntil(command.validUntil());
        quote.setMessage(trimToNull(command.message()));
        quote.setStatus(QuoteStatus.ACTIVE);
        quotes.save(quote);
        return view(rfq, entityId);
    }

    @Transactional
    public RfqView withdrawQuote(UUID rfqId, UUID entityId) {
        properties.requireReleased();
        RepoRfq rfq = requireRfq(rfqId);
        requireVisible(rfq, entityId);
        RepoQuote quote = quotes.findByRfqIdAndQuotingEntityId(rfqId, entityId)
                .orElseThrow(() -> new EntityNotFoundException("Your quote was not found"));
        if (quote.getStatus() != QuoteStatus.ACTIVE) {
            throw new IllegalStateException("Only an active quote can be withdrawn");
        }
        quote.setStatus(QuoteStatus.WITHDRAWN);
        return view(rfq, entityId);
    }

    @Transactional
    public RfqView acceptQuote(UUID rfqId, UUID quoteId, UUID entityId) {
        properties.requireReleased();
        RepoRfq rfq = rfqs.findByIdForUpdate(rfqId)
                .orElseThrow(() -> new EntityNotFoundException("Repo RFQ not found"));
        requireOwner(rfq, entityId);
        refreshExpiry(rfq);
        if (rfq.getStatus() != RfqStatus.OPEN) throw new IllegalStateException("RFQ is no longer open");
        RepoQuote accepted = quotes.findById(quoteId)
                .filter(quote -> rfqId.equals(quote.getRfqId()))
                .orElseThrow(() -> new EntityNotFoundException("Quote not found for this RFQ"));
        refreshQuoteExpiry(accepted);
        if (accepted.getStatus() != QuoteStatus.ACTIVE) {
            throw new IllegalStateException("Quote is no longer active");
        }
        rfq.setStatus(RfqStatus.MATCHED);
        for (RepoQuote quote : quotes.findByRfqIdOrderByRepoRateAscCreatedAtAsc(rfqId)) {
            quote.setStatus(quote.getId().equals(quoteId) ? QuoteStatus.ACCEPTED : QuoteStatus.REJECTED);
        }
        RepoTrade trade = createTrade(rfq, accepted, entityId);
        lifecycleEvents.save(event(trade.getId(), LifecycleEventType.TRADE_CONFIRMED, entityId,
                rfq.getRequesterUserId(), null, null, null, null, "Quote accepted; trade terms fixed"));
        return view(rfq, entityId);
    }

    @Transactional(readOnly = true)
    public List<CounterpartyView> counterparties(UUID entityId) {
        properties.requireReleased();
        requireEntity(entityId);
        return entities.findAll().stream()
                .filter(entity -> entity.getStatus() == EntityStatus.ACTIVE)
                .filter(entity -> !entity.getId().equals(entityId))
                .sorted(Comparator.comparing(LegalEntity::getCurrentName))
                .map(entity -> new CounterpartyView(entity.getId(), entity.getCurrentName(), entity.getLeiCode()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CollateralView> collateral() {
        properties.requireReleased();
        return assets.findAll().stream().filter(asset -> asset.getStatus() == AssetStatus.ISSUED)
                .sorted(Comparator.comparing(Asset::getName))
                .map(asset -> new CollateralView(asset.getId(), asset.getName(), asset.getIsin(), asset.getAssetNumber()))
                .toList();
    }

    private RfqView view(RepoRfq rfq, UUID viewerEntityId) {
        refreshExpiry(rfq);
        Asset asset = assets.findById(rfq.getCollateralAssetId())
                .orElseThrow(() -> new EntityNotFoundException("RFQ collateral asset not found"));
        LegalEntity requester = entities.findById(rfq.getRequesterEntityId())
                .orElseThrow(() -> new EntityNotFoundException("RFQ requester not found"));
        List<QuoteView> visibleQuotes = quotes.findByRfqIdOrderByRepoRateAscCreatedAtAsc(rfq.getId()).stream()
                .peek(this::refreshQuoteExpiry)
                .filter(quote -> rfq.getRequesterEntityId().equals(viewerEntityId)
                        || quote.getQuotingEntityId().equals(viewerEntityId))
                .map(this::quoteView).toList();
        UUID tradeId = trades.findByRfqId(rfq.getId()).map(RepoTrade::getId).orElse(null);
        return new RfqView(rfq, Set.copyOf(rfq.getTargetEntityIds()), asset.getName(), asset.getIsin(), requester.getCurrentName(),
                rfq.getRequesterEntityId().equals(viewerEntityId), mayQuote(rfq, viewerEntityId),
                tradeId, visibleQuotes);
    }

    private RepoTrade createTrade(RepoRfq rfq, RepoQuote accepted, UUID actorEntityId) {
        if (trades.findByRfqId(rfq.getId()).isPresent()) {
            throw new IllegalStateException("A trade already exists for this RFQ");
        }
        boolean requesterBorrows = rfq.getSide() == Side.BORROW_CASH;
        RepoTrade trade = new RepoTrade();
        trade.setRfqId(rfq.getId());
        trade.setAcceptedQuoteId(accepted.getId());
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
        long days = Duration.between(rfq.getStartDate().atStartOfDay(),
                rfq.getEndDate().atStartOfDay()).toDays();
        BigDecimal interest = accepted.getCashAmount()
                .multiply(accepted.getRepoRate())
                .multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(36_000), 18, RoundingMode.HALF_UP);
        trade.setRepurchaseAmount(accepted.getCashAmount().add(interest));
        return trades.save(trade);
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

    private QuoteView quoteView(RepoQuote quote) {
        String name = entities.findById(quote.getQuotingEntityId())
                .map(LegalEntity::getCurrentName).orElse("Unknown counterparty");
        return new QuoteView(quote, name);
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
    public record QuoteView(RepoQuote quote, String quotingEntityName) {}
    public record CounterpartyView(UUID id, String name, String lei) {}
    public record CollateralView(UUID id, String name, String isin, String assetNumber) {}
}
