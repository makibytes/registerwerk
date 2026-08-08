-- Bind inbound replay protection to the originating VASP and its transfer reference.
-- Multiple counterparties may use the same reference, but one VASP must not create duplicate
-- compliance records by retrying the same delivery.
CREATE UNIQUE INDEX uq_trm_inbound_vasp_transfer_ref
    ON travel_rule_message (originator_vasp_did, protocol_message_id)
    WHERE direction = 'INBOUND' AND protocol_message_id IS NOT NULL;
