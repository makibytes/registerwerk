package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.events.AssetUpdatedEvent;
import de.makibytes.registerwerk.asset.web.dto.BondTermsRequest;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import de.makibytes.registerwerk.deployment.api.DayCountConvention;
import de.makibytes.registerwerk.deployment.api.PaymentFrequency;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BondTermsServiceTest {

    @Mock AssetRepository assetRepository;
    @Mock AssetBondTermsRepository termsRepository;
    @Mock ApplicationEventPublisher events;

    private BondTermsService service() {
        return new BondTermsService(assetRepository, termsRepository, events);
    }

    private BondTermsRequest request() {
        LocalDate issue = LocalDate.of(2026, 9, 1);
        return new BondTermsRequest(new BigDecimal("1000"), "eur", issue,
                issue.plusYears(5), new BigDecimal("0.03"), " EURIBOR_3M ",
                new BigDecimal("-0.001"), DayCountConvention.ACT_360,
                PaymentFrequency.QUARTERLY, true,
                List.of(new BondTermsRequest.CallScheduleEntry(
                        issue.plusYears(2), new BigDecimal("100"))));
    }

    @Test
    void refusesTermsForUnknownAsset() {
        UUID assetId = UUID.randomUUID();
        when(assetRepository.findById(assetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().upsert(
                assetId, request(), UUID.randomUUID(), "REGISTRY_ADMIN"))
                .isInstanceOf(EntityNotFoundException.class);
        verify(termsRepository, never()).save(any());
    }

    @Test
    void normalizesAndAuditsTermsWithoutResettingLifecycleStatus() {
        UUID assetId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        AssetBondTerms existing = new AssetBondTerms();
        existing.setAssetId(assetId);
        existing.setBondStatus(BondStatus.MATURED);
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(new Asset()));
        when(termsRepository.findById(assetId)).thenReturn(Optional.of(existing));
        when(termsRepository.save(existing)).thenReturn(existing);

        AssetBondTerms result = service().upsert(assetId, request(), actorId, "REGISTRY_ADMIN");

        assertThat(result.getCurrencyIso()).isEqualTo("EUR");
        assertThat(result.getReferenceRate()).isEqualTo("EURIBOR_3M");
        assertThat(result.getBondStatus()).isEqualTo(BondStatus.MATURED);
        assertThat(result.getCallSchedule()).singleElement().satisfies(entry -> {
            assertThat(entry.get("callDate")).isEqualTo("2028-09-01");
            assertThat(entry.get("callPrice")).isEqualTo(new BigDecimal("100"));
        });
        ArgumentCaptor<AssetUpdatedEvent> event = ArgumentCaptor.forClass(AssetUpdatedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().actorId()).isEqualTo(actorId);
        assertThat(event.getValue().actorRole()).isEqualTo("REGISTRY_ADMIN");
    }
}
