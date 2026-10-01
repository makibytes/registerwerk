package de.makibytes.registerwerk.bootstrap;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.customer.api.ClientCategory;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.repo.api.*;
import de.makibytes.registerwerk.shared.api.RepoDeskCapability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** K3 made repo desk participation opt-in; the demo companies must be opted in or the customer Repo Desk page shows only the opt-in card. */
@DisplayName("RepoDeskDemoSeeder")
class RepoDeskDemoSeederTest {
    static final List<String> NUMBERS = List.of("DEMO-NI-001", "DEMO-RK-001", "DEMO-AF-001", "DEMO-FD-001", "DEMO-WI-001");

    RepoDeskCapability capability = mock(RepoDeskCapability.class);
    RepoRfqRepository rfqs = mock(RepoRfqRepository.class);
    RepoQuoteRepository quotes = mock(RepoQuoteRepository.class);
    LegalEntityRepository entities = mock(LegalEntityRepository.class);
    AssetRepository assets = mock(AssetRepository.class);
    RepoDeskParticipantRepository participants = mock(RepoDeskParticipantRepository.class);
    Map<String, LegalEntity> byNumber = new HashMap<>();
    RepoDeskDemoSeeder seeder = new RepoDeskDemoSeeder(capability, rfqs, quotes, entities, assets, participants);

    RepoDeskDemoSeederTest() {
        when(capability.isReleased()).thenReturn(true);
        for (String n : NUMBERS) {
            LegalEntity e = new LegalEntity();
            e.setId(UUID.randomUUID());
            e.setEntityNumber(n);
            byNumber.put(n, e);
            when(entities.findByEntityNumber(n)).thenReturn(Optional.of(e));
            when(participants.findById(e.getId())).thenReturn(Optional.empty());
        }
        for (String n : List.of("DEMO-BOND-MC-001", "DEMO-NOTE-AF-001")) {
            Asset a = new Asset();
            a.setId(UUID.randomUUID());
            when(assets.findByAssetNumber(n)).thenReturn(Optional.of(a));
        }
        when(rfqs.save(any())).thenAnswer(i -> { RepoRfq r = i.getArgument(0); r.setId(UUID.randomUUID()); return r; });
    }

    @Test
    @DisplayName("opts in and lists all five demo companies and classifies only unclassified ones")
    void optsInDemoCompanies() {
        byNumber.get("DEMO-WI-001").setClientCategory(ClientCategory.ELIGIBLE_COUNTERPARTY);
        when(rfqs.findAll()).thenReturn(List.of());

        seeder.run(null);

        var saved = org.mockito.ArgumentCaptor.forClass(RepoDeskParticipant.class);
        verify(participants, times(5)).save(saved.capture());
        assertThat(saved.getAllValues()).allMatch(p -> p.isActive() && p.isListed());
        assertThat(saved.getAllValues()).extracting(RepoDeskParticipant::getEntityId)
                .containsExactlyInAnyOrderElementsOf(byNumber.values().stream().map(LegalEntity::getId).toList());
        assertThat(byNumber.get("DEMO-NI-001").getClientCategory()).isEqualTo(ClientCategory.PROFESSIONAL);
        assertThat(byNumber.get("DEMO-WI-001").getClientCategory()).isEqualTo(ClientCategory.ELIGIBLE_COUNTERPARTY);
        verify(rfqs, times(3)).save(any());
        verify(quotes, times(2)).save(any());
    }

    @Test
    @DisplayName("repairs participants on an already-seeded database without duplicating RFQs")
    void repairsAlreadySeededDatabase() {
        RepoRfq existing = new RepoRfq();
        existing.setNotes("[DEMO-REPO] old");
        when(rfqs.findAll()).thenReturn(List.of(existing));
        LegalEntity nord = byNumber.get("DEMO-NI-001");
        RepoDeskParticipant out = new RepoDeskParticipant();
        out.setEntityId(nord.getId());
        out.setOptedOutAt(Instant.now());
        when(participants.findById(nord.getId())).thenReturn(Optional.of(out));

        seeder.run(null);

        verify(participants, times(5)).save(any());
        assertThat(out.isActive()).isTrue();
        assertThat(out.isListed()).isTrue();
        verify(rfqs, never()).save(any());
        verify(quotes, never()).save(any());
    }

    @Test
    @DisplayName("re-opens expired marker RFQs and re-arms their quotes so the demo never goes stale")
    void refreshesExpiredMarkerRfqsAndQuotes() {
        RepoRfq expired = new RepoRfq();
        expired.setId(UUID.randomUUID());
        expired.setNotes("[DEMO-REPO] old");
        expired.setStatus(de.makibytes.registerwerk.repo.api.RepoTypes.RfqStatus.EXPIRED);
        expired.setExpiresAt(Instant.now().minusSeconds(3600));
        RepoRfq matched = new RepoRfq();
        matched.setId(UUID.randomUUID());
        matched.setNotes("[DEMO-REPO] taken");
        matched.setStatus(de.makibytes.registerwerk.repo.api.RepoTypes.RfqStatus.MATCHED);
        matched.setExpiresAt(Instant.now().minusSeconds(3600));
        RepoQuote quote = new RepoQuote();
        quote.setStatus(de.makibytes.registerwerk.repo.api.RepoTypes.QuoteStatus.EXPIRED);
        quote.setValidUntil(Instant.now().minusSeconds(60));
        when(rfqs.findAll()).thenReturn(List.of(expired, matched));
        when(quotes.findByRfqIdOrderByRepoRateAscCreatedAtAsc(any())).thenReturn(List.of(quote));

        seeder.run(null);

        assertThat(expired.getStatus()).isEqualTo(de.makibytes.registerwerk.repo.api.RepoTypes.RfqStatus.OPEN);
        assertThat(expired.getExpiresAt()).isAfter(Instant.now().plusSeconds(3600));
        assertThat(quote.getStatus()).isEqualTo(de.makibytes.registerwerk.repo.api.RepoTypes.QuoteStatus.ACTIVE);
        assertThat(quote.getValidUntil()).isAfter(Instant.now().plusSeconds(3600));
        assertThat(matched.getStatus()).isEqualTo(de.makibytes.registerwerk.repo.api.RepoTypes.RfqStatus.MATCHED);
        verify(rfqs, times(1)).save(expired);
        verify(rfqs, never()).save(matched);
        verify(quotes).save(quote);
    }

    @Test
    @DisplayName("does nothing while the repo desk is not released")
    void skippedWhenNotReleased() {
        when(capability.isReleased()).thenReturn(false);
        seeder.run(null);
        verifyNoInteractions(participants);
    }
}
