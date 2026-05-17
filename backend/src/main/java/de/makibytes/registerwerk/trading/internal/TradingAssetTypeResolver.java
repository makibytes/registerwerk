package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.trading.internal.TradingAssetType;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class TradingAssetTypeResolver {

    public TradingAssetType resolve(Asset asset) {
        if (asset.getPublicData() == null || asset.getPublicData().isEmpty()) {
            return TradingAssetType.OTHER;
        }
        for (String key : new String[]{"assetType", "assetClass", "category", "type"}) {
            Object value = asset.getPublicData().get(key);
            if (value instanceof String stringValue) {
                TradingAssetType mapped = map(stringValue);
                if (mapped != null) {
                    return mapped;
                }
            }
        }
        return TradingAssetType.OTHER;
    }

    private TradingAssetType map(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return null;
        }
        if (containsAny(value, "equity", "share", "stock")) {
            return TradingAssetType.EQUITY;
        }
        if (containsAny(value, "bond", "debt", "fixed income")) {
            return TradingAssetType.BOND;
        }
        if (containsAny(value, "fund", "etf", "ucits")) {
            return TradingAssetType.FUND;
        }
        if (containsAny(value, "note", "certificate")) {
            return TradingAssetType.NOTE;
        }
        if (containsAny(value, "commodity", "metal", "oil", "energy")) {
            return TradingAssetType.COMMODITY;
        }
        return TradingAssetType.OTHER;
    }

    private boolean containsAny(String source, String... needles) {
        for (String needle : needles) {
            if (source.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
