package de.makibytes.registerwerk.infrastructure;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * "assets" ({@code AssetService.getAsset}) and "finalityPolicies" ({@code
 * FinalityPolicyResolverImpl.requiredLevel}) are the two caches actually wired to a read path.
 * Both are evicted by every writer that can invalidate them — "assets" by {@code AssetService}/
 * {@code AssetLifecycleService}, "finalityPolicies" fully (not per-key: a GLOBAL or
 * TOKEN_STANDARD change can affect any asset, so there is no narrower correct eviction) by every
 * mutating method on {@code FinalityPolicyAdminService}. "deployments" and "entities" are
 * deliberately left unused for now: {@code AssetDeployment} rows are rewritten by a 30s poller
 * while PENDING (an operator actively watching a live deployment would see stale status for up to
 * this cache's own TTL, on top of the poller's own lag), and {@code LegalEntity.kycStatus} is
 * mutated from the {@code kyc}/{@code onboarding} modules rather than through {@code
 * LegalEntityService} - missing any of those eviction points would let a KYC gate serve a stale
 * approval/rejection for up to the TTL, which is a compliance risk, not just a UX one. Revisit
 * either once there's a caller sensitive to a few extra queries per request.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("entities", "assets", "deployments", "finalityPolicies");
        manager.setCaffeine(
            Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .maximumSize(1000)
                // Spring Boot registers these caches with Micrometer. Without statistics,
                // cache hit/miss/eviction meters exist but remain unavailable at runtime.
                .recordStats()
        );
        return manager;
    }
}
