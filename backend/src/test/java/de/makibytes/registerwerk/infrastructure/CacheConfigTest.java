package de.makibytes.registerwerk.infrastructure;

import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCache;

import static org.assertj.core.api.Assertions.assertThat;

class CacheConfigTest {

    @Test
    void applicationCachesRecordStatisticsForMicrometer() {
        CaffeineCache cache = (CaffeineCache) new CacheConfig().cacheManager().getCache("assets");
        assertThat(cache).isNotNull();

        cache.get("missing-key");

        @SuppressWarnings("unchecked")
        Cache<Object, Object> nativeCache = (Cache<Object, Object>) cache.getNativeCache();
        assertThat(nativeCache.stats().requestCount()).isEqualTo(1);
        assertThat(nativeCache.stats().missCount()).isEqualTo(1);
    }
}
