-- Finding #2/#5, Phase 9: previously ConfidentialTravelRuleScreeningService advanced its sync
-- cursor past every event in a batch unconditionally, even when a decrypt failed — a transient
-- relayer/KMS hiccup permanently and silently skipped Travel Rule screening for that transfer.
-- This column tracks consecutive screening runs that failed to resolve the earliest unresolved
-- event, so the service can retry a bounded number of times before giving up and advancing past
-- it (logged at ERROR for operational visibility) rather than either retrying forever (risking a
-- permanent wedge if the failure turns out to be non-transient) or silently skipping on the first
-- failure (the original bug).
ALTER TABLE confidential_transfer_screening_state
    ADD COLUMN consecutive_decrypt_failures INT NOT NULL DEFAULT 0;
