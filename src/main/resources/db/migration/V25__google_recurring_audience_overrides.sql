-- Google recurring parents provide the default FamilyHub audience. An edited
-- occurrence can explicitly override that local-only metadata without changing
-- Google attendees or preventing later parent audience changes from flowing to
-- ordinary instances.
ALTER TABLE calendar_event
    ADD COLUMN google_audience_override BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE calendar_event
    ADD CONSTRAINT chk_google_audience_override
    CHECK (
        google_audience_override = FALSE
        OR (source = 'GOOGLE' AND recurring_event_id IS NOT NULL)
    );
