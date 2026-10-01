package de.makibytes.registerwerk.admin.internal;

import de.makibytes.registerwerk.admin.events.AdminImpersonationStartedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.auth.api.ImpersonationMode;
import de.makibytes.registerwerk.auth.api.ImpersonationSession;
import de.makibytes.registerwerk.auth.api.ImpersonationSessionRepository;
import de.makibytes.registerwerk.admin.web.dto.ImpersonateRequest;
import de.makibytes.registerwerk.admin.web.dto.ImpersonationSessionView;
import org.springframework.data.domain.PageRequest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.admin.web.dto.ImpersonateResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AdminImpersonationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ImpersonationSessionRepository sessions;
    private final AppUserRepository appUserRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final RegisterwerkAuthProperties authProperties;
    private final String customerFrontendUrl;

    public AdminImpersonationService(
            ImpersonationSessionRepository sessions,
            AppUserRepository appUserRepository,
            LegalEntityRepository legalEntityRepository,
            ApplicationEventPublisher eventPublisher,
            RegisterwerkAuthProperties authProperties,
            @Value("${registerwerk.onboarding.frontend-url}") String customerFrontendUrl) {
        this.sessions = sessions;
        this.appUserRepository = appUserRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.eventPublisher = eventPublisher;
        this.authProperties = authProperties;
        this.customerFrontendUrl = customerFrontendUrl;
    }

    @Transactional
    public ImpersonateResponse impersonate(Authentication caller, ImpersonateRequest request,
                                           ImpersonationMode mode, UUID approverId) {
        if (authProperties.isEntraEnabled()) {
            throw new UnsupportedOperationException(
                "Impersonation is only available in local-auth mode (ENTRA_ENABLED=false)"
            );
        }

        boolean isAdmin = caller != null && caller.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_REGISTRY_ADMIN"));
        if (!isAdmin) {
            throw new AccessDeniedException("Only REGISTRY_ADMIN users may impersonate");
        }

        UUID actorId = SecurityUtils.extractUserId(caller);
        if (actorId == null) {
            throw new AccessDeniedException("Authenticated user identity is invalid");
        }
        AppUser actor = appUserRepository.findById(actorId)
            .orElseThrow(() -> new EntityNotFoundException("AppUser", actorId));
        if (!actor.isEnabled()) {
            throw new AccessDeniedException("Disabled users may not impersonate");
        }
        if (mode == ImpersonationMode.ACT_ON_BEHALF && (approverId == null || approverId.equals(actorId))) {
            throw new AccessDeniedException("Acting on behalf of a customer requires a second approver");
        }

        UUID targetEntityId = request.entityId();
        LegalEntity target = legalEntityRepository.findById(targetEntityId)
            .orElseThrow(() -> new EntityNotFoundException("LegalEntity", targetEntityId));
        if (target.getStatus() != EntityStatus.ACTIVE) {
            throw new AccessDeniedException("Only active legal entities may be impersonated");
        }

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = Instant.now();
        Instant expires = now.plusSeconds(authProperties.getImpersonationTtlSeconds());
        ImpersonationSession session = sessions.save(new ImpersonationSession(
            UUID.randomUUID(), actorId, targetEntityId, mode, request.reason().trim(),
            request.ticket() == null || request.ticket().isBlank() ? null : request.ticket().trim(),
            approverId, expires, ImpersonationSession.hashHandoffCode(code),
            now.plusSeconds(authProperties.getImpersonationHandoffTtlSeconds())));

        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("sessionId", session.getId().toString());
        details.put("mode", mode.name());
        details.put("targetEntityId", targetEntityId.toString());
        details.put("targetEntityName", target.getCurrentName());
        details.put("reason", session.getReason());
        details.put("ticket", session.getTicketRef());
        details.put("expiresAt", expires.toString());
        eventPublisher.publishEvent(new AdminImpersonationStartedEvent(
                actorId, actorId, "REGISTRY_ADMIN", details, approverId));

        String encodedName = URLEncoder.encode(target.getCurrentName(), StandardCharsets.UTF_8);
        String handoffUrl = customerFrontendUrl + "/admin/handoff#code=" + code
                + "&entityId=" + target.getId()
                + "&entityName=" + encodedName;

        return new ImpersonateResponse(session.getId(), mode.name(), expires.atOffset(java.time.ZoneOffset.UTC),
                target.getId(), target.getCurrentName(), handoffUrl);
    }

    /** Sessions on one entity, newest first — shown to that entity's admins. */
    @Transactional(readOnly = true)
    public List<ImpersonationSessionView> sessionsFor(UUID entityId) {
        return sessions.findByTargetEntityIdOrderByStartedAtDesc(entityId, PageRequest.of(0, 100)).stream()
            .map(s -> new ImpersonationSessionView(s.getId(), s.getMode().name(), s.getReason(), s.getTicketRef(),
                s.getActorId(), s.getApproverId(), s.getStartedAt(), s.getExpiresAt(), s.getEndedAt(), s.getEndReason()))
            .toList();
    }
}
