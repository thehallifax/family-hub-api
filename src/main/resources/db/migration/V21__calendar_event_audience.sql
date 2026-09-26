-- Preserve every legacy assignment, including recurring exceptions and Google rows.
ALTER TABLE calendar_event ADD COLUMN audience_type VARCHAR(16) NOT NULL DEFAULT 'MEMBERS';
ALTER TABLE calendar_event ADD CONSTRAINT chk_calendar_event_audience_type
    CHECK (audience_type IN ('FAMILY', 'MEMBERS'));

CREATE TABLE calendar_event_member (
    event_id UUID NOT NULL REFERENCES calendar_event(id) ON DELETE CASCADE,
    member_id UUID NOT NULL REFERENCES family_member(id) ON DELETE CASCADE,
    PRIMARY KEY (event_id, member_id)
);
INSERT INTO calendar_event_member (event_id, member_id)
SELECT id, member_id FROM calendar_event;
CREATE INDEX idx_calendar_event_member_member_event
    ON calendar_event_member (member_id, event_id);

-- Google provenance is independent of the people an event applies to.
-- Legacy unscoped Google imports still need an owner for disconnect cleanup.
ALTER TABLE calendar_event RENAME COLUMN member_id TO source_owner_member_id;
ALTER TABLE calendar_event ALTER COLUMN source_owner_member_id DROP NOT NULL;
UPDATE calendar_event SET source_owner_member_id = NULL WHERE source = 'NATIVE';
ALTER TABLE calendar_event ADD CONSTRAINT chk_calendar_event_source_owner
    CHECK (source <> 'GOOGLE' OR source_owner_member_id IS NOT NULL);
CREATE INDEX idx_calendar_event_source_owner
    ON calendar_event (source_owner_member_id) WHERE source = 'GOOGLE';
