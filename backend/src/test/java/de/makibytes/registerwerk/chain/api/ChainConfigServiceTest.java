package de.makibytes.registerwerk.chain.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for {@link ChainConfigService#update} — specifically the
 * {@code finalityModel} field, whose entity-level default ({@code DEPTH_BASED}) makes "not
 * supplied in this PATCH request" indistinguishable from "explicitly set to DEPTH_BASED" unless
 * the controller nulls it out on the patch carrier first (see
 * {@code ChainConfigController.updateChain}). Without that, any unrelated field-only PATCH would
 * silently downgrade an existing TAG_BASED/INSTANT chain back to DEPTH_BASED.
 */
@ExtendWith(MockitoExtension.class)
class ChainConfigServiceTest {

    @Mock private ChainConfigRepository chainConfigRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ChainConfigService service;
    private final UUID chainId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ChainConfigService(chainConfigRepository, eventPublisher);
    }

    private ChainConfig existingTagBasedChain() {
        ChainConfig existing = new ChainConfig();
        existing.setId(chainId);
        existing.setIdentifier("ETHEREUM_MAINNET");
        existing.setDisplayName("Ethereum");
        existing.setChainType(ChainConfig.ChainType.EVM);
        existing.setNetworkType(ChainConfig.NetworkType.MAINNET);
        existing.setRpcUrl("https://eth.example.com");
        existing.setFinalityModel(ChainConfig.FinalityModel.TAG_BASED);
        return existing;
    }

    @Test
    @DisplayName("a PATCH that only changes displayName leaves an existing TAG_BASED chain's "
            + "finalityModel untouched — the patch carrier's finalityModel must be null, not the "
            + "entity's own DEPTH_BASED field default")
    void update_unrelatedFieldOnly_doesNotResetFinalityModel() {
        ChainConfig existing = existingTagBasedChain();
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(existing));
        when(chainConfigRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Mirrors what ChainConfigController.updateChain builds for a PATCH that only supplies
        // displayName: a fresh ChainConfig with finalityModel explicitly nulled out.
        ChainConfig patch = new ChainConfig();
        patch.setFinalityModel(null);
        patch.setDisplayName("Ethereum Mainnet");

        ChainConfig updated = service.update(chainId, patch);

        assertThat(updated.getDisplayName()).isEqualTo("Ethereum Mainnet");
        assertThat(updated.getFinalityModel()).isEqualTo(ChainConfig.FinalityModel.TAG_BASED);
    }

    @Test
    @DisplayName("a PATCH that explicitly supplies finalityModel changes it")
    void update_withFinalityModel_appliesIt() {
        ChainConfig existing = existingTagBasedChain();
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(existing));
        when(chainConfigRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ChainConfig patch = new ChainConfig();
        patch.setFinalityModel(ChainConfig.FinalityModel.INSTANT);

        ChainConfig updated = service.update(chainId, patch);

        assertThat(updated.getFinalityModel()).isEqualTo(ChainConfig.FinalityModel.INSTANT);
    }
}
