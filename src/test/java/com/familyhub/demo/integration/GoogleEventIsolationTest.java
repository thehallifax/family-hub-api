package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.model.GoogleSyncedCalendar;
import com.familyhub.demo.repository.GoogleSyncedCalendarRepository;
import com.familyhub.demo.service.GoogleCalendarSyncService;
import com.familyhub.demo.service.GoogleOAuthService;
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

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class GoogleEventIsolationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired GoogleSyncedCalendarRepository calendars;
    @Autowired GoogleCalendarSyncService sync;
    @Autowired GoogleOAuthService oauth;

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
