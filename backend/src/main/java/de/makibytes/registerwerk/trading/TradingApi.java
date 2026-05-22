package de.makibytes.registerwerk.trading;

import de.makibytes.registerwerk.trading.api.TradeListing;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/** Public API for trading venue and listing queries. */
public interface TradingApi {

    Page<TradeListing> listActiveListings(UUID assetId, Pageable pageable);
}
