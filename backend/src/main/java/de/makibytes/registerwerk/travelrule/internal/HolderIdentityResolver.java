package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.travelrule.api.Ivms101;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a wallet address to the registered holder's identity for IVMS-101
 * enrichment (TFR Art. 14(1): the originator information accompanying a
 * transfer must include the originator's <em>name</em>, account identifier,
 * and address or registration number — the wallet address alone is not
 * sufficient).
 *
 * <p>Reads {@code asset_holder} joined with {@code legal_entity} via SQL to
 * avoid a Java-level dependency on the deployment/customer modules. Lookup is
 * case-insensitive because EVM addresses may be stored checksummed (EIP-55)
 * or lowercase.
 */
@Component
class HolderIdentityResolver {

    private final JdbcTemplate jdbc;

    HolderIdentityResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Builds the IVMS-101 identity payload for the holder of {@code walletAddress}
     * on {@code assetId}, or empty if the wallet is not a registered holder
     * (e.g. the beneficiary side of an outbound transfer to another CASP).
     */
    Optional<Ivms101.IdentityPayload> resolve(UUID assetId, String walletAddress) {
        if (assetId == null || walletAddress == null || walletAddress.isBlank()) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT le.current_name, le.registration_country, le.lei_code, le.entity_number
            FROM asset_holder ah
            JOIN legal_entity le ON le.id = ah.investor_id
            WHERE ah.asset_id = ? AND LOWER(ah.wallet_address) = LOWER(?)
            LIMIT 1
            """, assetId, walletAddress);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        String name = (String) row.get("current_name");
        String country = (String) row.get("registration_country");
        String lei = (String) row.get("lei_code");
        String entityNumber = (String) row.get("entity_number");

        Ivms101.LegalPerson legalPerson = new Ivms101.LegalPerson(
                List.of(new Ivms101.LegalPersonNameId(name, Ivms101.LegalPersonNameTypeCode.LEGL)),
                entityNumber);

        Ivms101.NationalIdentification nationalId = lei == null || lei.isBlank()
                ? null
                : new Ivms101.NationalIdentification(
                        lei, Ivms101.NationalIdentifierTypeCode.LEIX, country, null);

        return Optional.of(new Ivms101.IdentityPayload(
                new Ivms101.Person(null, legalPerson),
                null,
                nationalId,
                entityNumber,
                null,
                country == null || country.isBlank() ? null : new Ivms101.CountryOfResidence(country)));
    }
}
