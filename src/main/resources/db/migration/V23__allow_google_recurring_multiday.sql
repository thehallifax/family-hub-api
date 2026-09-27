-- Native recurrence remains single-day. Google recurring parents may carry the
-- duration of each occurrence, including overnight and multi-day events.
ALTER TABLE calendar_event DROP CONSTRAINT chk_no_recurring_multiday;

ALTER TABLE calendar_event
    ADD CONSTRAINT chk_no_recurring_multiday
    CHECK (
        recurrence_rule IS NULL
        OR end_date IS NULL
        OR (source = 'GOOGLE' AND end_date >= date)
    );
