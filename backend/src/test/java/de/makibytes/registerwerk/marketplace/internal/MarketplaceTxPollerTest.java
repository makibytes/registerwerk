package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.marketplace.api.DappListing;
import de.makibytes.registerwerk.marketplace.api.DappListingRepository;
import de.makibytes.registerwerk.marketplace.api.DappListingStatus;
import de.makibytes.registerwerk.marketplace.api.DappVersion;
import de.makibytes.registerwerk.marketplace.api.DappVersionRepository;
import de.makibytes.registerwerk.marketplace.api.DappVersionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for {@code MarketplaceTxPoller.resolve} — it used to publish a dApp
 * version on the very first mined receipt with {@code status == "0x1"}, regardless of the
 * chain's confirmation depth or finality model. That meant a reorg un-mining the anchor tx
 * would leave the listing PUBLISHED on a state the chain had already abandoned. It now consults
 * the same tracked, model-aware verdict {@code BlockchainTransactionService} already computes
 * for this tx (via {@link MarketplaceTxGateway#isConfirmedSuccess}/{@code isConfirmedFailure}).
 */
@ExtendWith(MockitoExtension.class)
class MarketplaceTxPollerTest {

    @Mock private DappVersionRepository versionRepository;
    @Mock private DappListingRepository listingRepository;
    @Mock private MarketplaceTxGateway txGateway;
    @Mock private MarketplaceOnchainAnchorService anchorService;
    @Mock private de.makibytes.registerwerk.finality.api.ChainEffectRecorder chainEffectRecorder;

    private MarketplaceTxPoller poller;

    private final UUID listingId = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        poller = new MarketplaceTxPoller(versionRepository, listingRepository, txGateway, anchorService, chainEffectRecorder);
    }

    private DappVersion approvedVersion(String txHash) {
        DappVersion v = new DappVersion();
        org.springframework.test.util.ReflectionTestUtils.setField(v, "id", UUID.randomUUID());
        v.setListingId(listingId);
        v.setStatus(DappVersionStatus.APPROVED);
        v.setOnchainTx(txHash);
        v.setReviewedAt(Instant.now().minusSeconds(200));
        return v;
    }

    private DappListing listing() {
        DappListing l = new DappListing();
        l.setId(listingId);
        l.setChainConfigId(chainConfigId);
        l.setSlug("bond-desk");
        l.setStatus(DappListingStatus.IN_REVIEW);
        return l;
    }

    @Test
    @DisplayName("a mined-but-not-yet-final tx leaves the version APPROVED — no premature publish")
    void resolveApprovedVersions_notYetFinal_leavesVersionApproved() {
        DappVersion version = approvedVersion("0xtx1");
        when(versionRepository.findByStatus(DappVersionStatus.APPROVED)).thenReturn(List.of(version));
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing()));
        when(txGateway.isConfirmedFailure("0xtx1")).thenReturn(false);
        when(txGateway.isConfirmedSuccess("0xtx1")).thenReturn(false); // mined but not yet final

        poller.resolveApprovedVersions();

        assertThat(version.getStatus()).isEqualTo(DappVersionStatus.APPROVED);
        verify(versionRepository, never()).save(any());
        verify(listingRepository, never()).save(any());
    }

    @Test
    @DisplayName("a confirmed-success tx publishes the version and supersedes the previous one")
    void resolveApprovedVersions_confirmedSuccess_publishes() {
        DappVersion version = approvedVersion("0xtx1");
        DappVersion previouslyPublished = new DappVersion();
        previouslyPublished.setListingId(listingId);
        previouslyPublished.setStatus(DappVersionStatus.PUBLISHED);

        when(versionRepository.findByStatus(DappVersionStatus.APPROVED)).thenReturn(List.of(version));
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing()));
        when(txGateway.isConfirmedFailure("0xtx1")).thenReturn(false);
        when(txGateway.isConfirmedSuccess("0xtx1")).thenReturn(true);
        when(versionRepository.findByListingIdOrderByCreatedAtDesc(listingId))
                .thenReturn(List.of(previouslyPublished));
        when(txGateway.confirmedLocation("0xtx1")).thenReturn(Optional.of(
                new de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService.ConfirmedTxLocation(
                        chainConfigId, 100L, "0xblock100")));

        poller.resolveApprovedVersions();

        assertThat(version.getStatus()).isEqualTo(DappVersionStatus.PUBLISHED);
        assertThat(previouslyPublished.getStatus()).isEqualTo(DappVersionStatus.SUPERSEDED);
        verify(listingRepository).save(argThatStatus(DappListingStatus.PUBLISHED));

        org.mockito.ArgumentCaptor<de.makibytes.registerwerk.finality.api.ChainEffectDescriptor> captor =
                org.mockito.ArgumentCaptor.forClass(de.makibytes.registerwerk.finality.api.ChainEffectDescriptor.class);
        verify(chainEffectRecorder).record(captor.capture());
        assertThat(captor.getValue().effectType()).isEqualTo("DAPP_VERSION_PUBLISHED");
        assertThat(captor.getValue().chainConfigId()).isEqualTo(chainConfigId);
        assertThat(captor.getValue().blockNumber()).isEqualTo(100L);
        assertThat(captor.getValue().entityId()).isEqualTo(version.getId());
    }

    @Test
    @DisplayName("a confirmed-failure (reverted or timed-out) tx clears the anchor for re-approval, "
            + "never publishes")
    void resolveApprovedVersions_confirmedFailure_clearsAnchor() {
        DappVersion version = approvedVersion("0xtx1");
        when(versionRepository.findByStatus(DappVersionStatus.APPROVED)).thenReturn(List.of(version));
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing()));
        when(txGateway.isConfirmedFailure("0xtx1")).thenReturn(true);

        poller.resolveApprovedVersions();

        assertThat(version.getStatus()).isEqualTo(DappVersionStatus.IN_REVIEW);
        assertThat(version.getOnchainTx()).isNull();
        verify(txGateway, never()).isConfirmedSuccess(any());
        verify(listingRepository, never()).save(any());
    }

    private static DappListing argThatStatus(DappListingStatus status) {
        return org.mockito.ArgumentMatchers.argThat(l -> l.getStatus() == status);
    }
}
