package de.makibytes.registerwerk.admin.internal;

import de.makibytes.registerwerk.admin.events.AdminImpersonationStartedEvent;
import org.springframework.context.ApplicationEventPublisher;
import de.makibytes.registerwerk.auth.api.JwtMintingService;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.auth.api.AppUser;
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

    private final JwtMintingService jwtMintingService;
    private final AppUserRepository appUserRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final RegisterwerkAuthProperties authProperties;
    private final String customerFrontendUrl;

    public AdminImpersonationService(
            JwtMintingService jwtMintingService,
            AppUserRepository appUserRepository,
            LegalEntityRepository legalEntityRepository,
            ApplicationEventPublisher eventPublisher,
            RegisterwerkAuthProperties authProperties,
            @Value("${registerwerk.onboarding.frontend-url}") String customerFrontendUrl) {
        this.jwtMintingService = jwtMintingService;
        this.appUserRepository = appUserRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.eventPublisher = eventPublisher;
        this.authProperties = authProperties;
        this.customerFrontendUrl = customerFrontendUrl;
    }

    public ImpersonateResponse impersonate(Authentication caller, UUID targetEntityId) {
        if (authProperties.isEntraEnabled()) {
            throw new UnsupportedOperationException(
                "Impersonation is only available in local-auth mode (ENTRA_ENABLED=false)"
            );
        }

        UUID actorId = SecurityUtils.extractUserId(caller);
        AppUser actor = appUserRepository.findById(actorId)
            .orElseThrow(() -> new EntityNotFoundException("AppUser", actorId));

        boolean isAdmin = caller.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_REGISTRY_ADMIN"));
        if (!isAdmin) {
            throw new AccessDeniedException("Only REGISTRY_ADMIN users may impersonate");
        }

        LegalEntity target = legalEntityRepository.findById(targetEntityId)
            .orElseThrow(() -> new EntityNotFoundException("LegalEntity", targetEntityId));

        String token = jwtMintingService.mintImpersonationToken(actor, target);
        OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(jwtMintingService.getTokenTtlSeconds());

        eventPublisher.publishEvent(new AdminImpersonationStartedEvent(actorId, actorId, "REGISTRY_ADMIN", java.util.Map.of("targetEntityId", targetEntityId.toString(), "targetEntityName", target.getCurrentName())));

        String encodedName = URLEncoder.encode(target.getCurrentName(), StandardCharsets.UTF_8);
        String handoffUrl = customerFrontendUrl + "/admin/handoff#token=" + token
                + "&entityId=" + target.getId()
                + "&entityName=" + encodedName;

        return new ImpersonateResponse(token, "Bearer", expiresAt, target.getId(), target.getCurrentName(), handoffUrl);
    }
}
