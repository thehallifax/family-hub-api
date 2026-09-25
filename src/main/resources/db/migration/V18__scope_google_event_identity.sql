-- Google Calendar event IDs are unique within a Google calendar, not globally.
-- Retain existing rows unchanged, including legacy rows without a synced calendar.
DROP INDEX idx_calendar_event_google_id;

CREATE UNIQUE INDEX idx_calendar_event_google_calendar_event
    ON calendar_event (synced_calendar_id, google_event_id)
    WHERE source = 'GOOGLE' AND synced_calendar_id IS NOT NULL AND google_event_id IS NOT NULL;

-- Legacy unscoped imports cannot be assigned to a calendar safely. Keep them
-- unique among themselves until an operator can reconcile their provenance.
CREATE UNIQUE INDEX idx_calendar_event_legacy_google_id
    ON calendar_event (google_event_id)
    WHERE source = 'GOOGLE' AND synced_calendar_id IS NULL AND google_event_id IS NOT NULL;
