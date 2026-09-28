-- T3-04: bond terms carry the conventions a coupon schedule is generated from (ICMA / TARGET2
-- defaults), and each generated coupon row carries its accrual period, record and announcement
-- dates and the unrounded day-count fraction. scheduled_date stays the adjusted payment date so
-- existing queries keep working; rows created before this migration keep NULL period columns.

ALTER TABLE asset_bond_terms
    ADD COLUMN business_day_convention VARCHAR(24) NOT NULL DEFAULT 'MODIFIED_FOLLOWING',
    ADD COLUMN holiday_calendar        VARCHAR(16) NOT NULL DEFAULT 'TARGET2',
    ADD COLUMN record_date_offset_bd   INTEGER     NOT NULL DEFAULT 1,
    ADD COLUMN announcement_lead_bd    INTEGER     NOT NULL DEFAULT 5,
    ADD COLUMN interest_grace_days     INTEGER     NOT NULL DEFAULT 30,
    ADD COLUMN principal_grace_days    INTEGER     NOT NULL DEFAULT 7,
    ADD COLUMN stub_rule               VARCHAR(16) NOT NULL DEFAULT 'SHORT_FIRST',
    ADD CONSTRAINT ck_bond_terms_schedule_offsets CHECK (
        record_date_offset_bd >= 0 AND announcement_lead_bd >= 0
        AND interest_grace_days >= 0 AND principal_grace_days >= 0);

ALTER TABLE asset_coupon_payment
    ADD COLUMN period_start       DATE,
    ADD COLUMN period_end         DATE,
    ADD COLUMN unadjusted_date    DATE,
    ADD COLUMN record_date        DATE,
    ADD COLUMN announcement_date  DATE,
    ADD COLUMN day_count_fraction NUMERIC(38,18),
    ADD COLUMN schedule_version   INTEGER NOT NULL DEFAULT 1;
