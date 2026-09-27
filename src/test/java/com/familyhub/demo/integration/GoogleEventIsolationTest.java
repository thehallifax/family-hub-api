package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.model.GoogleSyncedCalendar;
import com.familyhub.demo.repository.GoogleSyncedCalendarRepository;
import com.familyhub.demo.service.GoogleCalendarSyncService;
import com.familyhub.demo.service.GoogleCalendarSelectionService;
import com.familyhub.demo.service.GoogleOAuthService;
import com.familyhub.demo.service.CalendarEventService;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class GoogleEventIsolationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired GoogleSyncedCalendarRepository calendars;
    @Autowired GoogleCalendarSyncService sync;
    @Autowired GoogleCalendarSelectionService selections;
    @Autowired GoogleOAuthService oauth;
    @Autowired CalendarEventService events;

    private GoogleSyncedCalendar calendar() {
        UUID family = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID calendar = UUID.randomUUID();
        jdbc.update("INSERT INTO family (id,name,username,password_hash) VALUES (?,?,?,?)",
                family, "Test", family.toString(), "hash");
        jdbc.update("INSERT INTO family_member (id,family_id,name,color) VALUES (?,?,?,?)",
                member, family, "Member", "CORAL");
        jdbc.update("INSERT INTO google_oauth_token (id,member_id,access_token,refresh_token,token_expiry,scope) VALUES (?,?,?,?,now(),'scope')",
                token, member, "encrypted", "encrypted");
        jdbc.update("INSERT INTO google_synced_calendar (id,token_id,member_id,google_calendar_id,calendar_name) VALUES (?,?,?,?,?)",
                calendar, token, member, calendar.toString(), "Calendar");
        return calendars.findById(calendar).orElseThrow();
    }

    private Event event(String id, String title) {
        return new Event().setId(id).setSummary(title)
                .setStart(new EventDateTime().setDate(new DateTime("2025-06-15")))
                .setEnd(new EventDateTime().setDate(new DateTime("2025-06-16")));
    }

    private GoogleSyncedCalendar secondCalendarForSameMember(GoogleSyncedCalendar first) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO google_synced_calendar (id,token_id,member_id,google_calendar_id,calendar_name)
                SELECT ?,token_id,member_id,?,? FROM google_synced_calendar WHERE id=?
                """, id, id.toString(), "Other calendar", first.getId());
        return calendars.findById(id).orElseThrow();
    }

    @Test
    void fullSyncCommitsRecurringGoogleSpansWithCorrectOwnershipAndExpansion() {
        GoogleSyncedCalendar selected = calendar();
        GoogleSyncedCalendar otherCalendar = secondCalendarForSameMember(selected);
        GoogleSyncedCalendar otherFamily = calendar();
        String sharedId = "series-" + UUID.randomUUID();
        Event overnight = new Event().setId(sharedId).setSummary("Overnight")
                .setStart(new EventDateTime().setDateTime(new DateTime("2025-06-15T23:00:00+08:00")))
                .setEnd(new EventDateTime().setDateTime(new DateTime("2025-06-16T08:00:00+08:00")))
                .setRecurrence(List.of("RRULE:FREQ=WEEKLY;BYDAY=SU"));
        Event multiDay = new Event().setId("all-day-" + UUID.randomUUID()).setSummary("Multi-day")
                .setStart(new EventDateTime().setDate(new DateTime("2025-06-15")))
                .setEnd(new EventDateTime().setDate(new DateTime("2025-06-18")))
                .setRecurrence(List.of("RRULE:FREQ=WEEKLY;BYDAY=SU"));
        sync.persistFullSync(otherCalendar, List.of(event(sharedId, "Other calendar")));
        sync.persistFullSync(otherFamily, List.of(event(sharedId, "Other family")));

        sync.persistFullSync(selected, List.of(overnight, multiDay));

        var row = jdbc.queryForMap("""
                SELECT e.source,e.family_id,e.source_owner_member_id,e.synced_calendar_id,e.date,
                       e.end_date,e.start_time,e.end_time,e.recurrence_rule,em.member_id
                FROM calendar_event e JOIN calendar_event_member em ON em.event_id=e.id
                WHERE e.synced_calendar_id=? AND e.google_event_id=?
                """, selected.getId(), sharedId);
        assertThat(row.get("source")).isEqualTo("GOOGLE");
        assertThat(row.get("family_id")).isEqualTo(selected.getMember().getFamily().getId());
        assertThat(row.get("source_owner_member_id")).isEqualTo(selected.getMember().getId());
        assertThat(row.get("member_id")).isEqualTo(selected.getMember().getId());
        assertThat(row.get("synced_calendar_id")).isEqualTo(selected.getId());
        assertThat(row.get("date").toString()).isEqualTo("2025-06-15");
        assertThat(row.get("end_date").toString()).isEqualTo("2025-06-16");
        assertThat(((java.sql.Time) row.get("start_time")).toLocalTime()).isEqualTo(java.time.LocalTime.of(23, 0));
        assertThat(((java.sql.Time) row.get("end_time")).toLocalTime()).isEqualTo(java.time.LocalTime.of(8, 0));
        assertThat(row.get("recurrence_rule")).isEqualTo("FREQ=WEEKLY;BYDAY=SU");
        assertThat(jdbc.queryForObject("SELECT end_date FROM calendar_event WHERE synced_calendar_id=? AND google_event_id=?",
                java.sql.Date.class, selected.getId(), multiDay.getId()).toLocalDate())
                .isEqualTo(java.time.LocalDate.of(2025, 6, 17));
        assertThat(events.getAllEventsByFamily(selected.getMember().getFamily(),
                java.time.LocalDate.of(2025, 6, 23), java.time.LocalDate.of(2025, 6, 23), null))
                .extracting(com.familyhub.demo.dto.CalendarEventResponse::endDate)
                .contains(java.time.LocalDate.of(2025, 6, 23), java.time.LocalDate.of(2025, 6, 24));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE synced_calendar_id=? AND google_event_id=?", Integer.class,
                otherCalendar.getId(), sharedId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE synced_calendar_id=? AND google_event_id=?", Integer.class,
                otherFamily.getId(), sharedId)).isEqualTo(1);
    }

    @Test
    void invalidFullSyncRollsBackWithoutTouchingOtherOwnersOrNativeEvents() {
        GoogleSyncedCalendar selected = calendar();
        GoogleSyncedCalendar otherCalendar = secondCalendarForSameMember(selected);
        GoogleSyncedCalendar otherFamily = calendar();
        String sharedId = "survivor-" + UUID.randomUUID();
        sync.persistFullSync(selected, List.of(event(sharedId, "Selected survivor")));
        sync.persistFullSync(otherCalendar, List.of(event(sharedId, "Other calendar")));
        sync.persistFullSync(otherFamily, List.of(event(sharedId, "Other family")));
        UUID nativeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO calendar_event (id,title,start_time,end_time,date,family_id,source)
                VALUES (?,'Native','09:00','10:00','2025-06-15',?,'NATIVE')
                """, nativeId, selected.getMember().getFamily().getId());
        Event invalid = new Event().setId("invalid-" + UUID.randomUUID())
                .setSummary("X".repeat(300))
                .setStart(new EventDateTime().setDate(new DateTime("2025-06-15")))
                .setEnd(new EventDateTime().setDate(new DateTime("2025-06-16")));

        assertThatThrownBy(() -> sync.persistFullSync(selected, List.of(invalid)))
                .hasStackTraceContaining("value too long for type character varying(255)");

        assertThat(jdbc.queryForObject("SELECT title FROM calendar_event WHERE synced_calendar_id=? AND google_event_id=?",
                String.class, selected.getId(), sharedId)).isEqualTo("Selected survivor");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE synced_calendar_id=? AND google_event_id=?",
                Integer.class, selected.getId(), invalid.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE synced_calendar_id=? AND google_event_id=?",
                Integer.class, otherCalendar.getId(), sharedId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE synced_calendar_id=? AND google_event_id=?",
                Integer.class, otherFamily.getId(), sharedId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE id=?", Integer.class, nativeId)).isEqualTo(1);
    }

    @Test
    void completedDiscoveryRemovesOnlyMissingCalendarsImportedRows() {
        GoogleSyncedCalendar missing = calendar();
        GoogleSyncedCalendar sameMember = secondCalendarForSameMember(missing);
        GoogleSyncedCalendar otherFamily = calendar();
        String sharedId = "shared-" + UUID.randomUUID();
        sync.persistFullSync(missing, List.of(event(sharedId, "Missing")));
        sync.persistFullSync(sameMember, List.of(event(sharedId, "Same member, other calendar")));
        sync.persistFullSync(otherFamily, List.of(event(sharedId, "Other family")));
        UUID nativeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO calendar_event
                (id,title,start_time,end_time,date,family_id,source)
                VALUES (?,'Native','09:00','10:00','2025-06-15',?,'NATIVE')
                """, nativeId, missing.getMember().getFamily().getId());

        selections.disableMissingCalendars(missing.getMember().getId(),
                java.util.Set.of(missing.getId(), sameMember.getId()), java.util.Set.of(sameMember.getGoogleCalendarId()));

        assertThat(calendars.findById(missing.getId()).orElseThrow().isEnabled()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE synced_calendar_id=?", Integer.class, missing.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE synced_calendar_id=? AND google_event_id=?", Integer.class, sameMember.getId(), sharedId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE synced_calendar_id=? AND google_event_id=?", Integer.class, otherFamily.getId(), sharedId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE id=?", Integer.class, nativeId)).isEqualTo(1);

        // A fetch started before discovery must not repopulate the disabled calendar.
        sync.persistFullSync(missing, List.of(event("late-" + sharedId, "Late")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE synced_calendar_id=?", Integer.class, missing.getId())).isZero();
    }

    @Test
    void fullReconciliationRemovesAbsentRowsAndKeepsSurvivorsAndOtherCalendars() {
        GoogleSyncedCalendar selected = calendar();
        GoogleSyncedCalendar other = secondCalendarForSameMember(selected);
        String stale = "stale-" + UUID.randomUUID();
        String survivor = "survivor-" + UUID.randomUUID();
        sync.persistFullSync(selected, List.of(event(stale, "Gone from Google"), event(survivor, "Old title")));
        sync.persistFullSync(other, List.of(event(stale, "Another calendar")));

        sync.persistFullSync(selected, List.of(event(survivor, "Current title")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE synced_calendar_id=? AND google_event_id=?", Integer.class, selected.getId(), stale)).isZero();
        assertThat(jdbc.queryForObject("SELECT title FROM calendar_event WHERE synced_calendar_id=? AND google_event_id=?", String.class, selected.getId(), survivor)).isEqualTo("Current title");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE synced_calendar_id=? AND google_event_id=?", Integer.class, other.getId(), stale)).isEqualTo(1);
    }

    @Test
    void fullReconciliationPreservesCancelledInstanceAndRemovesCancelledParent() {
        GoogleSyncedCalendar selected = calendar();
        String parentId = "parent-" + UUID.randomUUID();
        String instanceId = parentId + "_20250615";
        Event parent = event(parentId, "Recurring").setRecurrence(List.of("RRULE:FREQ=DAILY"));
        Event cancelledInstance = new Event().setId(instanceId).setStatus("cancelled")
                .setRecurringEventId(parentId)
                .setOriginalStartTime(new EventDateTime().setDate(new DateTime("2025-06-15")));

        sync.persistFullSync(selected, List.of(parent, cancelledInstance));

        assertThat(jdbc.queryForObject("SELECT is_cancelled FROM calendar_event WHERE synced_calendar_id=? AND google_event_id=?", Boolean.class, selected.getId(), instanceId)).isTrue();
        sync.persistFullSync(selected, List.of(new Event().setId(parentId).setStatus("cancelled")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE synced_calendar_id=?", Integer.class, selected.getId())).isZero();
    }

    @Test
    void importedGoogleEventHasConnectedMemberAudienceAndSeparateSourceOwner() {
        GoogleSyncedCalendar calendar = calendar();
        String id = "audience-" + UUID.randomUUID();
        sync.persistIncrementalChanges(calendar, List.of(event(id, "Imported")));
        var row = jdbc.queryForMap("""
                SELECT e.audience_type, e.source_owner_member_id, em.member_id, e.synced_calendar_id
                FROM calendar_event e JOIN calendar_event_member em ON em.event_id=e.id
                WHERE e.google_event_id=? AND e.synced_calendar_id=?
                """, id, calendar.getId());
        assertThat(row.get("audience_type")).isEqualTo("MEMBERS");
        assertThat(row.get("source_owner_member_id")).isEqualTo(calendar.getMember().getId());
        assertThat(row.get("member_id")).isEqualTo(calendar.getMember().getId());
        assertThat(row.get("synced_calendar_id")).isEqualTo(calendar.getId());
    }

    @Test
    void incrementalUpdateCannotModifyAnotherCalendarWithSameGoogleId() {
        GoogleSyncedCalendar a = calendar();
        GoogleSyncedCalendar b = calendar();
        String id = "shared-" + UUID.randomUUID();
        sync.persistIncrementalChanges(b, List.of(event(id, "B original")));
        sync.persistIncrementalChanges(a, List.of(event(id, "A new")));
        assertThat(jdbc.queryForObject("SELECT title FROM calendar_event WHERE synced_calendar_id=? AND google_event_id=?", String.class, b.getId(), id))
                .isEqualTo("B original");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE google_event_id=?", Integer.class, id))
                .isEqualTo(2);
    }

    @Test
    void incrementalCancellationCannotDeleteAnotherCalendarWithSameGoogleId() {
        GoogleSyncedCalendar a = calendar();
        GoogleSyncedCalendar b = calendar();
        String id = "shared-" + UUID.randomUUID();
        sync.persistIncrementalChanges(b, List.of(event(id, "B original")));
        sync.persistIncrementalChanges(a, List.of(new Event().setId(id).setStatus("cancelled")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE synced_calendar_id=? AND google_event_id=?", Integer.class, b.getId(), id))
                .isEqualTo(1);
    }

    @Test
    void recurringExceptionUsesParentFromItsOwnCalendar() {
        GoogleSyncedCalendar a = calendar();
        GoogleSyncedCalendar b = calendar();
        String parentId = "parent-" + UUID.randomUUID();
        Event parentA = event(parentId, "A parent").setRecurrence(List.of("RRULE:FREQ=DAILY"));
        Event parentB = event(parentId, "B parent").setRecurrence(List.of("RRULE:FREQ=DAILY"));
        sync.persistFullSync(a, List.of(parentA));
        sync.persistFullSync(b, List.of(parentB));

        Event exception = event(parentId + "_20250615", "A exception")
                .setRecurringEventId(parentId)
                .setOriginalStartTime(new EventDateTime().setDate(new DateTime("2025-06-15")));
        sync.persistIncrementalChanges(a, List.of(exception));
        UUID parent = jdbc.queryForObject("SELECT id FROM calendar_event WHERE synced_calendar_id=? AND google_event_id=?", UUID.class, a.getId(), parentId);
        UUID exceptionParent = jdbc.queryForObject("SELECT recurring_event_id FROM calendar_event WHERE synced_calendar_id=? AND google_event_id=?", UUID.class, a.getId(), exception.getId());
        assertThat(exceptionParent).isEqualTo(parent);
    }

    @Test
    void disconnectRemovesOnlyMembersGoogleEventsAndKeepsNativeEvents() {
        GoogleSyncedCalendar a = calendar();
        GoogleSyncedCalendar b = calendar();
        String id = "shared-" + UUID.randomUUID();
        sync.persistIncrementalChanges(a, List.of(event(id, "A")));
        sync.persistIncrementalChanges(b, List.of(event(id, "B")));
        UUID nativeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO calendar_event
                (id,title,start_time,end_time,date,family_id,source)
                VALUES (?,'Native','09:00','10:00','2025-06-15',?,'NATIVE')
                """, nativeId, a.getMember().getFamily().getId());
        jdbc.update("INSERT INTO calendar_event_member (event_id,member_id) VALUES (?,?)",
                nativeId, a.getMember().getId());

        oauth.disconnect(a.getMember().getId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE synced_calendar_id=? AND google_event_id=?", Integer.class, b.getId(), id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE id=?", Integer.class, nativeId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE source_owner_member_id=? AND source='GOOGLE'", Integer.class, a.getMember().getId())).isZero();
    }
}
