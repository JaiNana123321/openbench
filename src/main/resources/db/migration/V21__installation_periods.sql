-- The installation's Periods (design Section 4.3: when the Period length is set, first by the
-- installation migration with the default of one calendar month, Settings writes the Periods ten
-- years ahead, each starting the day after the previous one ends). The first Period starts on the
-- first day of the installation month in the company zone, so SettingsService.currentPeriod()
-- finds a Period from the first day on. The length is the period_months setting V20 installs.
-- ${zone} is the company zone (spring.flyway.placeholders.zone, from openbench.zone).
-- A Period that would overlap an existing one is skipped, so an installation that already has
-- Periods keeps them.
WITH length AS (
    SELECT value::int AS months FROM planner_setting WHERE key = 'period_months'
), first AS (
    SELECT date_trunc('month', now() AT TIME ZONE '${zone}') AS starts
)
INSERT INTO period (starts_on, ends_on)
SELECT (first.starts + make_interval(months => n * length.months))::date,
       (first.starts + make_interval(months => (n + 1) * length.months))::date - 1
FROM first, length, generate_series(0, 120 / length.months - 1) AS n
ORDER BY n
ON CONFLICT DO NOTHING;
