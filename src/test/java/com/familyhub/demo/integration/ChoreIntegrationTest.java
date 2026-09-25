package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.model.ChorePeriodCompletion;
import com.familyhub.demo.model.ChoreTemplate;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.repository.ChorePeriodCompletionRepository;
import com.familyhub.demo.security.WithMockFamily;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.familyhub.demo.TestDataFactory.FAMILY_ID;
import static com.familyhub.demo.TestDataFactory.MEMBER_ID;
import static com.familyhub.demo.TestDataFactory.OTHER_FAMILY_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, ChoreIntegrationTest.FixedClockConfig.class})
@ActiveProfiles("test")
class ChoreIntegrationTest {
    private static final UUID OTHER_MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @MockitoSpyBean
    private ChorePeriodCompletionRepository chorePeriodCompletionRepository;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM chore_period_completion");
        jdbcTemplate.update("DELETE FROM chore_template");
        jdbcTemplate.update("DELETE FROM family_member");
        // Other integration tests share this Testcontainer and can leave list items that
        // reference list_category via a NO ACTION composite FK (fk_shared_list_item_category).
        // The family cascade can't resolve that reference, so clear the items before wiping families.
        jdbcTemplate.update("DELETE FROM shared_list_item");
        jdbcTemplate.update("DELETE FROM family");

        jdbcTemplate.update(
                "INSERT INTO family (id, name, username, password_hash, timezone) VALUES (?, ?, ?, ?, ?)",
                FAMILY_ID,
                "Test Family",
                "testfamily",
                "$2a$10$dummyhashfortesting",
                "America/Los_Angeles"
        );

        jdbcTemplate.update(
                "INSERT INTO family_member (id, family_id, name, color, email) VALUES (?, ?, ?, ?, ?)",
                MEMBER_ID,
                FAMILY_ID,
                "Test Member",
                "CORAL",
                "member@test.com"
        );
    }

    @Test
    @WithMockFamily
    void recurringTemplate_roundTripsBoardCompletionUncompletionAndArchive() throws Exception {
        String location = mockMvc.perform(post("/api/chores/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Brush teeth",
                                  "assignedToMemberId": "00000000-0000-0000-0000-000000000002",
                                  "cadence": "DAILY",
                                  "activeFrom": "2026-05-19"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.title").value("Brush teeth"))
                .andExpect(jsonPath("$.data.cadence").value("DAILY"))
                .andExpect(jsonPath("$.data.archived").value(false))
                .andReturn()
                .getResponse()
                .getHeader("Location");

        String templateId = location.substring(location.lastIndexOf('/') + 1);

        mockMvc.perform(get("/api/chores/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timezone").value("America/Los_Angeles"))
                .andExpect(jsonPath("$.data.today.periodStartDate").value("2026-05-19"))
                .andExpect(jsonPath("$.data.today.summary.total").value(1))
                .andExpect(jsonPath("$.data.today.assignees[0].chores[0].templateId").value(templateId))
                .andExpect(jsonPath("$.data.today.assignees[0].chores[0].completed").value(false));

        mockMvc.perform(put("/api/chores/templates/{id}/current-period-completion", templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope": "TODAY", "periodStartDate": "2026-05-19"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.completed").value(true));

        mockMvc.perform(get("/api/chores/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.today.summary.completed").value(1))
                .andExpect(jsonPath("$.data.today.assignees[0].chores[0].completed").value(true));

        mockMvc.perform(delete("/api/chores/templates/{id}/current-period-completion", templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope": "TODAY", "periodStartDate": "2026-05-19"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.completed").value(false));

        mockMvc.perform(patch("/api/chores/templates/{id}", templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"archived": true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.archived").value(true));

        mockMvc.perform(get("/api/chores/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.today.summary.total").value(0));
    }

    @Test
    @WithMockFamily
    void scheduledTemplate_roundTripsApiAndKeepsPeriodCompletion() throws Exception {
        String location = mockMvc.perform(post("/api/chores/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Bins","assignedToMemberId":"00000000-0000-0000-0000-000000000002",
                                 "cadence":"WEEKLY","activeFrom":"2026-05-19","dueWeekday":"TUESDAY"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.dueWeekday").value("TUESDAY"))
                .andReturn().getResponse().getHeader("Location");
        String templateId = location.substring(location.lastIndexOf('/') + 1);
        mockMvc.perform(get("/api/chores/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.thisWeek.assignees[0].chores[0].dueDate").value("2026-05-19"))
                .andExpect(jsonPath("$.data.thisWeek.assignees[0].chores[0].dueState").value("DUE"));
        mockMvc.perform(put("/api/chores/templates/{id}/current-period-completion", templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"THIS_WEEK\",\"periodStartDate\":\"2026-05-17\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.dueState").value("COMPLETE"));
        mockMvc.perform(patch("/api/chores/templates/{id}", templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cadence\":\"MONTHLY\",\"dueWeekday\":null,\"dueDayOfMonth\":31}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dueWeekday").isEmpty())
                .andExpect(jsonPath("$.data.dueDayOfMonth").value(31));
        mockMvc.perform(get("/api/chores/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.thisMonth.assignees[0].chores[0].dueDate").value("2026-05-31"))
                .andExpect(jsonPath("$.data.thisMonth.assignees[0].chores[0].dueState").value("UPCOMING"));
        Integer completionCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chore_period_completion WHERE chore_template_id = ?", Integer.class, UUID.fromString(templateId));
        assertThat(completionCount).isEqualTo(1);
    }

    @Test
    @WithMockFamily
    void invalidScheduleCombinationIsRejectedByApi() throws Exception {
        mockMvc.perform(post("/api/chores/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Bins","assignedToMemberId":"00000000-0000-0000-0000-000000000002",
                                 "cadence":"DAILY","activeFrom":"2026-05-19","dueWeekday":"TUESDAY"}
                                """))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM chore_template", Integer.class)).isZero();
    }

    @Test
    @WithMockFamily
    void activeFrom_afterCurrentPeriod_isExcludedFromBoard() throws Exception {
        mockMvc.perform(post("/api/chores/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Deep clean fridge",
                                  "assignedToMemberId": "00000000-0000-0000-0000-000000000002",
                                  "cadence": "MONTHLY",
                                  "activeFrom": "2026-06-01"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/chores/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.thisMonth.summary.total").value(0));
    }

    @Test
    @WithMockFamily
    void activeFrom_laterWithinCurrentWeekAndMonth_staysHiddenUntilActivationDate() throws Exception {
        mockMvc.perform(post("/api/chores/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Take out trash",
                                  "assignedToMemberId": "00000000-0000-0000-0000-000000000002",
                                  "cadence": "WEEKLY",
                                  "activeFrom": "2026-05-21"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/chores/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Deep clean fridge",
                                  "assignedToMemberId": "00000000-0000-0000-0000-000000000002",
                                  "cadence": "MONTHLY",
                                  "activeFrom": "2026-05-25"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/chores/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.thisWeek.summary.total").value(0))
                .andExpect(jsonPath("$.data.thisMonth.summary.total").value(0));
    }

    @Test
    @WithMockFamily
    void board_withPersistedInvalidTimezone_fallsBackToDefaultInsteadOf500() throws Exception {
        jdbcTemplate.update(
                "UPDATE family SET timezone = ? WHERE id = ?",
                "Mars/Olympus",
                FAMILY_ID
        );

        mockMvc.perform(get("/api/chores/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timezone").value("America/Los_Angeles"));
    }

    @Test
    @WithMockFamily
    void completionAndUncompletion_stalePeriod_returns400() throws Exception {
        String templateId = createDailyTemplate();

        mockMvc.perform(put("/api/chores/templates/{id}/current-period-completion", templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope": "TODAY", "periodStartDate": "2026-05-18"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Chore period is stale. Refresh and try again."));

        mockMvc.perform(delete("/api/chores/templates/{id}/current-period-completion", templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope": "TODAY", "periodStartDate": "2026-05-18"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Chore period is stale. Refresh and try again."));
    }

    @Test
    @WithMockFamily
    void completionAndUncompletion_inactiveTemplate_return400() throws Exception {
        String location = mockMvc.perform(post("/api/chores/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Take out trash",
                                  "assignedToMemberId": "00000000-0000-0000-0000-000000000002",
                                  "cadence": "WEEKLY",
                                  "activeFrom": "2026-05-21"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        String templateId = location.substring(location.lastIndexOf('/') + 1);

        mockMvc.perform(put("/api/chores/templates/{id}/current-period-completion", templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope": "THIS_WEEK", "periodStartDate": "2026-05-17"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Chore template is not active for the current period."));

        mockMvc.perform(delete("/api/chores/templates/{id}/current-period-completion", templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope": "THIS_WEEK", "periodStartDate": "2026-05-17"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Chore template is not active for the current period."));
    }

    @Test
    @WithMockFamily
    void completionAndUncompletion_archivedTemplate_return400() throws Exception {
        String templateId = createDailyTemplate();

        mockMvc.perform(patch("/api/chores/templates/{id}", templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"archived": true}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/chores/templates/{id}/current-period-completion", templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope": "TODAY", "periodStartDate": "2026-05-19"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Chore template is not active for the current period."));

        mockMvc.perform(delete("/api/chores/templates/{id}/current-period-completion", templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope": "TODAY", "periodStartDate": "2026-05-19"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Chore template is not active for the current period."));
    }

    @Test
    @WithMockFamily
    void duplicateCompletionPut_isIdempotentAndKeepsSingleCompletionRow() throws Exception {
        String templateId = createDailyTemplate();

        mockMvc.perform(put("/api/chores/templates/{id}/current-period-completion", templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope": "TODAY", "periodStartDate": "2026-05-19"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.completed").value(true));

        mockMvc.perform(put("/api/chores/templates/{id}/current-period-completion", templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope": "TODAY", "periodStartDate": "2026-05-19"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.completed").value(true));

        assertThat(chorePeriodCompletionCount()).isEqualTo(1);
    }

    @Test
    @WithMockFamily
    void concurrentCompletionPut_whenBothRequestsSeeNoExistingRow_isIdempotent() throws Exception {
        String templateId = createDailyTemplate();
        LocalDate periodStart = LocalDate.of(2026, 5, 19);
        CyclicBarrier bothRequestsSawMissingCompletion = new CyclicBarrier(2);
        AtomicInteger gatedEmptyLookups = new AtomicInteger();

        doAnswer(invocation -> {
            ChoreTemplate template = invocation.getArgument(0);
            Optional<ChorePeriodCompletion> result = entityManager.createQuery("""
                            select c
                            from ChorePeriodCompletion c
                            where c.choreTemplate = :template
                              and c.periodStartDate = :periodStart
                              and c.periodEndDate = :periodEnd
                            """, ChorePeriodCompletion.class)
                    .setParameter("template", template)
                    .setParameter("periodStart", periodStart)
                    .setParameter("periodEnd", periodStart)
                    .setMaxResults(1)
                    .getResultList()
                    .stream()
                    .findFirst();

            if (result.isEmpty() && gatedEmptyLookups.getAndIncrement() < 2) {
                bothRequestsSawMissingCompletion.await(5, TimeUnit.SECONDS);
            }

            return result;
        }).when(chorePeriodCompletionRepository).findByChoreTemplateAndPeriodStartDateAndPeriodEndDate(
                any(ChoreTemplate.class),
                eq(periodStart),
                eq(periodStart)
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startTogether = new CountDownLatch(1);
        try {
            List<Future<Integer>> responses = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                responses.add(executor.submit(() -> {
                    startTogether.await(5, TimeUnit.SECONDS);
                    return mockMvc.perform(put("/api/chores/templates/{id}/current-period-completion", templateId)
                                    .with(testFamilyAuthentication())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"scope": "TODAY", "periodStartDate": "2026-05-19"}
                                            """))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }

            startTogether.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> response : responses) {
                statuses.add(response.get(10, TimeUnit.SECONDS));
            }

            assertThat(statuses).containsOnly(200);
            assertThat(chorePeriodCompletionCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @WithMockFamily
    void createTemplate_assigneeFromDifferentFamily_returns403ThroughRealStack() throws Exception {
        insertOtherFamilyAndMember();

        mockMvc.perform(post("/api/chores/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Should be rejected",
                                  "assignedToMemberId": "00000000-0000-0000-0000-000000000005",
                                  "cadence": "DAILY",
                                  "activeFrom": "2026-05-19"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Unauthorized"));

        assertThat(choreTemplateCount()).isZero();
    }

    @Test
    @WithMockFamily
    void deleteFamilyMember_withActiveRecurringTemplate_returns400() throws Exception {
        createDailyTemplate();

        mockMvc.perform(delete("/api/family/members/{id}", MEMBER_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Reassign or archive this member's recurring chores before deleting them."));
    }

    private String createDailyTemplate() throws Exception {
        String location = mockMvc.perform(post("/api/chores/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Brush teeth",
                                  "assignedToMemberId": "00000000-0000-0000-0000-000000000002",
                                  "cadence": "DAILY",
                                  "activeFrom": "2026-05-19"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        return location.substring(location.lastIndexOf('/') + 1);
    }

    private void insertOtherFamilyAndMember() {
        jdbcTemplate.update(
                "INSERT INTO family (id, name, username, password_hash, timezone) VALUES (?, ?, ?, ?, ?)",
                OTHER_FAMILY_ID,
                "Other Family",
                "otherfamily",
                "$2a$10$dummyhashfortesting",
                "America/Los_Angeles"
        );

        jdbcTemplate.update(
                "INSERT INTO family_member (id, family_id, name, color, email) VALUES (?, ?, ?, ?, ?)",
                OTHER_MEMBER_ID,
                OTHER_FAMILY_ID,
                "Other Member",
                "TEAL",
                "other@test.com"
        );
    }

    private int choreTemplateCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chore_template", Integer.class);
        return count == null ? 0 : count;
    }

    private int chorePeriodCompletionCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chore_period_completion", Integer.class);
        return count == null ? 0 : count;
    }

    private RequestPostProcessor testFamilyAuthentication() {
        Family family = new Family();
        family.setId(FAMILY_ID);
        family.setUsername("testfamily");
        family.setName("Test Family");
        family.setPasswordHash("$2a$10$dummyhashfortesting");

        return authentication(new UsernamePasswordAuthenticationToken(
                family,
                null,
                Collections.emptyList()
        ));
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-05-19T16:00:00Z"), ZoneOffset.UTC);
        }
    }
}
