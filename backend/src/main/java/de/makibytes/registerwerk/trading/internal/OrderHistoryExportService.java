package de.makibytes.registerwerk.trading.internal;

import de.makibytes.registerwerk.trading.api.TradeExecution;
import de.makibytes.registerwerk.trading.api.TradeExecutionRepository;
import de.makibytes.registerwerk.trading.api.TradeListing;
import de.makibytes.registerwerk.trading.api.TradeListingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Order-record export for a market-abuse tool / regulator request (Phase 5, 5C-06 interim, parked
 * T5-06). One CSV: a {@code LISTING} row per listing created in the window (cancelled listings are
 * retained rows - status flip, never a delete - with their last-change time) and an
 * {@code EXECUTION} row per trade. Fields follow the RTS 22-style order/execution record as far as
 * the interim states; this is not a validated transaction report and no STOR/insider hook exists.
 */
@Service
@Transactional(readOnly = true)
public class OrderHistoryExportService {

    static final String HEADER = String.join(",", "recordType", "id", "listingId", "recordedAt", "lastChangedAt",
            "status", "sellerEntityId", "buyerEntityId", "targetEntityId", "submittedByUserId", "assetId", "isin",
            "assetNumber", "quantity", "remainingQuantity", "unitPrice", "totalPrice", "currency", "paymentRailCode",
            "paymentOption", "relatedParty", "relatedPartyReasons", "venueCode", "venueClassification");

    private final TradeListingRepository listingRepository;
    private final TradeExecutionRepository executionRepository;

    OrderHistoryExportService(TradeListingRepository listingRepository, TradeExecutionRepository executionRepository) {
        this.listingRepository = listingRepository;
        this.executionRepository = executionRepository;
    }

    public byte[] exportCsv(Instant from, Instant to) {
        List<Row> rows = new ArrayList<>();
        for (TradeListing l : listingRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(from, to)) {
            rows.add(row(l.getCreatedAt(), new String[] {"LISTING", str(l.getId()), str(l.getId()), str(l.getCreatedAt()), str(l.getUpdatedAt()),
                    str(l.getStatus()), str(l.getSellerEntityId()), "", str(l.getTargetEntityId()),
                    str(l.getCreatedByActorId()), str(l.getAssetId()), str(l.getIsin()), str(l.getAssetNumber()),
                    dec(l.getQuantityTotal()), dec(l.getQuantityAvailable()), dec(l.getPricePerUnit()), "",
                    str(l.getCurrency()), str(l.getPaymentRailCode()),
                    l.getAllowedPaymentOptions().stream().map(Enum::name).sorted().collect(Collectors.joining("|")),
                    "", "", str(l.getVenueCode()), str(l.getVenueClassification())}));
        }
        for (TradeExecution e : executionRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(from, to)) {
            rows.add(row(e.getCreatedAt(), new String[] {"EXECUTION", str(e.getId()), str(e.getListingId()), str(e.getCreatedAt()),
                    str(e.getSettledAt() != null ? e.getSettledAt() : e.getCreatedAt()), str(e.getSettlementStatus()),
                    str(e.getSellerEntityId()), str(e.getBuyerEntityId()), "", str(e.getCreatedByActorId()),
                    str(e.getAssetId()), str(e.getIsin()), str(e.getAssetNumber()), dec(e.getExecutedQuantity()), "",
                    dec(e.getUnitPrice()), dec(e.getTotalPrice()), str(e.getCurrency()), str(e.getPaymentRailCode()),
                    str(e.getPaymentOption()), String.valueOf(e.isRelatedParty()), str(e.getRelatedPartyReasons()),
                    str(e.getVenueCode()), str(e.getVenueClassification())}));
        }
        // sort on the Instant (its ISO string drops zero fractions and mis-orders inside one second), tie-break by id
        rows.sort(Comparator.comparing(Row::at, Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(Row::id));
        StringBuilder csv = new StringBuilder(HEADER).append("\r\n");
        for (Row r : rows) {
            String[] row = r.cells();
            for (int i = 0; i < row.length; i++) {
                if (i > 0) {
                    csv.append(',');
                }
                csv.append(escape(row[i]));
            }
            csv.append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private record Row(Instant at, String id, String[] cells) {}

    private static Row row(Instant at, String[] cells) {
        return new Row(at, cells[1], cells);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String dec(java.math.BigDecimal d) {
        return d == null ? "" : d.toPlainString();
    }

    /** RFC 4180 quoting plus a leading apostrophe against spreadsheet formula injection. */
    static String escape(String value) {
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
