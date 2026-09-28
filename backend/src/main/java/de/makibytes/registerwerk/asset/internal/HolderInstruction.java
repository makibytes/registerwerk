package de.makibytes.registerwerk.asset.internal;

import java.util.UUID;

/**
 * T3-13: the recorded instruction behind an operator-executed register-entry change.
 *
 * @param approverId      second approver of a 4-eyes change, when known
 * @param changeRequestId the issuer's {@code HolderChangeRequest} this execution fulfils, if any
 */
public record HolderInstruction(InstructingParty party, String reference, UUID approverId, UUID changeRequestId) {

    public HolderInstruction(InstructingParty party, String reference) {
        this(party, reference, null, null);
    }

    void validate() {
        if (party == null) {
            throw new IllegalArgumentException("instructingParty is required");
        }
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("instructionReference is required");
        }
    }
}
