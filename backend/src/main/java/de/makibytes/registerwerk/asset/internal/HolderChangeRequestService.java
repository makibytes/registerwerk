package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.events.HolderChangeRequestExecutedEvent;
import de.makibytes.registerwerk.asset.events.HolderChangeRequestRejectedEvent;
import de.makibytes.registerwerk.asset.events.HolderChangeRequestedEvent;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T3-13: the issuer's side of register-entry changes. An issuer may only <em>request</em> an entry or
 * a §17(2) change (recording the instruction it relays); the operator executes the request through
 * {@link HolderService} (which records the instruction and before/after values) or rejects it.
 */
@Service
@Transactional
public class HolderChangeRequestService {

    private final HolderChangeRequestRepository repository;
    private final AssetRepository assetRepository;
    private final HolderService holderService;
    private final ApplicationEventPublisher events;

    public HolderChangeRequestService(HolderChangeRequestRepository repository, AssetRepository assetRepository,
                                      HolderService holderService, ApplicationEventPublisher events) {
        this.repository = repository;
        this.assetRepository = assetRepository;
        this.holderService = holderService;
        this.events = events;
    }

    public HolderChangeRequest request(UUID assetId, HolderChangeRequest.RequestType type, UUID holderId,
                                       Map<String, Object> payload, InstructingParty party, String reference,
                                       UUID actorId, String actorRole) {
        new HolderInstruction(party, reference).validate();
        assetRepository.findById(assetId).orElseThrow(() -> new EntityNotFoundException("Asset", assetId));
        if (type == HolderChangeRequest.RequestType.UPDATE_ATTRIBUTES && holderId == null) {
            throw new IllegalArgumentException("holderId is required for an attribute change");
        }
        if (type == HolderChangeRequest.RequestType.ADD_HOLDER
                && (payload == null || payload.get("investorId") == null || payload.get("walletAddress") == null)) {
            throw new IllegalArgumentException("investorId and walletAddress are required to request a new entry");
        }
        HolderChangeRequest r = new HolderChangeRequest();
        r.setAssetId(assetId);
        r.setRequestType(type);
        r.setHolderId(holderId);
        r.setPayload(payload != null ? payload : Map.of());
        r.setInstructingParty(party);
        r.setInstructionReference(reference);
        r.setRequestedBy(actorId);
        r.setRequestedByRole(actorRole);
        HolderChangeRequest saved = repository.save(r);
        events.publishEvent(new HolderChangeRequestedEvent(saved.getId(), actorId, actorRole, Map.of(
                "assetId", assetId.toString(), "requestType", type.name(),
                "instructingParty", party.name(), "instructionReference", reference)));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<HolderChangeRequest> list(UUID assetId) {
        return repository.findByAssetIdOrderByRequestedAtDesc(assetId);
    }

    public HolderChangeRequest reject(UUID assetId, UUID requestId, String reason, UUID actorId, String actorRole) {
        HolderChangeRequest r = openRequest(assetId, requestId);
        r.setStatus(HolderChangeRequest.Status.REJECTED);
        r.setDecidedBy(actorId);
        r.setDecidedAt(Instant.now());
        r.setDecisionReason(reason);
        HolderChangeRequest saved = repository.save(r);
        events.publishEvent(new HolderChangeRequestRejectedEvent(requestId, actorId, actorRole, Map.of(
                "assetId", assetId.toString(), "reason", reason)));
        return saved;
    }

    /** Executes the request as the operator; {@code approverId} is the second approver of the 4-eyes step. */
    public HolderChangeRequest execute(UUID assetId, UUID requestId, UUID actorId, String actorRole, UUID approverId) {
        HolderChangeRequest r = openRequest(assetId, requestId);
        HolderInstruction ins = new HolderInstruction(r.getInstructingParty(), r.getInstructionReference(),
                approverId, r.getId());
        Map<String, Object> p = r.getPayload();
        AssetHolder holder;
        if (r.getRequestType() == HolderChangeRequest.RequestType.ADD_HOLDER) {
            UUID investorId = UUID.fromString(String.valueOf(p.get("investorId")));
            String wallet = String.valueOf(p.get("walletAddress"));
            BigDecimal nominal = p.get("nominalAmount") != null ? new BigDecimal(String.valueOf(p.get("nominalAmount"))) : null;
            if (Boolean.TRUE.equals(p.get("singleEntry"))) {
                holder = holderService.addSingleEntryHolder(assetId, investorId, wallet, nominal,
                        Boolean.TRUE.equals(p.get("isConsumer")), text(p, "thirdPartyRights"),
                        text(p, "disposalRestrictions"), text(p, "legalCapacityNote"), ins, actorId, actorRole);
            } else {
                holder = holderService.addHolder(assetId, investorId, wallet, nominal, ins, actorId, actorRole);
            }
        } else {
            HolderService.AttributeChange change = new HolderService.AttributeChange(
                    p.get("isConsumer") != null ? Boolean.valueOf(String.valueOf(p.get("isConsumer"))) : null,
                    text(p, "thirdPartyRights"), text(p, "disposalRestrictions"), text(p, "legalCapacityNote"),
                    Boolean.TRUE.equals(p.get("clearThirdPartyRights")),
                    Boolean.TRUE.equals(p.get("clearDisposalRestrictions")));
            holder = holderService.updateSingleEntryAttributes(assetId, r.getHolderId(), change, ins, actorId, actorRole);
        }
        r.setStatus(HolderChangeRequest.Status.EXECUTED);
        r.setDecidedBy(actorId);
        r.setDecidedAt(Instant.now());
        r.setHolderId(holder.getId());
        HolderChangeRequest saved = repository.save(r);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("assetId", assetId.toString());
        details.put("holderId", holder.getId().toString());
        if (approverId != null) details.put("approverId", approverId.toString());
        events.publishEvent(new HolderChangeRequestExecutedEvent(requestId, actorId, actorRole, details));
        return saved;
    }

    private HolderChangeRequest openRequest(UUID assetId, UUID requestId) {
        HolderChangeRequest r = repository.findByIdAndAssetIdForUpdate(requestId, assetId)
                .orElseThrow(() -> new EntityNotFoundException("HolderChangeRequest", requestId));
        if (r.getStatus() != HolderChangeRequest.Status.REQUESTED) {
            throw new InvalidStateTransitionException("HolderChangeRequest", r.getStatus().name(), "decided");
        }
        return r;
    }

    private static String text(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
