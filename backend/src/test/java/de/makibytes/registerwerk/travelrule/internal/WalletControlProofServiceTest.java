package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.travelrule.api.WalletSignaturePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 6-27: Art. 14(5) wallet-control proofs are bound to the (entity, wallet) pair. */
class WalletControlProofServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final WalletSignaturePort verifier = mock(WalletSignaturePort.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final WalletControlProofService service = new WalletControlProofService(jdbc, verifier, events,
            Clock.fixed(Instant.parse("2026-09-01T10:00:00Z"), ZoneOffset.UTC), 365);
    private final UUID entity = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();
    private static final String WALLET = "0x2222222222222222222222222222222222222222";

    @BeforeEach
    void holderWallet() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
    }

    @Test
    void attestationNeedsSecondApproverAndEvidence() {
        assertThatThrownBy(() -> service.attest(entity, WALLET, "KYC video ident ref 4711", UUID.randomUUID(), "COMPLIANCE_OFFICER", null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.attest(entity, WALLET, " ", UUID.randomUUID(), "COMPLIANCE_OFFICER", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void proofForWalletOfAnotherEntityIsNotAccepted() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> service.createChallenge(entity, WALLET, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a registered holder wallet");
        assertThatThrownBy(() -> service.attest(entity, WALLET, "evidence note long enough", UUID.randomUUID(),
                "COMPLIANCE_OFFICER", UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lookupJoinsProofToTheRegisteredHolderOfThatWallet() {
        UUID proof = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(UUID.class), any(Object[].class))).thenReturn(List.of(proof));

        assertThat(service.findValidProof(assetId, WALLET)).contains(proof);
        assertThat(service.findValidProof(null, WALLET)).isEmpty();
        assertThat(service.findValidProof(assetId, " ")).isEmpty();
    }
}
