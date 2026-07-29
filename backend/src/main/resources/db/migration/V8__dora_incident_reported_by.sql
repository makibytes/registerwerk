-- Fix (Phase 5 finding #5): markReportedToAuthority had no actor at all — nobody was
-- attributable for filing (or choosing not to file) a DORA Art. 19 authority notification,
-- arguably the single most examination-sensitive action in this module.
ALTER TABLE ict_incident
    ADD COLUMN reported_by UUID;
