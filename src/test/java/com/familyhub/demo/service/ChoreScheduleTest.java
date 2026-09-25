package com.familyhub.demo.service;

import com.familyhub.demo.dto.ChoreBoardItemResponse;
import com.familyhub.demo.dto.ChoreDueState;
import com.familyhub.demo.dto.CreateChoreTemplateRequest;
import com.familyhub.demo.dto.UpdateChoreTemplateRequest;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.model.*;
import com.familyhub.demo.repository.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static com.familyhub.demo.TestDataFactory.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChoreScheduleTest {
    private final ChoreTemplateRepository templates = mock(ChoreTemplateRepository.class);
    private final ChorePeriodCompletionRepository completions = mock(ChorePeriodCompletionRepository.class);
    private final ChorePeriodCompletionWriter writer = mock(ChorePeriodCompletionWriter.class);
    private final FamilyMemberRepository members = mock(FamilyMemberRepository.class);
    private final Family family = createFamily();
    private final FamilyMember member = createFamilyMember(family);

    private ChoreBoardItemResponse item(LocalDate today, ChoreCadence cadence, DayOfWeek weekday,
                                         Integer monthDay, boolean completed) {
        family.setTimezone("UTC");
        ChoreService service = new ChoreService(templates, completions, writer, members,
                Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
        ChoreTemplate template = createChoreTemplate(family, member, "Test", cadence, LocalDate.of(2020, 1, 1));
        template.setDueWeekday(weekday);
        template.setDueDayOfMonth(monthDay);
        when(templates.findActiveByFamily(family)).thenReturn(List.of(template));
        ChorePeriodCompletion completion = new ChorePeriodCompletion();
        completion.setChoreTemplate(template);
        completion.setCompletedAt(today.atStartOfDay());
        when(completions.findByTemplateIdsAndPeriod(any(), any(), any()))
                .thenReturn(completed ? List.of(completion) : List.of());
        var board = service.getBoard(family);
        return switch (cadence) {
            case DAILY -> board.today().assignees().getFirst().chores().getFirst();
            case WEEKLY -> board.thisWeek().assignees().getFirst().chores().getFirst();
            case MONTHLY -> board.thisMonth().assignees().getFirst().chores().getFirst();
        };
    }

    @Test
    void dailyAndLegacySchedulesRemainUsable() {
        assertThat(item(LocalDate.of(2026, 5, 17), ChoreCadence.DAILY, null, null, false).dueState())
                .isEqualTo(ChoreDueState.DUE);
        for (ChoreCadence cadence : List.of(ChoreCadence.WEEKLY, ChoreCadence.MONTHLY)) {
            var legacy = item(LocalDate.of(2026, 5, 17), cadence, null, null, false);
            assertThat(legacy.dueDate()).isNull();
            assertThat(legacy.dueState()).isEqualTo(ChoreDueState.UNSCHEDULED);
        }
    }

    @Test
    void scheduledDueStateUsesFamilyTimezoneRatherThanUtcDate() {
        // It is Monday in UTC but still Sunday in Los Angeles.
        family.setTimezone("America/Los_Angeles");
        ChoreService service = new ChoreService(templates, completions, writer, members,
                Clock.fixed(Instant.parse("2026-05-18T02:00:00Z"), ZoneOffset.UTC));
        ChoreTemplate template = createChoreTemplate(family, member, "Bins", ChoreCadence.WEEKLY,
                LocalDate.of(2026, 1, 1));
        template.setDueWeekday(DayOfWeek.SUNDAY);
        when(templates.findActiveByFamily(family)).thenReturn(List.of(template));
        when(completions.findByTemplateIdsAndPeriod(any(), any(), any())).thenReturn(List.of());
        var board = service.getBoard(family);
        var chore = board.thisWeek().assignees().getFirst().chores().getFirst();
        assertThat(board.thisWeek().periodStartDate()).isEqualTo(LocalDate.of(2026, 5, 17));
        assertThat(chore.dueDate()).isEqualTo(LocalDate.of(2026, 5, 17));
        assertThat(chore.dueState()).isEqualTo(ChoreDueState.DUE);
    }

    @Test
    void weeklySundayTuesdaySaturdayAndPeriodReset() {
        LocalDate sunday = LocalDate.of(2026, 5, 17);
        for (DayOfWeek weekday : List.of(DayOfWeek.SUNDAY, DayOfWeek.TUESDAY, DayOfWeek.SATURDAY)) {
            assertThat(item(sunday, ChoreCadence.WEEKLY, weekday, null, false).dueDate())
                    .isEqualTo(sunday.plusDays(weekday.getValue() % 7));
        }
        assertThat(item(sunday, ChoreCadence.WEEKLY, DayOfWeek.TUESDAY, null, false).dueState())
                .isEqualTo(ChoreDueState.UPCOMING);
        assertThat(item(sunday.plusDays(2), ChoreCadence.WEEKLY, DayOfWeek.TUESDAY, null, false).dueState())
                .isEqualTo(ChoreDueState.DUE);
        assertThat(item(sunday.plusDays(3), ChoreCadence.WEEKLY, DayOfWeek.TUESDAY, null, false).dueState())
                .isEqualTo(ChoreDueState.OVERDUE);
        assertThat(item(sunday.plusDays(3), ChoreCadence.WEEKLY, DayOfWeek.TUESDAY, null, true).dueState())
                .isEqualTo(ChoreDueState.COMPLETE);
        var nextWeek = item(sunday.plusDays(7), ChoreCadence.WEEKLY, DayOfWeek.TUESDAY, null, false);
        assertThat(nextWeek.dueDate()).isEqualTo(sunday.plusDays(9));
        assertThat(nextWeek.dueState()).isEqualTo(ChoreDueState.UPCOMING);
    }

    @Test
    void monthlyDaysClampAtMonthEndIncludingLeapYear() {
        assertThat(item(LocalDate.of(2026, 5, 14), ChoreCadence.MONTHLY, null, 15, false).dueState())
                .isEqualTo(ChoreDueState.UPCOMING);
        assertThat(item(LocalDate.of(2026, 5, 15), ChoreCadence.MONTHLY, null, 15, false).dueState())
                .isEqualTo(ChoreDueState.DUE);
        assertThat(item(LocalDate.of(2026, 5, 16), ChoreCadence.MONTHLY, null, 15, false).dueState())
                .isEqualTo(ChoreDueState.OVERDUE);
        for (int day : List.of(28, 29, 30, 31)) {
            assertThat(item(LocalDate.of(2026, 2, 1), ChoreCadence.MONTHLY, null, day, false).dueDate())
                    .isEqualTo(LocalDate.of(2026, 2, Math.min(day, 28)));
            assertThat(item(LocalDate.of(2028, 2, 1), ChoreCadence.MONTHLY, null, day, false).dueDate())
                    .isEqualTo(LocalDate.of(2028, 2, Math.min(day, 29)));
        }
        assertThat(item(LocalDate.of(2026, 4, 1), ChoreCadence.MONTHLY, null, 31, false).dueDate())
                .isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    void invalidScheduleCombinationsAreRejectedAndCadenceEditClearsLegacySchedule() {
        family.setTimezone("UTC");
        ChoreService service = new ChoreService(templates, completions, writer, members, Clock.systemUTC());
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        for (var request : List.of(
                new CreateChoreTemplateRequest("Test", MEMBER_ID, ChoreCadence.DAILY, LocalDate.now(), DayOfWeek.MONDAY, null),
                new CreateChoreTemplateRequest("Test", MEMBER_ID, ChoreCadence.DAILY, LocalDate.now(), null, 1),
                new CreateChoreTemplateRequest("Test", MEMBER_ID, ChoreCadence.WEEKLY, LocalDate.now(), null, 15),
                new CreateChoreTemplateRequest("Test", MEMBER_ID, ChoreCadence.MONTHLY, LocalDate.now(), DayOfWeek.TUESDAY, null),
                new CreateChoreTemplateRequest("Test", MEMBER_ID, ChoreCadence.MONTHLY, LocalDate.now(), null, 0),
                new CreateChoreTemplateRequest("Test", MEMBER_ID, ChoreCadence.MONTHLY, LocalDate.now(), null, 32))) {
            assertThatThrownBy(() -> service.createTemplate(request, family)).isInstanceOf(BadRequestException.class);
        }
        ChoreTemplate template = createChoreTemplate(family, member, "Test", ChoreCadence.WEEKLY, LocalDate.of(2020, 1, 1));
        template.setDueWeekday(DayOfWeek.TUESDAY);
        when(templates.findByFamilyAndId(family, CHORE_TEMPLATE_ID)).thenReturn(Optional.of(template));
        when(templates.save(template)).thenReturn(template);
        service.updateTemplate(CHORE_TEMPLATE_ID,
                new UpdateChoreTemplateRequest(null, null, ChoreCadence.MONTHLY, null, null, null, null), family);
        assertThat(template.getCadence()).isEqualTo(ChoreCadence.MONTHLY);
        assertThat(template.getDueWeekday()).isNull();
        assertThat(template.getDueDayOfMonth()).isNull();
    }
}
