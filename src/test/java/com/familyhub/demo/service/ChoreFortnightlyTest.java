package com.familyhub.demo.service;

import com.familyhub.demo.dto.*;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.model.*;
import com.familyhub.demo.repository.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static com.familyhub.demo.TestDataFactory.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChoreFortnightlyTest {
    private static final LocalDate ANCHOR = LocalDate.of(2026, 10, 3); // Saturday
    private final ChoreTemplateRepository templates = mock(ChoreTemplateRepository.class);
    private final ChorePeriodCompletionRepository completions = mock(ChorePeriodCompletionRepository.class);
    private final ChorePeriodCompletionWriter writer = mock(ChorePeriodCompletionWriter.class);
    private final FamilyMemberRepository members = mock(FamilyMemberRepository.class);
    private final Family family = createFamily();
    private final FamilyMember member = createFamilyMember(family);
    private final ChoreTemplate template = createChoreTemplate(family, member, "Bins", ChoreCadence.FORTNIGHTLY,
            LocalDate.of(2026, 1, 1));

    private ChoreService at(LocalDate date) {
        family.setTimezone("UTC");
        template.setDueWeekday(DayOfWeek.SATURDAY);
        if (template.getRecurrenceAnchorDate() == null) template.setRecurrenceAnchorDate(ANCHOR);
        when(templates.findActiveByFamily(family)).thenReturn(List.of(template));
        when(templates.findByFamilyAndId(family, template.getId())).thenReturn(Optional.of(template));
        return new ChoreService(templates, completions, writer, members,
                Clock.fixed(date.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
    }

    private ChoreBoardItemResponse item(LocalDate date) {
        return at(date).getBoard(family).thisWeek().assignees().getFirst().chores().getFirst();
    }

    @Test
    void validatesCreateAndUpdateSchedules() {
        var service = at(ANCHOR);
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(templates.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var created = service.createTemplate(new CreateChoreTemplateRequest("Bins", MEMBER_ID,
                ChoreCadence.FORTNIGHTLY, ANCHOR, DayOfWeek.SATURDAY, null, ANCHOR), family);
        assertThat(created.recurrenceAnchorDate()).isEqualTo(ANCHOR);
        for (var request : List.of(
                new CreateChoreTemplateRequest("Bins", MEMBER_ID, ChoreCadence.FORTNIGHTLY, ANCHOR, null, null, ANCHOR),
                new CreateChoreTemplateRequest("Bins", MEMBER_ID, ChoreCadence.FORTNIGHTLY, ANCHOR, DayOfWeek.SATURDAY, null, null),
                new CreateChoreTemplateRequest("Bins", MEMBER_ID, ChoreCadence.FORTNIGHTLY, ANCHOR, DayOfWeek.SATURDAY, 3, ANCHOR),
                new CreateChoreTemplateRequest("Bins", MEMBER_ID, ChoreCadence.FORTNIGHTLY, ANCHOR, DayOfWeek.FRIDAY, null, ANCHOR),
                new CreateChoreTemplateRequest("Bins", MEMBER_ID, ChoreCadence.DAILY, ANCHOR, null, null, ANCHOR))) {
            assertThatThrownBy(() -> service.createTemplate(request, family)).isInstanceOf(BadRequestException.class);
        }
        assertThatThrownBy(() -> service.updateTemplate(template.getId(),
                new UpdateChoreTemplateRequest(null, null, ChoreCadence.FORTNIGHTLY, null, null,
                        DayOfWeek.FRIDAY, null, ANCHOR), family)).isInstanceOf(BadRequestException.class);
        service.updateTemplate(template.getId(),
                new UpdateChoreTemplateRequest(null, null, ChoreCadence.WEEKLY, null, null,
                        DayOfWeek.SATURDAY, null, null), family);
        assertThat(template.getRecurrenceAnchorDate()).isNull();
    }

    @Test
    void usesFirstDueDateAndFourteenDayPeriodsAcrossCalendarBoundaries() {
        assertThat(item(ANCHOR.minusDays(20)).dueDate()).isEqualTo(ANCHOR);
        assertThat(item(ANCHOR.minusDays(20)).dueState()).isEqualTo(ChoreDueState.UPCOMING);
        assertThat(item(ANCHOR.minusDays(20)).completionAvailable()).isFalse();
        assertThat(item(ANCHOR.minusDays(1)).dueState()).isEqualTo(ChoreDueState.UPCOMING);
        assertThat(item(ANCHOR).dueState()).isEqualTo(ChoreDueState.DUE);
        assertThat(item(ANCHOR.plusDays(6)).dueState()).isEqualTo(ChoreDueState.OVERDUE);
        assertThat(item(ANCHOR.plusDays(7)).dueState()).isEqualTo(ChoreDueState.UPCOMING);
        assertThat(item(ANCHOR.plusDays(14)).dueDate()).isEqualTo(ANCHOR.plusDays(14));
        assertThat(item(ANCHOR.plusDays(28)).dueDate()).isEqualTo(ANCHOR.plusDays(28));
        assertThat(item(ANCHOR.plusDays(14)).periodStartDate()).isEqualTo(ANCHOR.plusDays(7));
        assertThat(item(ANCHOR.plusDays(14)).periodEndDate()).isEqualTo(ANCHOR.plusDays(20));
        assertThat(item(ANCHOR.plusDays(91)).dueDate()).isEqualTo(ANCHOR.plusDays(98));

        template.setRecurrenceAnchorDate(LocalDate.of(2027, 12, 25)); // Saturday
        assertThat(item(LocalDate.of(2028, 1, 8)).dueDate()).isEqualTo(LocalDate.of(2028, 1, 8));
        template.setRecurrenceAnchorDate(LocalDate.of(2028, 2, 19)); // Saturday, leap-year February
        assertThat(item(LocalDate.of(2028, 3, 4)).dueDate()).isEqualTo(LocalDate.of(2028, 3, 4));
    }

    @Test
    void completionIsBoundToExactFortnightAndStalePeriodsAreRejected() {
        var first = at(ANCHOR);
        var firstPeriod = new UpdateCurrentPeriodCompletionRequest(ChoreScope.THIS_WEEK, ANCHOR.minusDays(7));
        var completion = new ChorePeriodCompletion();
        completion.setChoreTemplate(template);
        completion.setCompletedAt(ANCHOR.atStartOfDay());
        when(writer.createCompletion(eq(template.getId()), eq(ANCHOR.minusDays(7)), eq(ANCHOR.plusDays(6)), any()))
                .thenReturn(completion);
        assertThat(first.completeCurrentPeriod(template.getId(), firstPeriod, family).item().dueState())
                .isEqualTo(ChoreDueState.COMPLETE);
        var next = at(ANCHOR.plusDays(14));
        assertThatThrownBy(() -> next.completeCurrentPeriod(template.getId(), firstPeriod, family))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("stale");
        assertThat(item(ANCHOR.plusDays(14)).completed()).isFalse();
        verify(writer).createCompletion(eq(template.getId()), eq(ANCHOR.minusDays(7)), eq(ANCHOR.plusDays(6)), any());
        assertThatThrownBy(() -> at(ANCHOR.minusDays(8)).completeCurrentPeriod(
                template.getId(), firstPeriod, family)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void boardLoadsOnlyTheCompletionForTheActiveFortnight() {
        var completion = new ChorePeriodCompletion();
        completion.setChoreTemplate(template);
        completion.setCompletedAt(ANCHOR.atStartOfDay());
        when(completions.findByTemplateIdsAndPeriod(eq(List.of(template.getId())),
                eq(ANCHOR.minusDays(7)), eq(ANCHOR.plusDays(6)))).thenReturn(List.of(completion));
        assertThat(item(ANCHOR).dueState()).isEqualTo(ChoreDueState.COMPLETE);
        assertThat(item(ANCHOR.plusDays(7)).dueState()).isEqualTo(ChoreDueState.UPCOMING);
        verify(completions).findByTemplateIdsAndPeriod(eq(List.of(template.getId())),
                eq(ANCHOR.plusDays(7)), eq(ANCHOR.plusDays(20)));
    }

    @Test
    void usesHouseholdLocalDateAndKeepsFamiliesIsolated() {
        family.setTimezone("America/Los_Angeles");
        template.setDueWeekday(DayOfWeek.SATURDAY);
        template.setRecurrenceAnchorDate(ANCHOR);
        when(templates.findActiveByFamily(family)).thenReturn(List.of(template));
        var service = new ChoreService(templates, completions, writer, members,
                Clock.fixed(Instant.parse("2026-10-03T02:00:00Z"), ZoneOffset.UTC));
        assertThat(service.getBoard(family).thisWeek().assignees().getFirst().chores().getFirst().dueState())
                .isEqualTo(ChoreDueState.UPCOMING); // Friday in LA
        var otherFamily = createOtherFamily();
        assertThatThrownBy(() -> service.completeCurrentPeriod(template.getId(),
                new UpdateCurrentPeriodCompletionRequest(ChoreScope.THIS_WEEK, ANCHOR.minusDays(7)), otherFamily))
                .isInstanceOf(com.familyhub.demo.exception.ResourceNotFoundException.class);
    }
}
