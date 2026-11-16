-- The demand surcharge (SRS Ledger.Surcharge, issue #25): a per-type rate that raises a Quote with
-- how full the type already is in the line's window when it is quoted. The multiplier is
-- 1 + rate x peak fill, the fill capped at 1; a rate of 0 turns it off. It locks with the Quote.
ALTER TABLE type_pricing
    ADD COLUMN surcharge_rate numeric(6,3) NOT NULL DEFAULT 0 CHECK (surcharge_rate >= 0);
