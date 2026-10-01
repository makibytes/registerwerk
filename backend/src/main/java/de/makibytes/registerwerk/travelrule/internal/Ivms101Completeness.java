package de.makibytes.registerwerk.travelrule.internal;

import de.makibytes.registerwerk.travelrule.api.Ivms101;

import java.util.ArrayList;
import java.util.List;

/**
 * Completeness rules for IVMS-101 messages under TFR Art. 14(1)/(2) (outbound) and Art. 16(1) (inbound).
 * Originator: name, account identifier, and one of address / official identification / customer
 * identification / date and place of birth. Beneficiary: name and account identifier.
 */
final class Ivms101Completeness {

    private Ivms101Completeness() {}

    static List<String> missingForOutbound(Ivms101.TravelRuleMessage m) {
        List<String> missing = new ArrayList<>();
        if (m.originatingVasp() == null || m.originatingVasp().originatingVasp() == null
                || blank(m.originatingVasp().originatingVasp().vaspId())) {
            missing.add("originatingVasp");
        }
        if (m.beneficiaryVasp() == null || m.beneficiaryVasp().beneficiaryVasp() == null
                || blank(m.beneficiaryVasp().beneficiaryVasp().vaspId())) {
            missing.add("beneficiaryVasp");
        }
        if (m.transferDetails() == null || blank(m.transferDetails().transactionIdentifier())
                || blank(m.transferDetails().instructedAmount()) || blank(m.transferDetails().currencyOfTransfer())) {
            missing.add("transferDetails");
        }
        missing.addAll(originator(m));
        missing.addAll(beneficiary(m));
        return missing;
    }

    static List<String> missingForInbound(Ivms101.TravelRuleMessage m) {
        List<String> missing = new ArrayList<>(originator(m));
        missing.addAll(beneficiary(m));
        return missing;
    }

    private static List<String> originator(Ivms101.TravelRuleMessage m) {
        List<String> missing = new ArrayList<>();
        Ivms101.Originator o = m.originator() == null || m.originator().isEmpty() ? null : m.originator().get(0);
        if (o == null || blank(o.accountNumber())) {
            missing.add("originator.accountNumber");
        }
        Ivms101.IdentityPayload id = o == null ? null : o.originatorPersons();
        if (!hasName(id)) {
            missing.add("originator.name");
        }
        if (id == null || (id.geographicAddress() == null && id.nationalIdentification() == null
                && blank(id.customerIdentification()) && id.dateAndPlaceOfBirth() == null)) {
            missing.add("originator.address-or-identification");
        }
        return missing;
    }

    private static List<String> beneficiary(Ivms101.TravelRuleMessage m) {
        List<String> missing = new ArrayList<>();
        Ivms101.Beneficiary b = m.beneficiary() == null || m.beneficiary().isEmpty() ? null : m.beneficiary().get(0);
        if (b == null || blank(b.accountNumber())) {
            missing.add("beneficiary.accountNumber");
        }
        if (b == null || !hasName(b.beneficiaryPersons())) {
            missing.add("beneficiary.name");
        }
        return missing;
    }

    private static boolean hasName(Ivms101.IdentityPayload id) {
        if (id == null || id.person() == null) {
            return false;
        }
        Ivms101.LegalPerson lp = id.person().legalPerson();
        if (lp != null && lp.name() != null && lp.name().stream().anyMatch(n -> n != null && !blank(n.legalPersonName()))) {
            return true;
        }
        Ivms101.NaturalPerson np = id.person().naturalPerson();
        return np != null && np.name() != null && np.name().stream()
                .anyMatch(n -> n != null && !blank(n.primaryIdentifier()));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
