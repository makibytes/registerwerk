package de.makibytes.registerwerk.application.admin;

import de.makibytes.registerwerk.application.audit.AuditEventPublisher;
import de.makibytes.registerwerk.application.auth.JwtMintingService;
import de.makibytes.registerwerk.application.exception.EntityNotFoundException;
import de.makibytes.registerwerk.config.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.domain.entity.AppUser;
import de.makibytes.registerwerk.domain.entity.LegalEntity;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AppUserRepository;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.LegalEntityRepository;
import de.makibytes.registerwerk.web.dto.ImpersonateResponse;
import de.makibytes.registerwerk.web.security.SecurityUtils;
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
    private final AuditEventPublisher auditEventPublisher;
    private final RegisterwerkAuthProperties authProperties;
    private final String customerFrontendUrl;

    public AdminImpersonationService(
            JwtMintingService jwtMintingService,
            AppUserRepository appUserRepository,
            LegalEntityRepository legalEntityRepository,
            AuditEventPublisher auditEventPublisher,
            RegisterwerkAuthProperties authProperties,
            @Value("${registerwerk.onboarding.frontend-url}") String customerFrontendUrl) {
        this.jwtMintingService = jwtMintingService;
        this.appUserRepository = appUserRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.auditEventPublisher = auditEventPublisher;
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

        auditEventPublisher.publish(
            "ADMIN_IMPERSONATION_STARTED",
            "LegalEntity",
            targetEntityId,
            actorId,
            "REGISTRY_ADMIN",
            Map.of(
                "targetEntityId", targetEntityId.toString(),
                "targetEntityName", target.getCurrentName()
            )
        );

        String encodedName = URLEncoder.encode(target.getCurrentName(), StandardCharsets.UTF_8);
        String handoffUrl = customerFrontendUrl + "/admin/handoff#token=" + token
                + "&entityId=" + target.getId()
                + "&entityName=" + encodedName;

        return new ImpersonateResponse(token, "Bearer", expiresAt, target.getId(), target.getCurrentName(), handoffUrl);
    }
}
