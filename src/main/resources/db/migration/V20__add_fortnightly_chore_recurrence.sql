-- Existing V19 templates retain their cadence, schedules and completions.
ALTER TABLE chore_template ADD COLUMN recurrence_anchor_date DATE;

ALTER TABLE chore_template DROP CONSTRAINT chk_chore_schedule_cadence;
ALTER TABLE chore_template ADD CONSTRAINT chk_chore_schedule_cadence
    CHECK ((cadence = 'DAILY' AND due_weekday IS NULL AND due_day_of_month IS NULL AND recurrence_anchor_date IS NULL)
        OR (cadence = 'WEEKLY' AND due_day_of_month IS NULL AND recurrence_anchor_date IS NULL)
        OR (cadence = 'FORTNIGHTLY' AND due_weekday IS NOT NULL AND due_day_of_month IS NULL
            AND recurrence_anchor_date IS NOT NULL
            AND EXTRACT(ISODOW FROM recurrence_anchor_date) = CASE due_weekday
                WHEN 'MONDAY' THEN 1 WHEN 'TUESDAY' THEN 2 WHEN 'WEDNESDAY' THEN 3
                WHEN 'THURSDAY' THEN 4 WHEN 'FRIDAY' THEN 5 WHEN 'SATURDAY' THEN 6
                WHEN 'SUNDAY' THEN 7 END)
        OR (cadence = 'MONTHLY' AND due_weekday IS NULL AND recurrence_anchor_date IS NULL));
