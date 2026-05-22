package de.makibytes.registerwerk.travelrule.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * IVMS-101 (InterVASP Messaging Standard) data model records.
 * Implements FATF Recommendation 16 / Regulation (EU) 2023/1113 (TFR).
 * Threshold: DE/EU/LI/LU/FR = €1,000.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class Ivms101 {

    private Ivms101() {}

    /** Full IVMS-101 identity record (originator or beneficiary). */
    public record IdentityPayload(
            Person person,
            GeographicAddress geographicAddress,
            NationalIdentification nationalIdentification,
            String customerIdentification,
            DateAndPlaceOfBirth dateAndPlaceOfBirth,
            CountryOfResidence countryOfResidence
    ) {}

    public record Person(
            NaturalPerson naturalPerson,
            LegalPerson legalPerson
    ) {}

    public record NaturalPerson(
            List<NaturalPersonNameId> name,
            List<String> customerNumber
    ) {}

    public record NaturalPersonNameId(
            String primaryIdentifier,   // family name
            String secondaryIdentifier, // given name(s)
            NaturalPersonNameTypeCode nameIdentifierType
    ) {}

    public enum NaturalPersonNameTypeCode { ALIA, BIRT, MAID, LEGL, MISC }

    public record LegalPerson(
            List<LegalPersonNameId> name,
            String customerNumber
    ) {}

    public record LegalPersonNameId(
            String legalPersonName,
            LegalPersonNameTypeCode legalPersonNameIdentifierType
    ) {}

    public enum LegalPersonNameTypeCode { LEGL, SHRT, TRAD }

    public record GeographicAddress(
            AddressTypeCode addressType,
            List<String> streetName,
            String buildingNumber,
            String buildingName,
            String postCode,
            String townName,
            String countrySubDivision,
            String country          // ISO-3166-1 alpha-2
    ) {}

    public enum AddressTypeCode { HOME, BIZZ, GEOG }

    public record NationalIdentification(
            String nationalIdentifier,
            NationalIdentifierTypeCode nationalIdentifierType,
            String countryOfIssue,
            String registrationAuthority
    ) {}

    public enum NationalIdentifierTypeCode { ARNU, CCPT, RAID, DRLC, FIIN, TXID, SOCS, IDCD, LEIX, MISC }

    public record DateAndPlaceOfBirth(
            String dateOfBirth,     // ISO-8601 date
            String placeOfBirth
    ) {}

    public record CountryOfResidence(String country) {}

    /** Full Travel Rule message payload wrapping originator + beneficiary. */
    public record TravelRuleMessage(
            OriginatingVasp originatingVasp,
            List<Originator> originator,
            BeneficiaryVasp beneficiaryVasp,
            List<Beneficiary> beneficiary,
            TransferDetails transferDetails
    ) {}

    public record OriginatingVasp(VaspIdentity originatingVasp) {}
    public record BeneficiaryVasp(VaspIdentity beneficiaryVasp) {}

    public record VaspIdentity(
            String vaspId,          // LEI / DID / BIRT
            String legalName
    ) {}

    public record Originator(IdentityPayload originatorPersons, String accountNumber) {}
    public record Beneficiary(IdentityPayload beneficiaryPersons, String accountNumber) {}

    public record TransferDetails(
            String transactionIdentifier,
            String executionDate,
            String instructedAmount,
            String currencyOfTransfer,
            String settlementMethod
    ) {}
}
