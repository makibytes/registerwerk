package de.makibytes.registerwerk.repo.internal;

import de.makibytes.registerwerk.repo.api.RepoTradeRepository;
import de.makibytes.registerwerk.repo.api.RepoTypes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RepoHandoverBlocker (9A-07)")
class RepoHandoverBlockerTest {

    @Test
    @DisplayName("an open repo pledging the asset blocks the handover; none does not")
    void blocksOnlyWhileAnOpenRepoPledgesTheAsset() {
        RepoTradeRepository trades = mock(RepoTradeRepository.class);
        UUID asset = UUID.randomUUID();
        RepoHandoverBlocker blocker = new RepoHandoverBlocker(trades);
        when(trades.existsByCollateralAssetIdAndStatusIn(asset, RepoTypes.TradeStatus.OPEN_STATES)).thenReturn(true);
        assertThat(blocker.blocksHandover(asset)).hasValueSatisfying(r -> assertThat(r).contains("open repo"));
        assertThat(blocker.blocksHandover(UUID.randomUUID())).isEmpty();
    }
}
