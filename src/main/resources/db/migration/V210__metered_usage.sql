-- Actual usage (SRS Metered.Usage.Record, issue #24; design Section 9, "Actual usage recording and
-- comparison"): the units an ALLOCATED Metered allocation actually consumed, recorded by a Resource
-- Owner once its Period has ended. Recording again replaces the value; the row keeps who recorded it
-- last and when. Usage has no Ledger effect, and the hourly check keeps reading units, never these.
ALTER TABLE metered_allocation
    ADD COLUMN used_units        integer     CHECK (used_units >= 0),
    ADD COLUMN usage_recorded_at timestamptz,
    ADD COLUMN usage_recorded_by bigint      REFERENCES member,
    ADD CONSTRAINT metered_allocation_used_within_units CHECK (used_units <= units),
    ADD CONSTRAINT metered_allocation_usage_recorded
        CHECK ((used_units IS NULL) = (usage_recorded_at IS NULL)
               AND (used_units IS NULL) = (usage_recorded_by IS NULL));
