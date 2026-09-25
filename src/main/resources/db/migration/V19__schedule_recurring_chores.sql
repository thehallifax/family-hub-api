-- Optional schedule fields preserve every existing template and completion.
-- Java DayOfWeek names are locale-independent, including SUNDAY.
ALTER TABLE chore_template
    ADD COLUMN due_weekday VARCHAR(9),
    ADD COLUMN due_day_of_month INTEGER;

ALTER TABLE chore_template
    ADD CONSTRAINT chk_chore_due_weekday
        CHECK (due_weekday IS NULL OR due_weekday IN
            ('SUNDAY','MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY')),
    ADD CONSTRAINT chk_chore_due_day_of_month
        CHECK (due_day_of_month IS NULL OR due_day_of_month BETWEEN 1 AND 31),
    ADD CONSTRAINT chk_chore_schedule_cadence
        CHECK ((cadence = 'DAILY' AND due_weekday IS NULL AND due_day_of_month IS NULL)
            OR (cadence = 'WEEKLY' AND due_day_of_month IS NULL)
            OR (cadence = 'MONTHLY' AND due_weekday IS NULL));
