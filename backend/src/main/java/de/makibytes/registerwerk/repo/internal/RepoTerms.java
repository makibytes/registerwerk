package de.makibytes.registerwerk.repo.internal;

import de.makibytes.registerwerk.repo.api.RepoQuote;
import de.makibytes.registerwerk.repo.api.RepoRfq;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/** Server-side canonical terms digest of a quote within its RFQ; the requester must echo it to accept. */
final class RepoTerms {
    private RepoTerms() {}

    static String hash(RepoRfq rfq, RepoQuote quote) {
        String canonical = String.join("|",
                plain(quote.getCashAmount()), plain(quote.getRepoRate()), Integer.toString(quote.getHaircutBps()),
                quote.getValidUntil().truncatedTo(ChronoUnit.MILLIS).toString(),
                Integer.toString(quote.getQuoteVersion()), rfq.getCashCurrency(),
                rfq.getStartDate().toString(), rfq.getEndDate().toString(),
                rfq.getCollateralAssetId().toString(), plain(rfq.getCollateralQuantity()),
                rfq.getSettlementMethod().name(), quote.getQuotingEntityId().toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
