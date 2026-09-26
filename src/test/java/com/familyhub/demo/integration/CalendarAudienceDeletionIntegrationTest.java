package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.service.FamilyMemberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class CalendarAudienceDeletionIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired FamilyRepository families;
    @Autowired FamilyMemberService service;

    @Test
    void memberDeletionKeepsFamilyAndSharedEventsButRemovesSoleMemberEvent() {
        UUID family = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID familyEvent = UUID.randomUUID();
        UUID shared = UUID.randomUUID();
        UUID sole = UUID.randomUUID();
        jdbc.update("INSERT INTO family (id,name,username,password_hash) VALUES (?,?,?,?)",
                family, "Test", family.toString(), "hash");
        for (UUID member : new UUID[]{first, second}) {
            jdbc.update("INSERT INTO family_member (id,family_id,name,color) VALUES (?,?,?,?)",
                    member, family, "Member", "CORAL");
        }
        event(familyEvent, family, "FAMILY");
        event(shared, family, "MEMBERS");
        event(sole, family, "MEMBERS");
        for (UUID member : new UUID[]{first, second}) {
            jdbc.update("INSERT INTO calendar_event_member (event_id,member_id) VALUES (?,?)", shared, member);
        }
        jdbc.update("INSERT INTO calendar_event_member (event_id,member_id) VALUES (?,?)", sole, first);

        service.deleteFamilyMember(families.findById(family).orElseThrow(), first);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE id=?", Integer.class, familyEvent)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE id=?", Integer.class, shared)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event WHERE id=?", Integer.class, sole)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event_member WHERE event_id=? AND member_id=?", Integer.class, shared, second)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event_member WHERE member_id=?", Integer.class, first)).isZero();
    }

    private void event(UUID id, UUID family, String audience) {
        jdbc.update("""
                INSERT INTO calendar_event (id,title,start_time,end_time,date,family_id,source,audience_type)
                VALUES (?,'Event','09:00','10:00','2025-06-15',?,'NATIVE',?)
                """, id, family, audience);
    }
}
