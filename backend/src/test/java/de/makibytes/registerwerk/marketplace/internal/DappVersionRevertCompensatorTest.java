package de.makibytes.registerwerk.marketplace.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DappVersionRevertCompensator — INVERSE_FLIP compensator for DAPP_VERSION_PUBLISHED")
class DappVersionRevertCompensatorTest {

    @Mock private DappVersionRepository versionRepository;
    @Mock private DappListingRepository listingRepository;

    private DappVersionRevertCompensator compensator;
    private final UUID versionId = UUID.randomUUID();
    private final UUID listingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new DappVersionRevertCompensator(versionRepository, listingRepository);
    }

    private ChainEffectRecord effect() {
        return new ChainEffectRecord(UUID.randomUUID(), UUID.randomUUID(), 100L, "0xhash", "0xtxhash", null,
                "marketplace", "DAPP_VERSION_PUBLISHED", "DappVersion", versionId, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());
    }

    @Test
    void advertisesIdentity() {
        assertThat(compensator.effectType()).isEqualTo("DAPP_VERSION_PUBLISHED");
        assertThat(compensator.category()).isEqualTo(CompensationCategory.INVERSE_FLIP);
    }

    @Test
    void compensateRevertsPublishedVersionAndLiveListing() {
        DappVersion version = new DappVersion();
        ReflectionTestUtils.setField(version, "id", versionId);
        version.setListingId(listingId);
        version.setStatus(DappVersionStatus.PUBLISHED);
        when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));

        DappListing listing = new DappListing();
        listing.setId(listingId);
        listing.setStatus(DappListingStatus.PUBLISHED);
        listing.setCurrentVersionId(versionId);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(versionRepository).save(version);
        assertThat(version.getStatus()).isEqualTo(DappVersionStatus.APPROVED);
        verify(listingRepository).save(listing);
        assertThat(listing.getStatus()).isEqualTo(DappListingStatus.APPROVED);
        assertThat(listing.getCurrentVersionId()).isNull();
        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
    }

    @Test
    @DisplayName("if the listing's live version has already moved on, the listing is left untouched")
    void compensateLeavesListingAloneIfNoLongerLiveVersion() {
        DappVersion version = new DappVersion();
        ReflectionTestUtils.setField(version, "id", versionId);
        version.setListingId(listingId);
        version.setStatus(DappVersionStatus.PUBLISHED);
        when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));

        DappListing listing = new DappListing();
        listing.setId(listingId);
        listing.setStatus(DappListingStatus.PUBLISHED);
        listing.setCurrentVersionId(UUID.randomUUID()); // a newer version is now live
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        compensator.compensate(effect());

        verify(listingRepository, never()).save(any());
    }

    @Test
    void nonPublishedVersionIsNotApplicable() {
        DappVersion version = new DappVersion();
        version.setStatus(DappVersionStatus.APPROVED);
        when(versionRepository.findById(versionId)).thenReturn(Optional.of(version));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(versionRepository, never()).save(any());
        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }

    @Test
    void missingVersionIsNotApplicable() {
        when(versionRepository.findById(versionId)).thenReturn(Optional.empty());

        assertThat(compensator.compensate(effect())).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }
}
