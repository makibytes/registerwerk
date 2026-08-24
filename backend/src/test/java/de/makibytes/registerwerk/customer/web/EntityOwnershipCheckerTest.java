package de.makibytes.registerwerk.customer.web;

import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("EntityOwnershipChecker.isAssignedRelationshipManager unit tests (Track 5-4)")
class EntityOwnershipCheckerTest {

    LegalEntityRepository legalEntityRepository;
    EntityOwnershipChecker checker;

    static final UUID ENTITY_ID = UUID.randomUUID();
    static final UUID RM_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        legalEntityRepository = mock(LegalEntityRepository.class);
        checker = new EntityOwnershipChecker(legalEntityRepository);
    }

    private static Authentication jwtAuth(UUID userId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("sub", userId.toString())
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_RELATIONSHIP_MANAGER")));
    }

    @Test
    void isAssignedRelationshipManager_matchingAssignment_returnsTrue() {
        LegalEntity entity = new LegalEntity();
        entity.setAssignedRelationshipManagerId(RM_ID);
        when(legalEntityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        assertThat(checker.isAssignedRelationshipManager(ENTITY_ID, jwtAuth(RM_ID))).isTrue();
    }

    @Test
    void isAssignedRelationshipManager_differentRm_returnsFalse() {
        LegalEntity entity = new LegalEntity();
        entity.setAssignedRelationshipManagerId(UUID.randomUUID());
        when(legalEntityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        assertThat(checker.isAssignedRelationshipManager(ENTITY_ID, jwtAuth(RM_ID))).isFalse();
    }

    @Test
    void isAssignedRelationshipManager_unassignedEntity_returnsFalse() {
        LegalEntity entity = new LegalEntity();
        when(legalEntityRepository.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        assertThat(checker.isAssignedRelationshipManager(ENTITY_ID, jwtAuth(RM_ID))).isFalse();
    }

    @Test
    void isAssignedRelationshipManager_unknownEntity_returnsFalse() {
        when(legalEntityRepository.findById(ENTITY_ID)).thenReturn(Optional.empty());

        assertThat(checker.isAssignedRelationshipManager(ENTITY_ID, jwtAuth(RM_ID))).isFalse();
    }

    @Test
    void isAssignedRelationshipManager_nullAuthentication_returnsFalse() {
        assertThat(checker.isAssignedRelationshipManager(ENTITY_ID, null)).isFalse();
    }
}
