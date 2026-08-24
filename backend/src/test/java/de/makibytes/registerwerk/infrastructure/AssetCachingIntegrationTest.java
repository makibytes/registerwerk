package de.makibytes.registerwerk.infrastructure;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.internal.AssetLifecycleService;
import de.makibytes.registerwerk.asset.internal.AssetService;
import de.makibytes.registerwerk.customer.CustomerApi;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the {@code @Cacheable}/{@code @CacheEvict} wiring on {@link AssetService#getAsset}
 * actually takes effect through a real Spring proxy - a plain Mockito {@code @InjectMocks} unit
 * test (see {@code AssetServiceTest}) never exercises Spring AOP, so it cannot catch a missing or
 * mismatched cache key on either side.
 */
@SpringJUnitConfig(classes = {CacheConfig.class, AssetService.class, AssetLifecycleService.class})
@DisplayName("Asset cache wiring")
class AssetCachingIntegrationTest {

    @Autowired
    private AssetService assetService;

    @Autowired
    private AssetLifecycleService assetLifecycleService;

    @MockitoBean
    private AssetRepository assetRepository;

    @MockitoBean
    private CustomerApi customerApi;

    @MockitoBean
    private AssetBondTermsRepository bondTermsRepository;

    private UUID assetId;

    @BeforeEach
    void setUp() {
        assetId = UUID.randomUUID();
        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setStatus(AssetStatus.PENDING_APPROVAL);
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
    }

    @Test
    @DisplayName("a second getAsset call is served from cache, not the repository")
    void getAssetIsCached() {
        assetService.getAsset(assetId);
        assetService.getAsset(assetId);

        verify(assetRepository, times(1)).findById(assetId);
    }

    @Test
    @DisplayName("approving an asset evicts its cached entry")
    void lifecycleTransitionEvictsCache() {
        assetService.getAsset(assetId);
        assetService.getAsset(assetId);
        verify(assetRepository, times(1)).findById(assetId);

        clearInvocations(assetRepository);
        assetLifecycleService.approve(assetId, UUID.randomUUID());
        // approve() reads the row itself once (a direct repository call, not through the cached
        // getAsset()) before evicting the cache entry as a side effect of its own save.
        verify(assetRepository, times(1)).findById(assetId);

        clearInvocations(assetRepository);
        assetService.getAsset(assetId);
        // Proves the eviction actually happened - a stale cache would serve this from memory
        // with zero further repository calls.
        verify(assetRepository, times(1)).findById(assetId);
    }
}
