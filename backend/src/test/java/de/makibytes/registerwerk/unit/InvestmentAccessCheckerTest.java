package de.makibytes.registerwerk.unit;

import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.trading.web.InvestmentAccessChecker;
import org.junit.jupiter.api.BeforeEach;
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

class InvestmentAccessCheckerTest {

    AssetHolderRepository holderRepository;
    InvestmentAccessChecker checker;

    static final UUID INVESTOR_A = UUID.randomUUID();
    static final UUID INVESTOR_B = UUID.randomUUID();
    static final UUID HOLDER_ID  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        holderRepository = mock(AssetHolderRepository.class);
        checker = new InvestmentAccessChecker(holderRepository);

        AssetHolder holder = new AssetHolder();
        holder.setInvestorId(INVESTOR_A);
        when(holderRepository.findById(HOLDER_ID)).thenReturn(Optional.of(holder));
    }

    @Test
    void canRead_owningInvestor_returnsTrue() {
        assertThat(checker.canRead(HOLDER_ID, jwtAuth(INVESTOR_A, "INVESTOR"))).isTrue();
    }

    @Test
    void canRead_differentInvestor_returnsFalse() {
        assertThat(checker.canRead(HOLDER_ID, jwtAuth(INVESTOR_B, "INVESTOR"))).isFalse();
    }

    @Test
    void canRead_admin_returnsTrue() {
        assertThat(checker.canRead(HOLDER_ID, jwtAuth(INVESTOR_B, "REGISTRY_ADMIN"))).isTrue();
    }

    @Test
    void canRead_audit_returnsTrue() {
        assertThat(checker.canRead(HOLDER_ID, jwtAuth(INVESTOR_B, "AUDIT"))).isTrue();
    }

    @Test
    void canRead_missingHolder_returnsFalse() {
        UUID unknownId = UUID.randomUUID();
        when(holderRepository.findById(unknownId)).thenReturn(Optional.empty());
        assertThat(checker.canRead(unknownId, jwtAuth(INVESTOR_A, "INVESTOR"))).isFalse();
    }

    @Test
    void canRead_nullAuthentication_returnsFalse() {
        assertThat(checker.canRead(HOLDER_ID, null)).isFalse();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Authentication jwtAuth(UUID entityId, String role) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("entity_id", entityId.toString())
                .claim("sub", entityId.toString())
                .build();
        return new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
