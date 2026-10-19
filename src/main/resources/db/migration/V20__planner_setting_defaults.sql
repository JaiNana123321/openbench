-- The installation defaults of the single-valued planner settings (design Section 4.3: the
-- installation migration sets the first values), so SettingsService.current() finds every key on
-- any installation. The Capacity Planner changes them later; the demo seed sets the demo world's.
-- Period length, priority premium, and Objection Window are the SRS example defaults; the SRS
-- gives no Day Rate, so the default is the demo world's.
INSERT INTO planner_setting (key, value) VALUES
    ('period_months', '1'),
    ('priority_premium', '2.0'),
    ('objection_window_working_days', '2'),
    ('day_rate', '400.00');
